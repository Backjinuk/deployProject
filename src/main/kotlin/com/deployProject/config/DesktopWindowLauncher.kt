package com.deployProject.config

import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.scene.layout.StackPane
import javafx.scene.web.WebView
import javafx.stage.Screen
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.context.WebServerInitializedEvent
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.awt.Desktop
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

@Component
@Profile("desktop")
class DesktopWindowLauncher(
    @Value("\${deploy.desktop.open-browser:true}")
    private val openBrowser: Boolean,
    @Value("\${deploy.desktop.window-mode:native}")
    private val windowMode: String,
    private val applicationContext: ConfigurableApplicationContext
) : ApplicationListener<WebServerInitializedEvent> {

    private val logger = LoggerFactory.getLogger(DesktopWindowLauncher::class.java)
    private val opened = AtomicBoolean(false)

    override fun onApplicationEvent(event: WebServerInitializedEvent) {
        if (!openBrowser || !opened.compareAndSet(false, true)) return

        val url = "http://127.0.0.1:${event.webServer.port}/"
        Thread {
            Thread.sleep(700)
            open(url)
        }.apply {
            name = "deploykit-window-launcher"
            isDaemon = true
            start()
        }
    }

    private fun open(url: String) {
        if (!windowMode.equals("browser", ignoreCase = true)) {
            if (openNativeWindow(url)) return
        }

        openDefaultBrowser(url)
    }

    private fun openNativeWindow(url: String): Boolean {
        return runCatching {
            openJavaFxWindow(url)
        }.onSuccess {
            logger.info("deployKit desktop native window opened: {}", url)
        }.onFailure { error ->
            logger.warn("deployKit native window launch failed. Falling back to default browser.", error)
        }.isSuccess
    }

    private fun openJavaFxWindow(url: String) {
        if (javaFxStarted.compareAndSet(false, true)) {
            try {
                Platform.startup {
                    Platform.setImplicitExit(false)
                    showStage(url)
                }
                return
            } catch (_: IllegalStateException) {
                // JavaFX toolkit is already available. Reuse it below.
            }
        }

        Platform.runLater {
            showStage(url)
        }
    }

    private fun showStage(url: String) {
        val webView = WebView().apply {
            engine.load(url)
        }
        val bounds = Screen.getPrimary().visualBounds
        val targetWidth = minOf(1320.0, bounds.width)
        val targetHeight = minOf(860.0, bounds.height)
        val targetX = bounds.minX + (bounds.width - targetWidth) / 2
        val targetY = bounds.minY + (bounds.height - targetHeight) / 2

        Stage().apply {
            title = "deployKit"
            javaClass.getResourceAsStream("/brand/deploykit-icon.png")?.use { iconStream ->
                icons.add(Image(iconStream))
            }
            minWidth = minOf(1024.0, bounds.width)
            minHeight = minOf(680.0, bounds.height)
            x = targetX
            y = targetY
            width = targetWidth
            height = targetHeight
            scene = Scene(StackPane(webView), targetWidth, targetHeight)
            setOnCloseRequest {
                shutdownApplication()
            }
            show()
        }
    }

    private fun shutdownApplication() {
        Thread {
            val exitCode = runCatching {
                applicationContext.close()
                0
            }.getOrElse { error ->
                logger.warn("deployKit shutdown failed", error)
                1
            }

            runCatching {
                Platform.exit()
            }

            exitProcess(exitCode)
        }.apply {
            name = "deploykit-shutdown"
            isDaemon = false
            start()
        }
    }

    private fun openDefaultBrowser(url: String) {
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
            logger.info("deployKit desktop UI opened in browser: {}", url)
        }.onFailure { error ->
            logger.warn("deployKit is running, but the browser could not be opened automatically: {}", url, error)
        }
    }

    companion object {
        private val javaFxStarted = AtomicBoolean(false)
    }
}
