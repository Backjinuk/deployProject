package com.deployProject.deploy.service

import com.badlogicgames.packr.Packr
import com.badlogicgames.packr.PackrConfig
import com.deployProject.deploy.domain.extraction.ExtractionDto
import com.deployProject.deploy.domain.extraction.TargetOsStatus
import com.deployProject.cli.utilCli.JarCreator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Paths
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Service
class ExtractionService(
    private val jarCreator: JarCreator = JarCreator
) {
    private val os =  System.getProperty("os.name").lowercase()
    private val logger = LoggerFactory.getLogger(ExtractionService::class.java)

    fun extractGitInfo(extractionDto: ExtractionDto): File {
        // 1) baseDir/exe-output만 쓰도록 변경

        println("System.getProperty(\"java.class.path\") = ${System.getProperty("java.class.path")}")

        var baseDir: File = File("GitInfoJarFile", UUID.randomUUID().toString())

        if (!os.contains("windows") && !os.contains("mac")) {
            // Linux인 경우, 사용자 홈 디렉터리 하위에 생성
            val wd = Paths.get("").toAbsolutePath()
            val projectRoot = wd.parent?.parent
                ?: throw IllegalStateException("작업 디렉터리 기준으로 두 단계 상위가 존재하지 않습니다.")

            baseDir   = File(projectRoot.toAbsolutePath().toString() + "/GitInfoJarFile", UUID.randomUUID().toString())
        }

        val  deployJarName = "deploy-project-cli.jar"
        val  jarFile = File(baseDir, deployJarName)

        logger.info("▶︎ FAT-JAR 생성 시작")

        // 2) fat-JAR 생성 → outputDir에 바로 쓰기
        try {
            jarCreator.main(arrayOf(
                extractionDto.localPath,
                "",  // relPath
                extractionDto.since,
                extractionDto.until,
                extractionDto.fileStatusType,
                baseDir.absolutePath,  // **여기가 바뀌었습니다**
                extractionDto.homePath
            ) as Array<String>)
        } catch (e: Exception) {
            logger.error("Error during jar creation", e)
            throw e
        }

        logger.info("▶︎ FAT-JAR 생성 종료")

        // 3) 이제 EXE용 outputDir만 따로 만들어서
        val outputDir = File(baseDir, "exe-output")
        if (outputDir.exists()) outputDir.deleteRecursively()
        outputDir.mkdirs()



        logger.info("✅ PACKR 생성 시작")
        // 3) Packr로 EXE 생성 → 같은 outputDir에
        packWithPackr(jarFile, extractionDto.targetOs!!, outputDir)

        // exe-output 폴더 전체를 ZIP으로 묶어서 반환
        val zipFile = File(outputDir.parentFile, "exe-output.zip")
        zipDirectory(outputDir, zipFile)

        logger.info("⚡️PACKR 생성 종료")

        return zipFile
    }

    // packWithPackr에 outputDir 파라미터를 추가합니다.
    private fun packWithPackr(
        jarFile: File,
        targetOs: TargetOsStatus,
        outputDir: File
    ): File {

        val config = PackrConfig().apply {
            platform    = if (targetOs == TargetOsStatus.WINDOWS)
                PackrConfig.Platform.Windows64
            else
                PackrConfig.Platform.MacOS

            jdk =  if ( os.contains("windows"))
                "C:/Program Files/Java/jdk-17"
            else if( os.contains("mac"))
                "/Users/mac/.sdkman/candidates/java/current"
            else
                "/home/bjw/.sdkman/candidates/java/current"

            executable  = "deploy-project-cli"
            classpath   = listOf(jarFile.absolutePath)
            mainClass   = "com.deployProject.cli.ExtractionLauncher"
            vmArgs      = listOf("-Xmx512m")
            outDir      = outputDir
            useZgcIfSupportedOs = true
        }
        logger.info("PackrConfig: $config")
        Packr().pack(config)

        val exeName = if (targetOs == TargetOsStatus.WINDOWS)
            "deploy-project-cli.exe"
        else
            "deploy-project-cli"
        return File(outputDir, exeName)
    }

    private fun zipDirectory(sourceDir: File, targetZip: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(targetZip))).use { zos ->
            sourceDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val entryName = sourceDir.toPath().relativize(file.toPath()).toString().replace('\\','/')
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
        }
    }

}


