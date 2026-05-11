package com.deployProject.deploy.controller

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.PathResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

@RestController
class DownloadController(
    @Value("\${deploy.download.installer-path:./download/DeployProject.exe}")
    private val installerPath: String,
    @Value("\${deploy.download.installer-file-name:DeployProject.exe}")
    private val installerFileName: String
) {

    @GetMapping("/download/deploy-project.exe")
    fun downloadInstaller(): ResponseEntity<Any> {
        val path = Paths.get(installerPath).toAbsolutePath().normalize()
        if (!Files.isRegularFile(path)) {
            return ResponseEntity.notFound()
                .header("X-DeployProject-Error", "Installer file not found")
                .build()
        }

        val encodedFileName = URLEncoder.encode(installerFileName, StandardCharsets.UTF_8)
            .replace("+", "%20")

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(Files.size(path))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"$installerFileName\"; filename*=UTF-8''$encodedFileName"
            )
            .body(PathResource(path))
    }
}
