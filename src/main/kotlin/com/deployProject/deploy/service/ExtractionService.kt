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

        val randomFileName = "GitInfoJarFile/" + UUID.randomUUID().toString()
        val deployFileName = "deploy-project-cli.jar"

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

        return File(randomFileName, deployFileName)


    }


    fun packWithPackr(jarDir: File): File {


        // 2) Packr 설정
        val config = PackrConfig().apply {
            platform = PackrConfig.Platform.Windows64
            // 개발 PC
            jdk = "C:/Program Files/Java/jdk-17"
            executable      = "deploy-project-cli"           // exe 이름
//            classpath       =

        }
        // 3) 패키징 수행
        Packr().pack(config)
        return File(jarDir, "deploy-project-cli.exe")


    }
}