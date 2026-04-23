package com.deployProject.cli.utilCli

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
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

        if (!os.contains("windows") && !os.contains("mac")) {
            val wd = Paths.get("").toAbsolutePath()
            val projectRoot = wd.parent?.parent
                ?: throw IllegalStateException("Cannot resolve project root from working directory: $wd")
            sourceDir = projectRoot.resolve(sourceDirPath)
        }

        FileOutputStream(jarFilePath).use { fos ->
            JarOutputStream(fos, createManifest()).use { jos ->
                if (Files.exists(sourceDir)) {
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
                    addDepJarIfAbsent(depJar, depJars, seenDepJarPaths)
                }

                val rawUri = this::class.java.protectionDomain.codeSource.location.toURI()
                val rawUriStr = rawUri.toString()
                var outerJarPath: String? = null
                if (rawUri.scheme == "file" && rawUri.path.endsWith(".jar")) {
                    outerJarPath = File(rawUri).absolutePath
                } else if (rawUriStr.startsWith("jar:nested:")) {
                    val nestedPart = rawUriStr.removePrefix("jar:nested:")
                    val candidate = nestedPart.substringBefore("!/")
                    outerJarPath = File(candidate).absolutePath
                }

                if (outerJarPath != null) {
                    runCatching {
                        JarFile(outerJarPath).use { outerJar ->
                            outerJar.entries().asSequence()
                                .filter { entry ->
                                    val name = entry.name
                                    name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar")
                                }
                                .forEach { nestedEntry ->
                                    val tempJar = Files.createTempFile("nested-", ".jar").toFile().apply { deleteOnExit() }
                                    outerJar.getInputStream(nestedEntry).use { input ->
                                        FileOutputStream(tempJar).use { output -> input.copyTo(output) }
                                    }
                                    addDepJarIfAbsent(tempJar, depJars, seenDepJarPaths)
                                }
                        }
                    }
                }

                depJars.forEach { depJar ->
                    runCatching {
                        JarFile(depJar).use { jarFile ->
                            jarFile.entries().asSequence()
                                .filter { entry ->
                                    val name = entry.name
                                    when {
                                        entry.isDirectory -> false
                                        name == JarFile.MANIFEST_NAME -> false
                                        name.startsWith("META-INF/") -> false
                                        name == "org/slf4j/impl/StaticLoggerBinder.class" -> false
                                        else -> true
                                    }
                                }
                                .forEach { entry ->
                                    if (seenEntries.add(entry.name)) {
                                        jos.putNextEntry(JarEntry(entry.name).apply { time = entry.time })
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
            .map(::File)
            .filter { it.isFile && it.extension.equals("jar", true) }
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
            ?: LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
        val untilDate = args.getOrNull(3)?.takeIf { it.isNotBlank() }
            ?: LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
        val statusType = args.getOrNull(4)?.takeIf { it.isNotBlank() } ?: "ALL"
        val deployServerDir = args.getOrNull(6)?.takeIf { it.isNotBlank() } ?: "/home/bjw/deployProject/"
        val sinceVersion = args.getOrNull(7)?.takeIf { it.isNotBlank() } ?: ""
        val untilVersion = args.getOrNull(8)?.takeIf { it.isNotBlank() } ?: ""
        val selectedVersions = args.getOrNull(9)?.takeIf { it.isNotBlank() } ?: ""
        val selectedFiles = args.getOrNull(10)?.takeIf { it.isNotBlank() } ?: ""
        val duplicateFileVersionMap = args.getOrNull(11)?.takeIf { it.isNotBlank() } ?: ""

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
            "duplicateFileVersionMap" to duplicateFileVersionMap
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
