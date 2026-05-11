package com.deployProject.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.context.WebServerInitializedEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.awt.Desktop
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

@Component
@Profile("desktop")
class DesktopBrowserLauncher(
    @Value("\${deploy.desktop.open-browser:true}")
    private val openBrowser: Boolean
) : ApplicationListener<WebServerInitializedEvent> {

    private val logger = LoggerFactory.getLogger(DesktopBrowserLauncher::class.java)
    private val opened = AtomicBoolean(false)

    override fun onApplicationEvent(event: WebServerInitializedEvent) {
        if (!openBrowser || !opened.compareAndSet(false, true)) return

        val url = "http://127.0.0.1:${event.webServer.port}/"
        Thread {
            Thread.sleep(700)
            open(url)
        }.apply {
            name = "deploy-project-browser-launcher"
            isDaemon = true
            start()
        }
    }

    private fun open(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                return
            }
        }.onFailure { error ->
            logger.debug("Desktop browse failed. Falling back to OS command.", error)
        }

        val command = when {
            System.getProperty("os.name").lowercase().contains("windows") ->
                listOf("rundll32", "url.dll,FileProtocolHandler", url)
            System.getProperty("os.name").lowercase().contains("mac") ->
                listOf("open", url)
            else ->
                listOf("xdg-open", url)
        }

        runCatching {
            ProcessBuilder(command).start()
        }.onSuccess {
            logger.info("DeployProject desktop UI opened: {}", url)
        }.onFailure { error ->
            logger.warn("DeployProject is running, but the browser could not be opened automatically: {}", url, error)
        }
    }
}
