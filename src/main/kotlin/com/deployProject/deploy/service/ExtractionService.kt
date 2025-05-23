package com.deployProject.deploy.service

import com.badlogicgames.packr.Packr
import com.badlogicgames.packr.PackrConfig
import com.deployProject.deploy.domain.extraction.ExtractionDto
import com.deployProject.deploy.domain.extraction.TargetOsStatus
import com.deployProject.util.JarCreator
import org.springframework.stereotype.Service
import java.io.File
import java.util.*
import javax.swing.JOptionPane
import javax.swing.JPasswordField

@Service
class ExtractionService(
    private val jarCreator: JarCreator = JarCreator
) {

    fun extractGitInfo(extractionDto: ExtractionDto): File {
        // 1) (옵션) Swing 팝업으로 SVN 자격증명 입력받기
        val randomFileName = "GitInfoJarFile/${UUID.randomUUID()}"
        val deployJarName = "deploy-project-cli.jar"

        try {
            jarCreator.main(
                arrayOf(
                    extractionDto.localPath,
                    "",
                    extractionDto.since,
                    extractionDto.until,
                    extractionDto.fileStatusType,
                    randomFileName,
                    extractionDto.homePath
                ) as Array<String>
            )
        } catch (e: Exception) {
            println("[ERROR] Error during jar creation: ${e.message}")
            e.printStackTrace()
            throw e
        }

        val jarFile = File(randomFileName, deployJarName)

        val exeFile = extractionDto.targetOs?.let { packWithPackr(jarFile, it) }
        return exeFile as File

    }

    private fun packWithPackr(jarFile: File, targetOs: TargetOsStatus): File {
        val outputDir = File(jarFile.parentFile, "exe-output")
        val config = PackrConfig().apply {
            platform =
                if (targetOs == TargetOsStatus.WINDOWS) PackrConfig.Platform.Windows64 else PackrConfig.Platform.MacOS

            jdk = "C:/Program Files/Java/jdk-17"
//            jdk = "/Users/mac/.sdkman/candidates/java/current/bin/java"
            executable = "deploy-project-cli"
            classpath = listOf(jarFile.absolutePath)
            mainClass = "com.deployProject.util.ExtractionLauncher"
            vmArgs = listOf("-Xmx512m")
            outDir = outputDir
            useZgcIfSupportedOs = true
        }

        println("[DEBUG] PackrConfig: $config")
        try {
            Packr().pack(config)
        } catch (e: Exception) {
            println("[ERROR] Error during Packr packaging: ${e.message}")
            e.printStackTrace()
            throw e
        }

        return File(outputDir, "deploy-project-cli.exe")
    }
}