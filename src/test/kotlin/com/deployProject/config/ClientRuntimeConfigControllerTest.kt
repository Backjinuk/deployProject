package com.deployProject.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.mock.web.MockHttpServletRequest
import java.util.Properties

class ClientRuntimeConfigControllerTest {

    @Test
    fun `runtime config defaults to public https urls for production requests`() {
        val controller = ClientRuntimeConfigController(
            ObjectMapper(),
            buildPropertiesProvider(),
            "DOWNLOAD",
            "",
            ""
        )
        val request = productionRequest()

        val body = controller.runtimeConfig(request).body!!

        assertTrue(
            body.contains("\"installerDownloadUrl\":\"https://deploy.jinukl.dev/download/deploykit.exe\""),
            body
        )
    }

    @Test
    fun `runtime config rewrites insecure production download urls`() {
        val controller = ClientRuntimeConfigController(
            ObjectMapper(),
            buildPropertiesProvider(),
            "DOWNLOAD",
            "http://backjin.iptime.org:9090/download/deploykit.exe",
            ""
        )
        val request = productionRequest()

        val body = controller.runtimeConfig(request).body!!

        assertTrue(
            body.contains("\"installerDownloadUrl\":\"https://deploy.jinukl.dev/download/deploykit.exe\""),
            body
        )
    }

    private fun productionRequest(): MockHttpServletRequest {
        return MockHttpServletRequest("GET", "/runtime-config.js").apply {
            scheme = "http"
            serverName = "127.0.0.1"
            serverPort = 8080
            addHeader("Host", "backjin.iptime.org:9090")
        }
    }

    private fun buildPropertiesProvider(): ObjectProvider<BuildProperties> {
        val buildProperties = BuildProperties(Properties().apply { setProperty("version", "1.0.21") })
        return object : ObjectProvider<BuildProperties> {
            override fun getObject(): BuildProperties = buildProperties
        }
    }
}
