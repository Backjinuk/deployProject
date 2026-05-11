package com.deployProject.deploy.service

import java.awt.GraphicsEnvironment
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

object LocalDirectoryPicker {
    fun chooseDirectory(currentPath: String?, title: String?): String? {
        val dialogTitle = title?.trim().takeUnless { it.isNullOrBlank() } ?: "Select directory"
        val initialDirectory = resolveInitialDirectory(currentPath)
        val osName = System.getProperty("os.name").lowercase()

        // Prefer native dialogs first so the picker behaves like the host OS and stays in front
        // of the user's desktop instead of hiding behind the Spring process window.
        return when {
            osName.contains("win") -> chooseDirectoryOnWindows(initialDirectory, dialogTitle)
            osName.contains("mac") -> chooseDirectoryOnMac(initialDirectory, dialogTitle)
            osName.contains("nux") || osName.contains("nix") -> chooseDirectoryOnLinux(initialDirectory, dialogTitle)
            else -> chooseDirectoryWithSwing(initialDirectory, dialogTitle)
        }
    }

    private fun chooseDirectoryOnWindows(initialDirectory: File?, title: String): String? {
        val escapedPath = escapePowerShellLiteral(initialDirectory?.absolutePath.orEmpty())
        val escapedTitle = escapePowerShellLiteral(title)
        val script = """
            Add-Type -AssemblyName System.Windows.Forms
            Add-Type -AssemblyName System.Drawing
            [System.Windows.Forms.Application]::EnableVisualStyles()
            ${'$'}owner = New-Object System.Windows.Forms.Form
            ${'$'}owner.StartPosition = 'CenterScreen'
            ${'$'}owner.Size = New-Object System.Drawing.Size(1, 1)
            ${'$'}owner.ShowInTaskbar = ${'$'}false
            ${'$'}owner.TopMost = ${'$'}true
            ${'$'}owner.Opacity = 0
            ${'$'}owner.Show()
            ${'$'}owner.Activate()
            ${'$'}dialog = New-Object System.Windows.Forms.FolderBrowserDialog
            ${'$'}dialog.Description = '$escapedTitle'
            if ('$escapedPath' -ne '') { ${'$'}dialog.SelectedPath = '$escapedPath' }
            ${'$'}result = ${'$'}dialog.ShowDialog(${'$'}owner)
            ${'$'}owner.Close()
            if (${'$'}result -eq [System.Windows.Forms.DialogResult]::OK) {
                [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                Write-Output ${'$'}dialog.SelectedPath
            }
        """.trimIndent()

        return runCommandForPath(
            command = listOf(
                "powershell.exe",
                "-NoProfile",
                "-STA",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                script
            ),
            failureMessage = "Windows directory picker failed."
        )
    }

    private fun chooseDirectoryOnMac(initialDirectory: File?, title: String): String? {
        val escapedTitle = escapeAppleScriptString(title)
        val chooseFolderScript = buildString {
            append("set chosenFolder to choose folder with prompt \"")
            append(escapedTitle)
            append("\"")
            initialDirectory?.absolutePath?.let { path ->
                append(" default location POSIX file \"")
                append(escapeAppleScriptString(path))
                append("\"")
            }
        }

        return runCommandForPath(
            command = listOf(
                "osascript",
                "-e", "tell application \"System Events\" to activate",
                "-e", chooseFolderScript,
                "-e", "POSIX path of chosenFolder"
            ),
            cancelExitCodes = setOf(1),
            cancelMarkers = listOf("-128", "User canceled"),
            failureMessage = "macOS directory picker failed."
        )
    }

    private fun chooseDirectoryOnLinux(initialDirectory: File?, title: String): String? {
        // Linux desktop stacks vary. Try common native pickers first, then fall back to Swing
        // so Ubuntu/GNOME and KDE users still get an OS-integrated dialog when available.
        val initialPath = initialDirectory?.absolutePath
        findExecutable("zenity")?.let { zenity ->
            return runCommandForPath(
                command = buildList {
                    add(zenity.absolutePath)
                    add("--file-selection")
                    add("--directory")
                    add("--title=$title")
                    initialPath?.let { add("--filename=${ensureTrailingSeparator(it)}") }
                },
                cancelExitCodes = setOf(1),
                failureMessage = "zenity directory picker failed."
            )
        }

        findExecutable("kdialog")?.let { kdialog ->
            return runCommandForPath(
                command = buildList {
                    add(kdialog.absolutePath)
                    add("--getexistingdirectory")
                    add(initialPath ?: System.getProperty("user.home"))
                    add("--title")
                    add(title)
                },
                cancelExitCodes = setOf(1),
                failureMessage = "kdialog directory picker failed."
            )
        }

        return chooseDirectoryWithSwing(initialDirectory, title)
    }

    private fun chooseDirectoryWithSwing(initialDirectory: File?, title: String): String? {
        check(!GraphicsEnvironment.isHeadless()) { "Directory picker is not available in headless mode." }

        val selectedPath = AtomicReference<String?>(null)
        val failure = AtomicReference<Throwable?>(null)

        SwingUtilities.invokeAndWait {
            try {
                val chooser = JFileChooser().apply {
                    dialogTitle = title
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    isAcceptAllFileFilterUsed = false

                    initialDirectory?.let { directory ->
                        currentDirectory = directory
                        selectedFile = directory
                    }
                }

                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    selectedPath.set(chooser.selectedFile?.absolutePath)
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }

        failure.get()?.let { throw IllegalStateException("Swing directory picker failed.", it) }
        return selectedPath.get()
    }

    private fun runCommandForPath(
        command: List<String>,
        cancelExitCodes: Set<Int> = emptySet(),
        cancelMarkers: List<String> = emptyList(),
        failureMessage: String
    ): String? {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText().trim()
        val exitCode = process.waitFor()

        if (exitCode in cancelExitCodes || cancelMarkers.any { output.contains(it, ignoreCase = true) }) {
            return null
        }

        require(exitCode == 0) {
            listOf(failureMessage, output)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }

        return output.ifBlank { null }
    }

    private fun findExecutable(name: String): File? {
        val pathEntries = (System.getenv("PATH") ?: "")
            .split(File.pathSeparator)
            .map(String::trim)
            .filter { it.isNotEmpty() }

        return pathEntries
            .map { File(it, name) }
            .firstOrNull { it.isFile }
    }

    private fun resolveInitialDirectory(currentPath: String?): File? {
        val trimmed = currentPath?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        val file = runCatching { File(trimmed).canonicalFile }.getOrNull() ?: return null
        return when {
            file.isDirectory -> file
            file.parentFile?.isDirectory == true -> file.parentFile
            else -> null
        }
    }

    private fun ensureTrailingSeparator(path: String): String =
        if (path.endsWith(File.separator)) path else path + File.separator

    private fun escapePowerShellLiteral(value: String): String =
        value.replace("'", "''")

    private fun escapeAppleScriptString(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
