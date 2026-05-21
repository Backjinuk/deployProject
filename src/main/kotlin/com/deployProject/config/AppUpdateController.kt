package com.deployProject.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.info.BuildProperties
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.awt.Desktop
import java.net.URI
import java.util.concurrent.TimeUnit

@RestController
class AppUpdateController(
    private val objectMapper: ObjectMapper,
    buildPropertiesProvider: ObjectProvider<BuildProperties>,
    @Value("\${deploy.client.latest-version-url:https://deploy.jinukl.dev/version.json}")
    private val latestVersionUrl: String,
    @Value("\${deploy.client.installer-download-url:}")
    private val installerDownloadUrl: String
) {
    private val buildProperties = buildPropertiesProvider.ifAvailable
    private val fallbackInstallerUrl = "https://deploy.jinukl.dev/download/deploykit.exe"

    @GetMapping("/api/app/update-check")
    fun updateCheck(): ResponseEntity<AppUpdateResponse> {
        val currentVersion = buildProperties?.version?.trim().orEmpty()
        if (currentVersion.isBlank()) return ResponseEntity.noContent().build()

        val manifest = runCatching { fetchLatestVersionManifest() }.getOrNull()
            ?: return ResponseEntity.noContent().build()

        val latestVersion = manifest.latestVersion?.trim()
            ?: manifest.version?.trim()
            ?: return ResponseEntity.noContent().build()

        if (!isNewerVersion(latestVersion, currentVersion)) {
            return ResponseEntity.noContent().build()
        }

        val response = AppUpdateResponse(
            currentVersion = currentVersion,
            latestVersion = latestVersion,
            installerUrl = manifest.installerUrl?.takeIf { it.isNotBlank() }
                ?: manifest.downloadUrl?.takeIf { it.isNotBlank() }
                ?: installerDownloadUrl.takeIf { it.isNotBlank() }
                ?: fallbackInstallerUrl,
            message = manifest.message,
            releaseNotes = manifest.releaseNotes ?: emptyList()
        )

        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES))
            .body(response)
    }

    @PostMapping("/api/app/open-update-installer")
    fun openUpdateInstaller(@RequestBody request: OpenUpdateInstallerRequest): ResponseEntity<AppUpdateOpenResponse> {
        val installerUrl = request.installerUrl
            ?.trim()
            ?.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
            ?: runCatching {
                val manifest = fetchLatestVersionManifest()
                manifest.installerUrl?.takeIf { it.isNotBlank() }
                    ?: manifest.downloadUrl?.takeIf { it.isNotBlank() }
            }.getOrNull()
            ?: installerDownloadUrl.takeIf { it.isNotBlank() }
            ?: fallbackInstallerUrl

        openExternalUrl(installerUrl)

        return ResponseEntity.ok(AppUpdateOpenResponse(installerUrl = installerUrl))
    }

    private fun fetchLatestVersionManifest(): LatestVersionManifest {
        val connection = URI(latestVersionUrl.trim()).toURL().openConnection()
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        return connection.getInputStream().use { input ->
            objectMapper.readValue(input, LatestVersionManifest::class.java)
        }
    }

    private fun openExternalUrl(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                return
            }
        }

        val command = when {
            System.getProperty("os.name").lowercase().contains("windows") ->
                listOf("rundll32", "url.dll,FileProtocolHandler", url)
            System.getProperty("os.name").lowercase().contains("mac") ->
                listOf("open", url)
            else ->
                listOf("xdg-open", url)
        }

        ProcessBuilder(command).start()
    }

    private fun isNewerVersion(latestVersion: String, currentVersion: String): Boolean {
        val latestParts = versionParts(latestVersion)
        val currentParts = versionParts(currentVersion)
        val length = maxOf(latestParts.size, currentParts.size)

        for (index in 0 until length) {
            val latestPart = latestParts.getOrElse(index) { 0 }
            val currentPart = currentParts.getOrElse(index) { 0 }
            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }

        return false
    }

    private fun versionParts(version: String): List<Int> =
        version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .split(".", "-")
            .map { part -> part.filter { it.isDigit() }.toIntOrNull() ?: 0 }
}

data class LatestVersionManifest(
    val version: String? = null,
    val latestVersion: String? = null,
    val installerUrl: String? = null,
    val downloadUrl: String? = null,
    val message: String? = null,
    val releaseNotes: List<String>? = null
)

data class AppUpdateResponse(
    val currentVersion: String,
    val latestVersion: String,
    val installerUrl: String,
    val message: String? = null,
    val releaseNotes: List<String> = emptyList()
)

data class OpenUpdateInstallerRequest(
    val installerUrl: String? = null
)

data class AppUpdateOpenResponse(
    val installerUrl: String
)
