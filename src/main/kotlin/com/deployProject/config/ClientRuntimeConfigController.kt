package com.deployProject.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import jakarta.servlet.http.HttpServletRequest

@RestController
class ClientRuntimeConfigController(
    private val objectMapper: ObjectMapper,
    @Value("\${deploy.client.ui-mode:APP}")
    private val uiMode: String,
    @Value("\${deploy.client.server-api-mode:LOCAL}")
    private val serverApiMode: String,
    @Value("\${deploy.client.remote-api-base-url:}")
    private val remoteApiBaseUrl: String,
    @Value("\${deploy.client.installer-download-url:}")
    private val installerDownloadUrl: String
) {

    @GetMapping("/runtime-config.js", produces = ["application/javascript;charset=UTF-8"])
    fun runtimeConfig(request: HttpServletRequest): ResponseEntity<String> {
        val requestBaseUrl = requestBaseUrl(request)
        val resolvedRemoteApiBaseUrl = remoteApiBaseUrl.trim().ifBlank { requestBaseUrl }.trimEnd('/')
        val configuredInstallerBaseUrl = remoteApiBaseUrl.trim().trimEnd('/')
        val resolvedInstallerDownloadUrl = installerDownloadUrl.trim()
            .ifBlank {
                if (configuredInstallerBaseUrl.isBlank()) {
                    "/download/deploy-project.exe"
                } else {
                    "$configuredInstallerBaseUrl/download/deploy-project.exe"
                }
            }

        val config = mapOf(
            "uiMode" to uiMode.trim().uppercase(),
            "serverApiMode" to serverApiMode.trim().uppercase(),
            "remoteApiBaseUrl" to resolvedRemoteApiBaseUrl,
            "installerDownloadUrl" to resolvedInstallerDownloadUrl
        )
        val script = "window.__DEPLOY_PROJECT_CONFIG__ = ${objectMapper.writeValueAsString(config)};"

        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(script)
    }

    private fun requestBaseUrl(request: HttpServletRequest): String {
        val forwarded = parseForwardedHeader(request.getHeader("Forwarded"))
        val scheme = forwarded["proto"]
            ?: request.getHeader("X-Forwarded-Proto")?.substringBefore(",")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: request.scheme
        val host = forwarded["host"]
            ?: request.getHeader("X-Forwarded-Host")?.substringBefore(",")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: request.getHeader("Host")?.trim()
            ?: "${request.serverName}:${request.serverPort}"

        return "$scheme://$host"
    }

    private fun parseForwardedHeader(value: String?): Map<String, String> {
        if (value.isNullOrBlank()) return emptyMap()

        return value.substringBefore(",")
            .split(";")
            .mapNotNull { part ->
                val key = part.substringBefore("=", "").trim().lowercase()
                val rawValue = part.substringAfter("=", "").trim().trim('"')
                if (key.isBlank() || rawValue.isBlank()) null else key to rawValue
            }
            .toMap()
    }
}