//private fun packWithPackr(jarFile: File, targetOs: TargetOsStatus): File {
//    val outputDir = File(jarFile.parentFile, "exe-output")
//    val config = PackrConfig().apply {
//        platform =
//            if (targetOs == TargetOsStatus.WINDOWS) PackrConfig.Platform.Windows64 else PackrConfig.Platform.MacOS
//
////            jdk = "C:/Program Files/Java/jdk-17"
//        jdk = "/Users/mac/.sdkman/candidates/java/current/bin/java"
//        executable = "deploy-project-cli"
//        classpath = listOf(jarFile.absolutePath)
//        mainClass = "com.deployProject.util.ExtractionLauncher"
//        vmArgs = listOf("-Xmx512m")
//        outDir = outputDir
//        useZgcIfSupportedOs = true
//    }
//
//    logger.info("PackrConfig: $config")
//    try {
//        Packr().pack(config)
//    } catch (e: Exception) {
//        println("[ERROR] Error during Packr packaging: ${e.message}")
//        e.printStackTrace()
//        throw e
//    }
//
//    return File(outputDir, "deploy-project-cli.exe")
//}


//private fun packWithJLink(jarFile: File): File {
//    val javaHome = System.getProperty("java.home")
//
//    // 1) jdeps 대신 고정 모듈 리스트 사용
//    val modules = listOf(
//        "java.base",
//        "java.logging",
//        "java.sql",
//        "java.desktop",
//        "java.naming"
//    ).joinToString(",")
//    logger.info("Using fixed modules for jlink: $modules")
//
//    // 2) 기존 runtime-image 디렉터리 삭제 및 재생성
//    val outputDir = File(jarFile.parentFile, "runtime-image")
//    if (outputDir.exists()) outputDir.deleteRecursively()
////        outputDir.mkdirs()
//
//    // 3) jlink 실행 (최대 2분 타임아웃)
//    val jlinkExe = Paths.get(javaHome, "bin", "jlink").toString()
//    val cmd = listOf(
//        jlinkExe,
//        "--add-modules", modules,
//        "--output", outputDir.absolutePath,
//        "--launcher", "deploy=${jarFile.name}",
//        "--no-header-files",
//        "--no-man-pages",
//        "--compress=0"
//    )
//    logger.info("Running jlink: ${cmd.joinToString(" ")}")
//    val proc = ProcessBuilder(*cmd.toTypedArray())
//        .inheritIO()
//        .start()
//    if (!proc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
//        proc.destroyForcibly()
//        throw RuntimeException("jlink timed out after 120 seconds")
//    }
//    if (proc.exitValue() != 0) {
//        throw RuntimeException("jlink failed with exit code ${proc.exitValue()}")
//    }
//
//    return outputDir
//}
//
//
//private fun packWithJPackage(jarFile: File): File {
//    val outputDir = File(jarFile.parentFile, "package").apply {
//        if (exists()) deleteRecursively()
//        mkdirs()
//    }
//    val javaHome    = System.getProperty("java.home")
//    val jpackageExe = Paths.get(javaHome, "bin", "jpackage").toString()
//
//    val jpackageCmd = listOf(
//        jpackageExe,
//        "--name",        "DeployProjectCLI",
//        "--app-version", "1.0.0",
//        "--input",       jarFile.parentFile.absolutePath,
//        "--main-jar",    jarFile.name,
//        "--main-class",  "com.deployProject.util.ExtractionLauncher",
//        "--type",        "app-image",
//        "--dest",        outputDir.absolutePath
//    )
//    logger.info("Running jpackage: ${jpackageCmd.joinToString(" ")}")
//    val proc = ProcessBuilder(*jpackageCmd.toTypedArray())
//        .inheritIO()
//        .start()
//
//    if (!proc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
//        proc.destroyForcibly()
//        throw RuntimeException("jpackage timed out after 120 seconds")
//    }
//    if (proc.exitValue() != 0) {
//        throw RuntimeException("jpackage failed with exit code ${proc.exitValue()}")
//    }
//
//    // .app 이미지 디렉터리 반환
//    return outputDir.resolve("DeployProjectCLI.app")
//}
//
//private fun runCapture(vararg cmd: String): String {
//    logger.info("Executing command: ${cmd.joinToString(" ")}")
//    val proc = ProcessBuilder(*cmd)
//        .redirectErrorStream(true)
//        .start()
//    val exitCode = proc.waitFor()
//    val output = proc.inputStream.bufferedReader().readText()
//    if (exitCode != 0) {
//        logger.error("Command failed (exit $exitCode): ${cmd.joinToString(" ")}\n$output")
//        throw RuntimeException("Command failed: ${cmd.joinToString(" ")} (exit $exitCode)\n$output")
//    }
//    logger.debug("Command output: $output")
//    return output
//}
//
//private fun runExec(vararg cmd: String) {
//    val proc = ProcessBuilder(*cmd)
//        .inheritIO()
//        .start()
//    if (proc.waitFor() != 0) {
//        throw RuntimeException("Command failed: ${cmd.joinToString(" ")} (exit ${proc.exitValue()})")
//    }
//}
