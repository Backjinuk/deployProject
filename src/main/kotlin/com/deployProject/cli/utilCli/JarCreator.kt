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

/**
 * 동적으로 JAR 파일을 생성 및 실행할 수 있는 GUI 유틸리티
 */
object JarCreator {

    private val os = System.getProperty("os.name").lowercase()

    /**
     * 주어진 디렉터리의 .class 파일과 현재 의존성 JAR들을 전부 병합하여 fat-JAR 생성
     *
     * @param sourceDirPath   컴파일된 .class들이 있는 디렉터리 (예: "build/classes/kotlin/main")
     * @param jarFilePath     만들어질 fat-JAR 경로 (예: "./output/deploy-project-cli.jar")
     * @param defaults        defaults.properties로 쓸 키-값 맵
     */
    @Throws(IOException::class)
    fun createJar(sourceDirPath: String, jarFilePath: String, defaults: Map<String, String>) {
        val seenEntries = mutableSetOf<String>()
        var sourceDir: Path = Paths.get(sourceDirPath)

        // (1) sourceDirPath 보정 (Linux 등에서 프로젝트 루트 기준으로 찾기)
        if (!os.contains("windows") && !os.contains("mac")) {
            val wd = Paths.get("").toAbsolutePath()
            val projectRoot = wd.parent?.parent
                ?: throw IllegalStateException("작업 디렉터리 기준으로 두 단계 상위가 존재하지 않습니다.")
            sourceDir = projectRoot.resolve(sourceDirPath)
        }

        FileOutputStream(jarFilePath).use { fos ->
            JarOutputStream(fos, createManifest()).use { jos ->
                // ─────────────────────────────────────────────────────
                // (2)  .class 파일 → fat-JAR에 추가
                if (Files.exists(sourceDir)) {
                    Files.walk(sourceDir)
                        .filter { Files.isRegularFile(it) }
                        .forEach { path ->
                            val entryName = sourceDir.relativize(path).toString().replace('\\', '/')
                            if (seenEntries.add(entryName)) {
                                jos.putNextEntry(JarEntry(entryName).apply { time = path.toFile().lastModified() })
                                Files.copy(path, jos)
                                jos.closeEntry()
                            }
                        }
                } else {
                    // “빌드 결과물이 없어서 sourceDir이 없을 때” → 현재 코드(예: Spring Boot JAR 내부)를 뒤져서 .class만 추가
                    val codeSourceUri: URI = this::class.java.protectionDomain.codeSource.location.toURI()
                    val scheme = codeSourceUri.scheme

                    val jarFsUri: URI = when {
                        // file:/.../something.jar 형태
                        scheme == "file" && codeSourceUri.path.endsWith(".jar") -> {
                            URI.create("jar:$codeSourceUri")
                        }
                        // 이미 jar: 스킴인 경우 (예: nested: 같은)
                        scheme == "jar" -> codeSourceUri
                        else -> throw IOException(
                            "클래스 파일(.class) 디렉터리를 찾을 수 없습니다. " +
                                    "URI 스킴: $scheme, path: ${codeSourceUri.path}"
                        )
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
                                        Files.newInputStream(pathInJar).use { input ->
                                            input.copyTo(jos)
                                        }
                                        jos.closeEntry()
                                    }
                                }
                        }
                    }

                    jos.setLevel(java.util.zip.Deflater.BEST_SPEED)
               }

                // ─────────────────────────────────────────────────────
                // (3) 현재 환경에 맞춰 “병합 대상 JAR 목록”을 구함

                // (3-1) Gradle 캐시(~/.gradle/caches/modules-2/files-2.1) 아래 모든 .jar 를 jarsInClassPath에 담기
                val jarsInClassPath = mutableListOf<File>()
                val userHome = System.getProperty("user.home")
                val gradleCacheRoot = Paths.get(userHome, ".gradle", "caches", "modules-2", "files-2.1")

/*
                if (Files.exists(gradleCacheRoot)) {
                    Files.walk(gradleCacheRoot).use { stream ->
                        stream
                            .filter { Files.isRegularFile(it) && it.toString().endsWith(".jar") }
                            .forEach { path ->
                                val file = path.toFile()
                                jarsInClassPath.add(file)
                            }
                    }
                }
*/

                jarsInClassPath += jarsFromJavaClassPath()

