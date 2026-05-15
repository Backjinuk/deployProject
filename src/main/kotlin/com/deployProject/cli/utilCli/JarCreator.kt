package com.deployProject.cli.utilCli

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

object JarCreator {

    private val os = System.getProperty("os.name").lowercase()

    @Throws(IOException::class)
    fun createJar(sourceDirPath: String, jarFilePath: String, defaults: Map<String, String>) {
        val seenEntries = mutableSetOf<String>()
        var sourceDir: Path = Paths.get(sourceDirPath)
        val outerBootJar = resolveOuterBootJar()

        if (!os.contains("windows") && !os.contains("mac")) {
            val wd = Paths.get("").toAbsolutePath()
            val projectRoot = wd.parent?.parent
                ?: throw IllegalStateException("Cannot resolve project root from working directory: $wd")
            sourceDir = projectRoot.resolve(sourceDirPath)
        }

        FileOutputStream(jarFilePath).use { fos ->
            JarOutputStream(fos, createManifest()).use { jos ->
                if (Files.exists(sourceDir)) {
                    copyClassesFromDirectory(sourceDir, jos, seenEntries)
                } else if (outerBootJar != null) {
                    copyClassesFromBootJar(outerBootJar, jos, seenEntries)
                } else {
                    val codeSourceUri: URI = this::class.java.protectionDomain.codeSource.location.toURI()
                    val jarFsUri: URI = when {
                        codeSourceUri.scheme == "file" && codeSourceUri.path.endsWith(".jar") -> URI.create("jar:$codeSourceUri")
                        codeSourceUri.scheme == "jar" -> codeSourceUri
                        else -> throw IOException("Class source not found. uri=$codeSourceUri")
                    }

                    FileSystems.newFileSystem(jarFsUri, emptyMap<String, Any>()).use { fs ->
                        val jarRoot = fs.getPath("/")
                        Files.walk(jarRoot).use { stream ->
                            stream
                                .filter { !Files.isDirectory(it) && it.toString().endsWith(".class") }
                                .filter { !it.toString().startsWith("/META-INF/") }
                                .forEach { pathInJar ->
                                    val name = pathInJar.toString().removePrefix("/")
                                    if (seenEntries.add(name)) {
                                        val lastModified = Files.getLastModifiedTime(pathInJar).toMillis()
                                        jos.putNextEntry(JarEntry(name).apply { time = lastModified })
                                        Files.newInputStream(pathInJar).use { input -> input.copyTo(jos) }
                                        jos.closeEntry()
                                    }
                                }
                        }
                    }
                }

                jos.setLevel(java.util.zip.Deflater.BEST_SPEED)

                val depJars = mutableListOf<File>()
                val seenDepJarPaths = mutableSetOf<String>()

                // 수정 이유: local function/function reference가 생성한 synthetic class 로딩 오류를 피한다.
                for (depJar in jarsFromJavaClassPath()) {
                    if (isSpringBootJar(depJar)) {
                        addNestedBootLibs(depJar, depJars, seenDepJarPaths)
                    } else {
                        addDepJarIfAbsent(depJar, depJars, seenDepJarPaths)
                    }
                }

                outerBootJar?.let { addNestedBootLibs(it, depJars, seenDepJarPaths) }

                depJars.forEach { depJar ->
                    runCatching {
                        JarFile(depJar).use { jarFile ->
                            jarFile.entries().asSequence()
                                .mapNotNull { entry -> dependencyEntryName(entry)?.let { it to entry } }
                                .forEach { (entryName, entry) ->
                                    if (seenEntries.add(entryName)) {
                                        jos.putNextEntry(JarEntry(entryName).apply { time = entry.time })
                                        jarFile.getInputStream(entry).use { input -> input.copyTo(jos) }
                                        jos.closeEntry()
                                    }
                                }
                        }
                    }
                }

                val props = Properties().apply {
                    defaults.forEach { (k, v) -> setProperty(k, v) }
                }
                jos.putNextEntry(JarEntry("defaults.properties"))
                props.store(jos, "GitInfoCli defaults")
                jos.closeEntry()
            }
        }
    }

