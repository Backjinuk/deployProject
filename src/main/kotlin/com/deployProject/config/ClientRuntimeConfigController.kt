package com.deployProject.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class ClientRuntimeConfigController(
    private val objectMapper: ObjectMapper,
    @Value("\${deploy.client.ui-mode:APP}")
    private val uiMode: String,
    @Value("\${deploy.client.server-api-mode:LOCAL}")
    private val serverApiMode: String,
    @Value("\${deploy.client.remote-api-base-url:http://backjin.iptime.org:9090}")
    private val remoteApiBaseUrl: String,
    @Value("\${deploy.client.installer-download-url:/download/deploy-project.exe}")
    private val installerDownloadUrl: String
) {

    @GetMapping("/runtime-config.js", produces = ["application/javascript;charset=UTF-8"])
    fun runtimeConfig(): ResponseEntity<String> {
        val config = mapOf(
            "uiMode" to uiMode.trim().uppercase(),
            "serverApiMode" to serverApiMode.trim().uppercase(),
            "remoteApiBaseUrl" to remoteApiBaseUrl.trim().trimEnd('/'),
            "installerDownloadUrl" to installerDownloadUrl.trim()
        )
        val script = "window.__DEPLOY_PROJECT_CONFIG__ = ${objectMapper.writeValueAsString(config)};"

        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(script)
    }
}
