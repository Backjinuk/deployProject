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
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name

@RestController
class DownloadController(
    @Value("\${deploy.download.installer-path:}")
    private val installerPath: String,
    @Value("\${deploy.download.installer-file-name:}")
    private val installerFileName: String
) {

    @GetMapping("/download/deploy-project.exe")
    fun downloadInstaller(): ResponseEntity<Any> {
        val path = resolveInstallerPath()
        if (path == null) {
            return ResponseEntity.notFound()
                .header("X-DeployProject-Error", "Installer file not found")
                .build()
        }

        val downloadFileName = installerFileName.trim().ifBlank { path.fileName.toString() }
        val encodedFileName = URLEncoder.encode(downloadFileName, StandardCharsets.UTF_8)
            .replace("+", "%20")

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(Files.size(path))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"$downloadFileName\"; filename*=UTF-8''$encodedFileName"
            )
            .body(PathResource(path))
    }

    private fun resolveInstallerPath(): Path? {
        configuredInstallerPath()?.let { return it }

        val directCandidates = listOf(
            findLatestJpackageInstaller(),
            Paths.get("./download/DeployProject.exe"),
            Paths.get("./build/download/DeployProject.exe")
        ).filterNotNull()

        directCandidates
            .map { it.toAbsolutePath().normalize() }
            .firstOrNull { Files.isRegularFile(it) }
            ?.let { return it }

        return null
    }

    private fun configuredInstallerPath(): Path? {
        val configuredPath = installerPath.trim()
        if (configuredPath.isBlank()) return null

        return Paths.get(configuredPath)
            .toAbsolutePath()
            .normalize()
            .takeIf { Files.isRegularFile(it) }
    }

    private fun findLatestJpackageInstaller(): Path? {
        val jpackageOutputDir = Paths.get("./build/jpackage-output").toAbsolutePath().normalize()
        if (!Files.isDirectory(jpackageOutputDir)) return null

        return Files.list(jpackageOutputDir).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { it.name.startsWith("DeployProject-") && it.name.endsWith(".exe") }
                .max(Comparator.comparing { Files.getLastModifiedTime(it) })
                .orElse(null)
        }
    }
}