    private fun copyClassesFromDirectory(sourceDir: Path, jos: JarOutputStream, seenEntries: MutableSet<String>) {
        Files.walk(sourceDir).use { stream ->
            stream
                .filter(Files::isRegularFile)
                .forEach { path ->
                    val entryName = sourceDir.relativize(path).toString().replace('\\', '/')
                    if (seenEntries.add(entryName)) {
                        jos.putNextEntry(JarEntry(entryName).apply { time = path.toFile().lastModified() })
                        Files.copy(path, jos)
                        jos.closeEntry()
                    }
                }
        }
    }

    private fun copyClassesFromBootJar(bootJar: File, jos: JarOutputStream, seenEntries: MutableSet<String>) {
        JarFile(bootJar).use { jarFile ->
            jarFile.entries().asSequence()
                .filter { !it.isDirectory }
                .filter { it.name.startsWith("BOOT-INF/classes/") }
                .forEach { entry ->
                    val entryName = entry.name.removePrefix("BOOT-INF/classes/")
                    if (entryName.isNotBlank() && seenEntries.add(entryName)) {
                        jos.putNextEntry(JarEntry(entryName).apply { time = entry.time })
                        jarFile.getInputStream(entry).use { input -> input.copyTo(jos) }
                        jos.closeEntry()
                    }
                }
        }
    }

    private fun dependencyEntryName(entry: JarEntry): String? {
        if (entry.isDirectory) return null

        val name = entry.name
        return when {
            name == JarFile.MANIFEST_NAME -> null
            name.startsWith("META-INF/") -> null
            name == "org/slf4j/impl/StaticLoggerBinder.class" -> null
            name.startsWith("BOOT-INF/lib/") -> null
            name.startsWith("BOOT-INF/classes/") -> name.removePrefix("BOOT-INF/classes/").takeIf { it.isNotBlank() }
            name.startsWith("BOOT-INF/") -> null
            else -> name
        }
    }

    private fun addNestedBootLibs(bootJar: File, dest: MutableList<File>, seen: MutableSet<String>) {
        JarFile(bootJar).use { outerJar ->
            outerJar.entries().asSequence()
                .filter { entry ->
                    !entry.isDirectory && entry.name.startsWith("BOOT-INF/lib/") && entry.name.endsWith(".jar")
                }
                .forEach { nestedEntry ->
                    val tempJar = Files.createTempFile("deploy-project-nested-", ".jar").toFile().apply {
                        deleteOnExit()
                    }
                    outerJar.getInputStream(nestedEntry).use { input ->
                        FileOutputStream(tempJar).use { output -> input.copyTo(output) }
                    }
                    addDepJarIfAbsent(tempJar, dest, seen)
                }
        }
    }

    private fun addDepJarIfAbsent(file: File, dest: MutableList<File>, seen: MutableSet<String>) {
        if (!file.isFile || !file.extension.equals("jar", true)) return
        val key = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        if (seen.add(key)) {
            dest.add(file)
        }
    }

    private fun createManifest(): Manifest = Manifest().apply {
        mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        mainAttributes[Attributes.Name("Main-Class")] = "com.deployProject.cli.ExtractionLauncher"
    }

    private fun jarsFromJavaClassPath(): List<File> {
        val cp = System.getProperty("java.class.path") ?: return emptyList()
        return cp.split(File.pathSeparatorChar)
            .mapNotNull(::jarFileFromLocation)
            .filter { it.isFile && it.extension.equals("jar", true) }
    }

