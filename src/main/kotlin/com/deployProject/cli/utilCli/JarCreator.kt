package com.deployProject.cli.utilCli

import com.deployProject.cli.utilCli.JarCreator.createJar
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

    private val os =  System.getProperty("os.name").lowercase()

    /**
     * 주어진 디렉터리의 .class 파일과 현재 클래스패스 의존성 JAR을 포함하여 fat-JAR 생성
     */
    @Throws(IOException::class)
    fun createJar(sourceDirPath: String, jarFilePath: String, defaults: Map<String,String>) {
        val seenEntries = mutableSetOf<String>()
        var sourceDir: Path = Paths.get(sourceDirPath)

        if (!os.contains("windows") && !os.contains("mac")) {
            // Linux인 경우, 사용자 홈 디렉터리 하위에 생성
            val wd = Paths.get("").toAbsolutePath()
            val projectRoot = wd.parent?.parent
                ?: throw IllegalStateException("작업 디렉터리 기준으로 두 단계 상위가 존재하지 않습니다.")
            sourceDir = projectRoot.resolve(sourceDirPath)
        }
//        if (Files.exists(srcPath)) {


        FileOutputStream(jarFilePath).use { fos ->
            JarOutputStream(fos, createManifest()).use { jos ->
                // 클래스 파일 추가
                if(Files.exists(sourceDir)) {
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

                    // 2) “배포 환경”이라 build/classes/kotlin/main 이 없으므로, JAR 내부를 탐색
                    val codeSourceUri: URI =
                        this::class.java.protectionDomain.codeSource.location.toURI()
                    val scheme = codeSourceUri.scheme

                    // JAR 내부를 열기 위해 사용할 URI 결정
                    val jarFsUri: URI = when {
                        // 2-1. codeSourceUri 가 “file:/.../myapp.jar” 형태인 경우
                        scheme == "file" && codeSourceUri.path.endsWith(".jar") -> {
                            URI.create("jar:${codeSourceUri}")
                        }
                        // 2-2. Spring Boot “nested JAR” 형태라 이미 스킴이 jar 로 나온 경우
                        scheme == "jar" -> {
                            codeSourceUri
                        }

                        else -> {
                            throw IOException(
                                "클래스 파일(.class) 디렉터리를 찾을 수 없습니다. " +
                                        "URI 스킴: $scheme, path: ${codeSourceUri.path}"
                            )
                        }
                    }

                    // 3) jarFsUri 로 FileSystem을 열어 JAR 내부 .class 파일 순회
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
                }



                // 의존성 JAR 병합
                System.getProperty("java.class.path").split(File.pathSeparatorChar)
                    .map { File(it) }
                    .filter { it.exists() && it.extension == "jar" }
                    .forEach { depJar ->
                        JarFile(depJar).use { jarFile ->
                            jarFile.entries().asSequence()
                                .filter { e ->
                                    if (e.isDirectory || e.name == JarFile.MANIFEST_NAME || e.name.startsWith("META-INF/")) {
                                        false
                                    }    // 2) SLF4J 구버전 바인딩(StaticLoggerBinder) 무조건 제외
                                    else if (e.name == "org/slf4j/impl/StaticLoggerBinder.class") {
                                        false
                                    } else {
                                        true
                                    }
                                }
                                .forEach { e ->
                                    if (seenEntries.add(e.name)) {
                                        jos.putNextEntry(JarEntry(e.name).apply { time = e.time })
                                        jarFile.getInputStream(e).use { it.copyTo(jos) }
                                        jos.closeEntry()
                                    }
                                }
                        }
                    }

                // GitInfoCli 호출시 매개변수를 properties에 바인딩
                val props = Properties().apply {
                    defaults.forEach { (k, v) -> setProperty(k,v) }

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
    }


    /**
     * 기존 main은 args 파싱 후 run() 호출
     */
    /**
     * CLI 진입점: 화면이나 테스트에서 받은 값으로 JAR을 생성하고 defaults.properties에 바인딩
     * 0: gitDir (필수)
     * 1: relPath (옵션)
     * 2: sinceDate (옵션, yyyy/MM/dd)
     * 3: untilDate (옵션, yyyy/MM/dd)
     * 4: fileStatusType (옵션)
     * 5: jarFilePath (옵션, 출력 JAR 경로)
     */
    @JvmStatic
    fun main(args: Array<String>) {
        /// 파일 생성
        val randomFileName = args[5]
        val path = Paths.get(randomFileName)
        Files.createDirectories(path)

        // 1) 필수 및 옵션 파라미터 파싱
        val repoDir = args.getOrNull(0)?.takeIf { it.isNotBlank() }
            ?: error("Usage: <repoDir> [relPath] [sinceDate] [untilDate] [fileStatusType] [jarFilePath]")
        val relPath = args.getOrNull(1) ?: ""
        val sinceDate = args.getOrNull(2)?.takeIf { it.isNotBlank() }
            ?: LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd")).toString()
        val untilDate = args.getOrNull(3)?.takeIf { it.isNotBlank() }
            ?: LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd")).toString()
        val statusType = args.getOrNull(4)?.takeIf { it.isNotBlank() } ?: "ALL"
        val jarFilePath = "${randomFileName}/deploy-project-cli.jar"
        val deployServerDir = args.getOrNull(6)?.takeIf { it.isNotBlank() } ?: "/home/bjw/deployProject/"

        // 2) defaults 맵 구성
        val defaults = mapOf(
            "repoDir" to repoDir,
            "relPath" to relPath,
            "since" to sinceDate,
            "until" to untilDate,
            "statusType" to statusType,
            "deployServerDir" to deployServerDir
        )


        val sourceDirPath = if (os.contains("windows") || os.contains("mac"))
            "./build/classes/kotlin/main"
        else
           "build/classes/kotlin/main"

        // 3) JAR 생성
        createJar(
            sourceDirPath = sourceDirPath,
            jarFilePath   = jarFilePath,
            defaults      = defaults
        )

        println("✅ JAR 생성 완료: $jarFilePath")
    }





}