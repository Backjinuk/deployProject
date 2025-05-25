package com.deployProject.deploy.service

import com.deployProject.deploy.domain.extraction.ExtractionDto
import com.deployProject.util.JarCreator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Paths
import java.util.*

@Service
class ExtractionService(
    private val jarCreator: JarCreator = JarCreator
) {

    private val logger = LoggerFactory.getLogger(ExtractionService::class.java)

    fun extractGitInfo(extractionDto: ExtractionDto): File {

        // 1) Fat JAR 생성
        val randomDir = File("GitInfoJarFile", UUID.randomUUID().toString()).apply { mkdirs() }
        val deployJar = File(randomDir, "deploy-project-cli.jar")
        try {
            jarCreator.main(arrayOf(
                extractionDto.localPath,
                "",
                extractionDto.since,
                extractionDto.until,
                extractionDto.fileStatusType,
                randomDir.path,
                extractionDto.homePath
            ) as Array<String>)
        } catch (e: Exception) {
            logger.error("Error during jar creation", e)
            throw e
        }

        logger.info("Fat JAR created at: ${deployJar.absolutePath}")
        // 2) jlink로 커스텀 런타임 이미지 생성
        val runtimeImage = packWithJLink(deployJar)


        // 3) jpackage로 앱 이미지 생성
        return packWithJPackage(deployJar, runtimeImage)
    }

    private fun packWithJLink(jarFile: File): File {
        logger.debug("Packing with jlink: jarFile=${jarFile.absolutePath}")
        val javaHome = System.getProperty("java.home")
        val jdepsExe = Paths.get(javaHome, "bin", "jdeps").toString()

        // jdeps --print-module-deps 로 모듈 목록만 추출
        val rawOutput = runCapture(
            jdepsExe,
            "--print-module-deps",
            jarFile.absolutePath
        )

        val modules = rawOutput
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?: throw RuntimeException("jdeps did not output any modules")

        logger.debug("Detected modules: $modules")

        // 기존 runtime-image 디렉터리 삭제 및 재생성
        val outputDir = File(jarFile.parentFile, "runtime-image")
        if (outputDir.exists()) {
            outputDir.deleteRecursively()
        }
        outputDir.mkdirs()

        // jlink 실행 (최대 2분 타임아웃)
        val jlinkExe = Paths.get(javaHome, "bin", "jlink").toString()
        val jlinkCmd = listOf(
            jlinkExe,
            "--add-modules", modules,
            "--output", outputDir.absolutePath,
            "--launcher", "deploy=${jarFile.name}",
            "--no-header-files",
            "--no-man-pages",
            "--compress=0"
        )
        logger.debug("Running jlink with timeout: ${jlinkCmd.joinToString(" ")}")
        val proc = ProcessBuilder(*jlinkCmd.toTypedArray()).inheritIO().start()
        // 타임아웃 설정: 120초
        if (!proc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw RuntimeException("jlink timed out after 120 seconds")
        }
        if (proc.exitValue() != 0) {
            throw RuntimeException("jlink failed with exit code ${proc.exitValue()}")
        }

        return outputDir
    }

    private fun packWithJPackage(jarFile: File, runtimeImage: File): File {
        logger.debug("Packing with jpackage: jarFile=${jarFile.absolutePath}, runtimeImage=${runtimeImage.absolutePath}")


        val outputDir = File(jarFile.parentFile, "package").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val javaHome = System.getProperty("java.home")
        val jpackageExe = Paths.get(javaHome, "bin", "jpackage").toString()
        val jpackageCmd = listOf(
            jpackageExe,
            "--name", "DeployProjectCLI",
            "--app-version", "1.0.0",
            "--runtime-image", runtimeImage.absolutePath,
            "--input", jarFile.parentFile.absolutePath,
            "--main-jar", jarFile.name,
            "--main-class", "com.deployProject.util.ExtractionLauncher",
            "--type", "app-image",
            "--dest", outputDir.absolutePath
        )
        logger.debug("Running jpackage with timeout: ${jpackageCmd.joinToString(" ")}")
        val proc = ProcessBuilder(*jpackageCmd.toTypedArray())
            .inheritIO()
            .start()
        // 2분 타임아웃 설정
        if (!proc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw RuntimeException("jpackage timed out after 120 seconds")
        }
        if (proc.exitValue() != 0) {
            throw RuntimeException("jpackage failed with exit code ${proc.exitValue()}")
        }
        return outputDir.resolve("DeployProjectCLI.app")
    }

    private fun runCapture(vararg cmd: String): String {
        logger.debug("Executing command: ${cmd.joinToString(" ")}")
        val proc = ProcessBuilder(*cmd)
            .redirectErrorStream(true)
            .start()
        val exitCode = proc.waitFor()
        val output = proc.inputStream.bufferedReader().readText()
        if (exitCode != 0) {
            logger.error("Command failed (exit $exitCode): ${cmd.joinToString(" ")}\n$output")
            throw RuntimeException("Command failed: ${cmd.joinToString(" ")} (exit $exitCode)\n$output")
        }
        logger.debug("Command output: $output")
        return output
    }

    private fun runExec(vararg cmd: String) {
        val proc = ProcessBuilder(*cmd)
            .inheritIO()
            .start()
        if (proc.waitFor() != 0) {
            throw RuntimeException("Command failed: ${cmd.joinToString(" ")} (exit ${proc.exitValue()})")
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
