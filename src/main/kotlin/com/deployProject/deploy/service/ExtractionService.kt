package com.deployProject.deploy.service

import com.badlogicgames.packr.Packr
import com.badlogicgames.packr.PackrConfig
import com.deployProject.deploy.domain.extraction.ExtractionDto
import com.deployProject.util.JarCreator
import org.springframework.stereotype.Service
import java.io.File
import java.util.*

@Service
class ExtractionService(
    private val jarCreator: JarCreator = JarCreator
) {

    fun extractGitInfo(extractionDto: ExtractionDto): File {
        println("[DEBUG] Starting extractGitInfo with DTO: $extractionDto")
        val randomFileName = "GitInfoJarFile/${UUID.randomUUID()}"
        val deployJarName = "deploy-project-cli.jar"

        try {
            println("[DEBUG] Invoking JarCreator to build JAR: outputDir=$randomFileName, jarName=$deployJarName")
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
        println("[DEBUG] JAR created at ${jarFile.absolutePath}")

        val exeFile = packWithPackr(jarFile)
        println("[DEBUG] EXE created at ${exeFile.absolutePath}")
        return exeFile
    }

    private fun packWithPackr(jarFile: File): File {
        println("[DEBUG] Configuring Packr packaging for ${jarFile.absolutePath}")
        val outputDir = File(jarFile.parentFile, "exe-output")
        val config = PackrConfig().apply {
            platform = PackrConfig.Platform.Windows64
            jdk = "/Users/mac/.sdkman/candidates/java/21.0.2-open"
            executable = "deploy-project-cli"
            classpath = listOf(jarFile.absolutePath)
            mainClass = "com.deployProject.DeployProjectApplicationKt"
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

        return File(outputDir , "deploy-project-cli.exe")
    }
}