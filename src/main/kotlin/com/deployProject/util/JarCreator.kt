package com.deployProject.util

import com.deployProject.deploy.domain.site.FileStatusType
import com.deployProject.util.GitInfoCli.log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.swing.*
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.time.LocalDate

/**
 * 동적으로 JAR 파일을 생성 및 실행할 수 있는 GUI 유틸리티
 */
object JarCreator {
    /**
     * 주어진 디렉터리의 .class 파일과 현재 클래스패스 의존성 JAR을 포함하여 fat-JAR 생성
     */
    @Throws(IOException::class)
    fun createJar(sourceDirPath: String, jarFilePath: String) {
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
            }
        }
    }

    /**
     * 기본 매니페스트 생성
     */
    private fun createManifest(): Manifest = Manifest().apply {
        mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        mainAttributes[Attributes.Name("Main-Class")] = "com.deployProject.util.GitInfoCli"
    }

    /**
     * GUI 진입점: 입력 필드와 버튼으로 JAR 생성 및 실행
     */
    @JvmStatic
    fun main(args: Array<String>) {
        // 테스트용 기본 인자 설정
        val cliArgs = if (args.isEmpty()) {

            val repoPath = args.getOrNull(0) ?: error("Usage: <gitDir> [sinceDate] [untilDate]")


            val since = args.getOrNull(2)
                ?.takeIf { it.isBlank() }
                ?.let {
                    try {
                        LocalDate.parse(it)
                    } catch (e: Exception) {
                        LocalDate.now()
                    }
                }
                ?: LocalDate.now()

            val until = args.getOrNull(3)
                ?.takeIf { it.isBlank() }
                ?.let {
                    try {
                        LocalDate.parse(it)
                    } catch (e: Exception) {
                        LocalDate.now()
                    }
                }
                ?: LocalDate.now()

            val fileStatusType = args.getOrNull(4  ) ?: "ALL"

            // 하드코딩된 테스트 파라미터
            arrayOf(repoPath, "", since, until, fileStatusType)
        } else {
            args
        }
        // GitInfoCli 호출하여 ZIP 등 기능 실행
        GitInfoCli.main(cliArgs)
    }
}
