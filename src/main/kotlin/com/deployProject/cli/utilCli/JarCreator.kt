package com.deployProject.cli.utilCli

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
    /**
     * 주어진 디렉터리의 .class 파일과 현재 클래스패스 의존성 JAR을 포함하여 fat-JAR 생성
     */
    @Throws(IOException::class)
    fun createJar(sourceDirPath: String, jarFilePath: String, defaults: Map<String,String>) {
        val seenEntries = mutableSetOf<String>()
        val sourceDir: Path = Paths.get(sourceDirPath)
        FileOutputStream(jarFilePath).use { fos ->
            JarOutputStream(fos, createManifest()).use { jos ->

                // 클래스 파일 추가
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
                // 의존성 JAR 병합
                System.getProperty("java.class.path").split(File.pathSeparatorChar)
                    .map { File(it) }
                    .filter { it.exists() && it.extension == "jar" }
                    .forEach { depJar ->
                        JarFile(depJar).use { jarFile ->
                            jarFile.entries().asSequence()
                                .filter { e -> !e.isDirectory && e.name != JarFile.MANIFEST_NAME && !e.name.startsWith("META-INF/") }
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
        mainAttributes[Attributes.Name("Main-Class")] = "com.deployProject.util.ExtractionLauncher"
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
        val repoDir       = args.getOrNull(0)?.takeIf { it.isNotBlank() }
            ?: error("Usage: <repoDir> [relPath] [sinceDate] [untilDate] [fileStatusType] [jarFilePath]")
        val relPath       = args.getOrNull(1) ?: ""
        val sinceDate     = args.getOrNull(2)?.takeIf { it.isNotBlank() }
            ?: LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd")).toString()
        val untilDate     = args.getOrNull(3)?.takeIf { it.isNotBlank() }
            ?: LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd")).toString()
        val statusType    = args.getOrNull(4)?.takeIf { it.isNotBlank() } ?: "ALL"
        val jarFilePath   = "${randomFileName}/deploy-project-cli.jar"
        val deployServerDir = args.getOrNull(6)?.takeIf { it.isNotBlank() } ?: "/home/bjw/deployProject/"

        // 2) defaults 맵 구성
        val defaults = mapOf(
            "repoDir"     to repoDir,
            "relPath"     to relPath,
            "since"       to sinceDate,
            "until"       to untilDate,
            "statusType"  to statusType,
            "deployServerDir" to deployServerDir
        )

        // 3) JAR 생성
        createJar(
            sourceDirPath = "./build/classes/kotlin/main",
            jarFilePath   = jarFilePath,
            defaults      = defaults
        )

        println("✅ JAR 생성 완료: $jarFilePath")
    }





}