                // (3-2) “운영 모드” (Spring Boot fat-JAR 내부 실행) 체크 → outerJarPath 추출
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
                    try {
                        JarFile(outerJarPath).use { outerJar ->
                            outerJar.entries().asSequence()
                                .filter { entry ->
                                    val name = entry.name
                                    name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar")
                                }
                                .forEach { nestedEntry ->
                                    val nestedName = nestedEntry.name

                                    val tempJar = Files.createTempFile("nested-", ".jar").toFile().apply {
                                        deleteOnExit()
                                    }

                                    outerJar.getInputStream(nestedEntry).use { input ->
                                        FileOutputStream(tempJar).use { output ->
                                            input.copyTo(output)
                                        }
                                    }

                                    if (!jarsInClassPath.any { it.absolutePath == tempJar.absolutePath }) {
                                        jarsInClassPath.add(tempJar)
                                    }
                                }
                        }
                    } catch (e: Exception) {
                    }
                }

                // (4) jarsInClassPath 에 담긴 모든 JAR을 순회하면서 실제 병합
                jarsInClassPath.forEach { depJar ->
                    try {
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
                                        jarFile.getInputStream(entry).use { it.copyTo(jos) }
                                        jos.closeEntry()
                                    }
                                }
                        }
                    } catch (e: Exception) {
                    }
                }

                // ─────────────────────────────────────────────────────
                // (5) defaults.properties 파일 추가 (기존과 동일)
                val props = Properties().apply {
                    defaults.forEach { (k, v) -> setProperty(k, v) }
                }
                jos.putNextEntry(JarEntry("defaults.properties"))
                props.store(jos, "GitInfoCli defaults")
                jos.closeEntry()
            }
        }
    }

    /**
     * 기본 매니페스트 생성
     */
    private fun createManifest(): Manifest = Manifest().apply {
        mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        mainAttributes[Attributes.Name("Main-Class")] = "com.deployProject.cli.ExtractionLauncher"
        // ※ Fat-JAR 안에 kotlin-stdlib 등을 이미 병합했으므로 Class-Path 설정은 불필요
    }

    // JarCreator 안에 유틸 추가
    private fun jarsFromJavaClassPath(): List<File> {
        val cp = System.getProperty("java.class.path") ?: return emptyList()
        return cp.split(File.pathSeparatorChar)
            .map(::File)
            .filter { it.isFile && it.extension.equals("jar", true) }
    }

    /**
     * CLI 진입점: 화면이나 테스트에서 받은 값으로 JAR을 생성하고 defaults.properties에 바인딩
     *
     * @param args
     *  0: gitDir (필수)
     *  1: relPath (옵션)
     *  2: sinceDate (옵션, yyyy/MM/dd)
     *  3: untilDate (옵션, yyyy/MM/dd)
     *  4: fileStatusType (옵션)
     *  5: jarOutputDir (필수, 출력 JAR을 담을 디렉터리)
     *  6: deployServerDir (옵션)
     */
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 6) {
            error("Usage: <repoDir> [relPath] [sinceDate] [untilDate] [fileStatusType] <jarOutputDir> [deployServerDir]")
        }

        // 1) jarOutputDir 디렉터리 생성
        val jarOutputDir = args[5]
        val outputPath = Paths.get(jarOutputDir)
        Files.createDirectories(outputPath)

        // 2) 파라미터 파싱
        val repoDir = args.getOrNull(0)?.takeIf { it.isNotBlank() }
            ?: error("Usage: <repoDir> [relPath] [sinceDate] [untilDate] [fileStatusType] <jarOutputDir> [deployServerDir]")
        val relPath = args.getOrNull(1) ?: ""
        val sinceDate = args.getOrNull(2)?.takeIf { it.isNotBlank() }
            ?: LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
        val untilDate = args.getOrNull(3)?.takeIf { it.isNotBlank() }
            ?: LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
        val statusType = args.getOrNull(4)?.takeIf { it.isNotBlank() } ?: "ALL"
        val deployServerDir = args.getOrNull(6)?.takeIf { it.isNotBlank() } ?: "/home/bjw/deployProject/"

        // 3) defaults 맵 구성
        val defaults = mapOf(
            "repoDir" to repoDir,
            "relPath" to relPath,
            "since" to sinceDate,
            "until" to untilDate,
            "statusType" to statusType,
            "deployServerDir" to deployServerDir
        )

        // 4) sourceDirPath 결정
        val sourceDirPath = if (os.contains("windows") || os.contains("mac")) {
            "./build/classes/kotlin/main"
        } else {
            "build/classes/kotlin/main"
        }

        // 5) 최종 생성될 JAR 경로: <jarOutputDir>/deploy-project-cli.jar
        val jarFilePath = "$jarOutputDir/deploy-project-cli.jar"

        // 6) JAR 생성
        createJar(sourceDirPath, jarFilePath, defaults)

    }
}
