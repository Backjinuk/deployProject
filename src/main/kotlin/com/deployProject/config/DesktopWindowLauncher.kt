package com.deployProject.config

import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.image.Image
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.web.WebView
import javafx.stage.Screen
import javafx.stage.Stage
import javafx.util.Duration
import javafx.animation.PauseTransition
import org.eclipse.swt.SWT
import org.eclipse.swt.browser.Browser
import org.eclipse.swt.graphics.Image as SwtImage
import org.eclipse.swt.layout.FillLayout
import org.eclipse.swt.widgets.Display
import org.eclipse.swt.widgets.Shell
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.context.WebServerInitializedEvent
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

@Component
@Profile("desktop")
class DesktopWindowLauncher(
    @Value("\${deploy.desktop.open-browser:true}")
    private val openBrowser: Boolean,
    @Value("\${deploy.desktop.window-mode:swt-edge}")
    private val windowMode: String,
    private val applicationContext: ConfigurableApplicationContext
) : ApplicationListener<WebServerInitializedEvent> {

    private val logger = LoggerFactory.getLogger(DesktopWindowLauncher::class.java)
    private val opened = AtomicBoolean(false)
    private val browserFallbackOpened = AtomicBoolean(false)

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
        when (windowMode.lowercase()) {
            "browser" -> openDefaultBrowser(url)
            "native" -> {
                if (openNativeWindow(url)) return
                openDefaultBrowser(url)
            }
            "edge-app" -> {
                if (openEdgeAppWindow(url)) return
                logger.warn("deployKit Edge app window launch failed. Falling back to default browser.")
                openDefaultBrowser(url)
            }
            else -> {
                if (openSwtEdgeWindow(url)) return
                if (openEdgeAppWindow(url)) return
                logger.warn("deployKit SWT Edge window launch failed. Falling back to default browser.")
                openDefaultBrowser(url)
            }
        }
    }

    private fun openSwtEdgeWindow(url: String): Boolean {
        if (!System.getProperty("os.name").lowercase().contains("windows")) return false

        return runCatching {
            openSwtEdgeWindowBlocking(url)
        }.onFailure { error ->
            logger.warn("deployKit SWT Edge WebView2 launch failed: {}", url, error)
        }.isSuccess
    }

    private fun openSwtEdgeWindowBlocking(url: String) {
        val userDataDirectory = resolveSwtEdgeDataDirectory().apply { mkdirs() }
        System.setProperty("org.eclipse.swt.browser.DefaultType", "edge")
        System.setProperty("org.eclipse.swt.browser.EdgeDataDir", userDataDirectory.absolutePath)
        System.setProperty("org.eclipse.swt.browser.EdgeArgs", "--disable-features=Translate")

        Display.setAppName("DeployKit")
        val display = Display()
        var icon: SwtImage? = null

        try {
            val shell = Shell(display).apply {
                text = "deployKit"
                layout = FillLayout()
            }

            javaClass.getResourceAsStream("/brand/deploykit-icon.png")?.use { iconStream ->
                icon = SwtImage(display, iconStream)
                shell.images = arrayOf(icon)
            }

            Browser(shell, SWT.EDGE).url = url

            val bounds = display.primaryMonitor.clientArea
            val targetWidth = minOf(1320, bounds.width)
            val targetHeight = minOf(860, bounds.height)
            val minimumWidth = minOf(1024, bounds.width)
            val minimumHeight = minOf(680, bounds.height)

            shell.setMinimumSize(minimumWidth, minimumHeight)
            shell.setSize(targetWidth, targetHeight)
            shell.setLocation(
                bounds.x + (bounds.width - targetWidth) / 2,
                bounds.y + (bounds.height - targetHeight) / 2
            )
            shell.addListener(SWT.Close) {
                shutdownApplication()
            }
            shell.open()
            logger.info("deployKit desktop UI opened in SWT Edge WebView2 mode: {}", url)

            while (!shell.isDisposed) {
                if (!display.readAndDispatch()) {
                    display.sleep()
                }
            }
        } finally {
            icon?.dispose()
            if (!display.isDisposed) {
                display.dispose()
            }
        }
    }

    private fun openEdgeAppWindow(url: String): Boolean {
        if (!System.getProperty("os.name").lowercase().contains("windows")) return false

        val edgeExecutable = resolveEdgeExecutable() ?: return false
        val userDataDirectory = resolveEdgeUserDataDirectory().apply { mkdirs() }
        val appUrl = appendDesktopWindowMode(url)
        val command = listOf(
            edgeExecutable.absolutePath,
            "--app=$appUrl",
            "--new-window",
            "--no-first-run",
            "--disable-extensions",
            "--disable-features=Translate",
            "--user-data-dir=${userDataDirectory.absolutePath}"
        )

        return runCatching {
            ProcessBuilder(command).start()
        }.onSuccess {
            logger.info("deployKit desktop UI opened in Edge app mode: {}", appUrl)
        }.onFailure { error ->
            logger.warn("deployKit Edge app mode launch failed: {}", url, error)
        }.isSuccess
    }

    private fun appendDesktopWindowMode(url: String): String {
        val separator = if (url.contains("?")) "&" else "?"
        return "${url}${separator}desktopWindow=edge-app"
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
        configureJavaFxRendering()

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
        val webView = WebView()
        val root = StackPane(webView)
        configureWebEngine(webView, root, url)
        webView.engine.load(url)

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
            scene = Scene(root, targetWidth, targetHeight)
            setOnCloseRequest {
                shutdownApplication()
            }
            show()
        }

        scheduleRenderFallback(webView, root, url)
    }

    private fun configureJavaFxRendering() {
        System.setProperty("sun.java2d.d3d", "false")
        System.setProperty("prism.order", System.getProperty("DEPLOY_PROJECT_PRISM_ORDER", "sw"))
    }

    private fun configureWebEngine(webView: WebView, root: StackPane, url: String) {
        val engine = webView.engine
        configureWebViewUserDataDirectory(webView)

        engine.onError = javafx.event.EventHandler { event ->
            logger.warn("deployKit WebView error: {}", event.message)
            openBrowserFallback(root, url, "내장 화면에서 오류가 발생해 기본 브라우저로 열었습니다.")
        }
        engine.setOnAlert { event ->
            logger.info("deployKit WebView alert: {}", event.data)
        }
        engine.setOnStatusChanged { event ->
            val data = event.data.orEmpty()
            if (data.startsWith(JS_ERROR_STATUS_PREFIX)) {
                logger.warn("deployKit frontend JavaScript error: {}", data.removePrefix(JS_ERROR_STATUS_PREFIX))
                openBrowserFallback(root, url, "화면 스크립트 실행에 실패해 기본 브라우저로 열었습니다.")
            }
        }
        engine.loadWorker.exceptionProperty().addListener { _, _, error ->
            if (error != null) {
                logger.warn("deployKit WebView load exception: {}", error.message, error)
                openBrowserFallback(root, url, "내장 화면 로딩에 실패해 기본 브라우저로 열었습니다.")
            }
        }
        engine.loadWorker.stateProperty().addListener { _, _, state ->
            when (state) {
                Worker.State.SUCCEEDED -> {
                    logger.info("deployKit WebView page loaded: {}", url)
                    injectJavaScriptErrorReporter(webView)
                }
                Worker.State.FAILED, Worker.State.CANCELLED -> {
                    logger.warn("deployKit WebView page load did not complete: state={}, url={}", state, url)
                    openBrowserFallback(root, url, "내장 화면 로딩이 완료되지 않아 기본 브라우저로 열었습니다.")
                }
                else -> Unit
            }
        }
    }

    private fun configureWebViewUserDataDirectory(webView: WebView) {
        runCatching {
            val userDataDirectory = resolveWebViewUserDataDirectory()
            userDataDirectory.mkdirs()
            webView.engine.userDataDirectory = userDataDirectory
            logger.info("deployKit WebView user data directory: {}", userDataDirectory.absolutePath)
        }.onFailure { error ->
            logger.warn("deployKit WebView user data directory setup failed", error)
        }
    }

    private fun resolveWebViewUserDataDirectory(): File {
        val baseDir = System.getenv("LOCALAPPDATA")
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it, "DeployKit") }
            ?: File(System.getProperty("user.home"), ".deploykit")
        val webViewRoot = File(baseDir, "webview")
        val currentDir = File(webViewRoot, ProcessHandle.current().pid().toString())

        cleanupOldWebViewUserDataDirectories(webViewRoot, currentDir)

        return currentDir
    }

    private fun resolveEdgeUserDataDirectory(): File {
        val baseDir = System.getenv("LOCALAPPDATA")
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it, "DeployKit") }
            ?: File(System.getProperty("user.home"), ".deploykit")
        val edgeRoot = File(baseDir, "edge-app")
        val currentDir = File(edgeRoot, ProcessHandle.current().pid().toString())

        cleanupOldWebViewUserDataDirectories(edgeRoot, currentDir)

        return currentDir
    }

    private fun resolveSwtEdgeDataDirectory(): File {
        val baseDir = System.getenv("LOCALAPPDATA")
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it, "DeployKit") }
            ?: File(System.getProperty("user.home"), ".deploykit")
        val edgeRoot = File(baseDir, "swt-edge")
        val currentDir = File(edgeRoot, ProcessHandle.current().pid().toString())

        cleanupOldWebViewUserDataDirectories(edgeRoot, currentDir)

        return currentDir
    }

    private fun resolveEdgeExecutable(): File? {
        val candidates = listOfNotNull(
            System.getenv("ProgramFiles(x86)")?.let { File(it, "Microsoft/Edge/Application/msedge.exe") },
            System.getenv("ProgramFiles")?.let { File(it, "Microsoft/Edge/Application/msedge.exe") },
            System.getenv("LOCALAPPDATA")?.let { File(it, "Microsoft/Edge/Application/msedge.exe") }
        )

        return candidates.firstOrNull { it.isFile }
            ?: runCatching {
                ProcessBuilder("where", "msedge").start().inputStream.bufferedReader().useLines { lines ->
                    lines.map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { File(it) }
                        .firstOrNull { it.isFile }
                }
            }.getOrNull()
    }

    private fun cleanupOldWebViewUserDataDirectories(webViewRoot: File, currentDir: File) {
        val now = System.currentTimeMillis()
        webViewRoot.listFiles()
            ?.filter { it.isDirectory && it.absolutePath != currentDir.absolutePath }
            ?.filter { now - it.lastModified() > WEBVIEW_USER_DATA_RETENTION_MS }
            ?.forEach { dir ->
                runCatching { dir.deleteRecursively() }
                    .onFailure { logger.debug("Failed to cleanup old WebView user data directory: {}", dir.absolutePath, it) }
            }
    }

    private fun injectJavaScriptErrorReporter(webView: WebView) {
        runCatching {
            webView.engine.executeScript(
                """
                window.onerror = function(message, source, line, column) {
                  window.status = '$JS_ERROR_STATUS_PREFIX' + message + ' @ ' + source + ':' + line + ':' + column;
                };
                true;
                """.trimIndent()
            )
        }.onFailure { error ->
            logger.debug("deployKit JavaScript error reporter injection failed", error)
        }
    }

    private fun scheduleRenderFallback(webView: WebView, root: StackPane, url: String) {
        PauseTransition(Duration.seconds(6.0)).apply {
            setOnFinished {
                val rendered = isReactRootRendered(webView)
                if (!rendered) {
                    logger.warn("deployKit WebView stayed blank. Opening default browser: {}", url)
                    openBrowserFallback(root, url, "내장 화면이 표시되지 않아 기본 브라우저로 열었습니다.")
                }
            }
            play()
        }
    }

    private fun isReactRootRendered(webView: WebView): Boolean {
        return runCatching {
            webView.engine.executeScript(
                """
                (function() {
                  var root = document.getElementById('root');
                  return !!(root && root.children && root.children.length > 0);
                })();
                """.trimIndent()
            ) as? Boolean ?: false
        }.getOrDefault(false)
    }

    private fun openBrowserFallback(root: StackPane, url: String, message: String) {
        showFallbackMessage(root, url, message)
        if (!browserFallbackOpened.compareAndSet(false, true)) return

        Thread {
            openDefaultBrowser(url)
        }.apply {
            name = "deploykit-browser-fallback"
            isDaemon = true
            start()
        }
    }

    private fun showFallbackMessage(root: StackPane, url: String, message: String) {
        val browserButton = Button("브라우저로 다시 열기").apply {
            setOnAction {
                Thread {
                    openDefaultBrowser(url)
                }.apply {
                    name = "deploykit-browser-open"
                    isDaemon = true
                    start()
                }
            }
        }

        root.children.setAll(
            VBox(14.0).apply {
                alignment = Pos.CENTER
                children.add(Label(message))
                children.add(Label(url))
                children.add(browserButton)
            }
        )
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
        private const val JS_ERROR_STATUS_PREFIX = "DEPLOYKIT_JS_ERROR:"
        private const val WEBVIEW_USER_DATA_RETENTION_MS = 24L * 60L * 60L * 1000L
    }
}