    private fun resolveOuterBootJar(): File? {
        val codeSourceLocation = runCatching {
            this::class.java.protectionDomain.codeSource.location.toString()
        }.getOrNull()

        codeSourceLocation?.let { location ->
            jarFileFromLocation(location)?.let { return it }
        }

        return jarsFromJavaClassPath().firstOrNull(::isSpringBootJar)
    }

    private fun jarFileFromLocation(value: String): File? {
        val decoded = URLDecoder.decode(value, StandardCharsets.UTF_8)
            .removePrefix("jar:nested:")
            .removePrefix("jar:file:")
            .removePrefix("jar:")
            .removePrefix("file:")

        val jarIndex = decoded.lowercase().indexOf(".jar")
        if (jarIndex < 0) return null

        var path = decoded.substring(0, jarIndex + ".jar".length)
        if (os.contains("windows") && path.matches(Regex("^/[A-Za-z]:/.*"))) {
            path = path.substring(1)
        }

        return File(path).takeIf { it.isFile }
    }

    private fun isSpringBootJar(file: File): Boolean {
        if (!file.isFile || !file.extension.equals("jar", true)) return false

        return runCatching {
            JarFile(file).use { jarFile ->
                jarFile.entries().asSequence().any { entry ->
                    entry.name.startsWith("BOOT-INF/classes/") ||
                        (entry.name.startsWith("BOOT-INF/lib/") && entry.name.endsWith(".jar"))
                }
            }
        }.getOrDefault(false)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 6) {
            error("Usage: <repoDir> [relPath] [sinceDate] [untilDate] [fileStatusType] <jarOutputDir> [deployServerDir]")
        }

        val jarOutputDir = args[5]
        val outputPath = Paths.get(jarOutputDir)
        Files.createDirectories(outputPath)

        val repoDir = args.getOrNull(0)?.takeIf { it.isNotBlank() }
            ?: error("Usage: <repoDir> [relPath] [sinceDate] [untilDate] [fileStatusType] <jarOutputDir> [deployServerDir]")
        val relPath = args.getOrNull(1) ?: ""
        val sinceDate = args.getOrNull(2)?.takeIf { it.isNotBlank() }
            ?: LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val untilDate = args.getOrNull(3)?.takeIf { it.isNotBlank() }
            ?: LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val statusType = args.getOrNull(4)?.takeIf { it.isNotBlank() } ?: "ALL"
        val deployServerDir = args.getOrNull(6)?.takeIf { it.isNotBlank() } ?: "/home/bjw/deployProject/"
        val sinceVersion = args.getOrNull(7)?.takeIf { it.isNotBlank() } ?: ""
        val untilVersion = args.getOrNull(8)?.takeIf { it.isNotBlank() } ?: ""
        val selectedVersions = args.getOrNull(9)?.takeIf { it.isNotBlank() } ?: ""
        val selectedFiles = args.getOrNull(10)?.takeIf { it.isNotBlank() } ?: ""
        val duplicateFileVersionMap = args.getOrNull(11)?.takeIf { it.isNotBlank() } ?: ""
        val jdkPath = args.getOrNull(12)?.takeIf { it.isNotBlank() } ?: ""

        val defaults = mapOf(
            "repoDir" to repoDir,
            "relPath" to relPath,
            "since" to sinceDate,
            "until" to untilDate,
            "statusType" to statusType,
            "deployServerDir" to deployServerDir,
            "sinceVersion" to sinceVersion,
            "untilVersion" to untilVersion,
            "selectedVersions" to selectedVersions,
            "selectedFiles" to selectedFiles,
            "duplicateFileVersionMap" to duplicateFileVersionMap,
            "jdkPath" to jdkPath
        )

        val sourceDirPath = if (os.contains("windows") || os.contains("mac")) {
            "./build/classes/kotlin/main"
        } else {
            "build/classes/kotlin/main"
        }

        val jarFilePath = "$jarOutputDir/deploy-project-cli.jar"
        createJar(sourceDirPath, jarFilePath, defaults)
    }
}
