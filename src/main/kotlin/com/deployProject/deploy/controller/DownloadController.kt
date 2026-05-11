package com.deployProject.deploy.controller

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.PathResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

@RestController
class DownloadController(
    @Value("\${deploy.download.installer-path:./DeployProject.exe}")
    private val installerPath: String,
    @Value("\${deploy.download.installer-file-name:DeployProject.exe}")
    private val installerFileName: String
) {

    @GetMapping("/download/deploy-project.exe")
    fun downloadInstaller(): ResponseEntity<PathResource> {
        val path = Paths.get(installerPath).toAbsolutePath().normalize()
        if (!Files.isRegularFile(path)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Installer file not found: $path")
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
