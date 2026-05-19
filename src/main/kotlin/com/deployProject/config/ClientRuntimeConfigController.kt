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
    private val defaultApiPort = "9090"
    private val productionPublicBaseUrl = "https://deploy.jinuk.dev"
    private val installerDownloadPath = "/download/deploy-project.exe"

    @GetMapping("/runtime-config.js", produces = ["application/javascript;charset=UTF-8"])
    fun runtimeConfig(request: HttpServletRequest): ResponseEntity<String> {
        val resolvedRemoteApiBaseUrl = remoteApiBaseUrl.trim()
            .ifBlank { defaultApiBaseUrl(request) }
            .let { forcePublicHttpsUrl(it, request) }
            .trimEnd('/')
        val resolvedInstallerDownloadUrl = installerDownloadUrl.trim()
            .ifBlank {
                "$resolvedRemoteApiBaseUrl$installerDownloadPath"
            }
            .let { forceProductionInstallerDownloadUrl(it, request) }

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

    private fun defaultApiBaseUrl(request: HttpServletRequest): String {
        val requestBaseUrl = requestBaseUrl(request)
        val host = requestBaseUrl
            .substringAfter("://", requestBaseUrl)
            .substringBefore("/")
            .substringBefore(":")
            .lowercase()

        return if (host == "localhost" || host == "127.0.0.1") {
            "http://localhost:$defaultApiPort"
        } else {
            publicBaseUrl(request)
        }
    }

    private fun publicBaseUrl(request: HttpServletRequest): String {
        val requestBaseUrl = requestBaseUrl(request).trimEnd('/')
        if (isLocalRequest(request)) return requestBaseUrl

        val host = requestBaseUrl
            .substringAfter("://", requestBaseUrl)
            .substringBefore("/")
            .substringBefore(":")
            .lowercase()

        return if (
            host == "deploy.jinuk.dev" &&
            requestBaseUrl.startsWith("https://", ignoreCase = true)
        ) {
            requestBaseUrl
        } else {
            productionPublicBaseUrl
        }
    }

    private fun forcePublicHttpsUrl(value: String, request: HttpServletRequest): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isBlank() || isLocalRequest(request)) return trimmed

        return if (trimmed.startsWith("http://", ignoreCase = true)) {
            publicBaseUrl(request)
        } else {
            trimmed
        }
    }

    private fun forceProductionInstallerDownloadUrl(value: String, request: HttpServletRequest): String {
        val trimmed = value.trim()
        if (isLocalRequest(request)) return trimmed

        val pathOnly = trimmed
            .substringBefore("?")
            .substringBefore("#")
            .trimEnd('/')

        return if (pathOnly == installerDownloadPath || pathOnly.endsWith(installerDownloadPath)) {
            "${publicBaseUrl(request)}$installerDownloadPath"
        } else if (trimmed.startsWith("http://", ignoreCase = true)) {
            trimmed.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
        } else {
            trimmed
        }
    }

    private fun isLocalRequest(request: HttpServletRequest): Boolean {
        val host = requestBaseUrl(request)
            .substringAfter("://", "")
            .substringBefore("/")
            .substringBefore(":")
            .lowercase()

        return host == "localhost" || host == "127.0.0.1"
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
