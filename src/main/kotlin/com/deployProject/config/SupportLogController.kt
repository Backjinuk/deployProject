package com.deployProject.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.info.GitProperties
import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class SupportLogExportResponse(
    val fileName: String,
    val path: String,
    val size: Long,
    val logFileCount: Int
)

@RestController
@RequestMapping("/api/support")
class SupportLogController(
    @Value("\${logging.file.name:}")
    private val configuredLogFile: String,
    private val environment: Environment,
    private val applicationContext: ApplicationContext
) {
    private val logger = LoggerFactory.getLogger(SupportLogController::class.java)

    @PostMapping("/logs/export")
    fun exportSupportLogs(): SupportLogExportResponse {
        val timestamp = DOWNLOAD_TIMESTAMP_FORMATTER.format(LocalDateTime.now())
        val exportFile = uniqueDownloadTarget(resolveDownloadsDir(), "deploykit-error-logs-$timestamp.zip")
        val logFiles = resolveLogFiles()

        ZipOutputStream(Files.newOutputStream(exportFile)).use { zip ->
            writeDiagnostics(zip, logFiles)
            logFiles.forEach { logFile ->
                val entryName = "logs/${logFile.fileName}"
                zip.putNextEntry(ZipEntry(entryName))
                Files.copy(logFile, zip)
                zip.closeEntry()
            }
        }

        logger.info("deployKit support logs exported: {}", exportFile.toAbsolutePath())

        return SupportLogExportResponse(
            fileName = exportFile.fileName.toString(),
            path = exportFile.toAbsolutePath().normalize().toString(),
            size = Files.size(exportFile),
            logFileCount = logFiles.size
        )
    }

    private fun resolveLogFiles(): List<Path> {
        val configured = configuredLogFile.trim().takeIf { it.isNotBlank() }
            ?.let { Path.of(it).toAbsolutePath().normalize() }

        val logDir = configured?.parent ?: Path.of(System.getProperty("user.home"), ".deploy-project", "logs")
        val files = linkedSetOf<Path>()

        if (configured != null && Files.isRegularFile(configured)) {
            files.add(configured)
        }

        if (Files.isDirectory(logDir)) {
            Files.list(logDir).use { stream ->
                stream
                    .filter(Files::isRegularFile)
                    .filter { path -> path.fileName.toString().startsWith("deploy-project") }
                    .forEach { files.add(it.toAbsolutePath().normalize()) }
            }
        }

        return files
            .filter { Files.isReadable(it) }
            .sortedByDescending { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
    }

    private fun writeDiagnostics(zip: ZipOutputStream, logFiles: List<Path>) {
        val buildProperties = runCatching { applicationContext.getBean(BuildProperties::class.java) }.getOrNull()
        val gitProperties = runCatching { applicationContext.getBean(GitProperties::class.java) }.getOrNull()
        val activeProfiles = environment.activeProfiles.takeIf { it.isNotEmpty() }?.joinToString(",") ?: "default"

        val diagnostics = buildString {
            appendLine("deployKit support diagnostics")
            appendLine("exportedAt=${LocalDateTime.now()}")
            appendLine("appVersion=${buildProperties?.version ?: "unknown"}")
            appendLine("gitCommit=${gitProperties?.shortCommitId ?: "unknown"}")
            appendLine("activeProfiles=$activeProfiles")
            appendLine("os=${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
            appendLine("java=${System.getProperty("java.version")}")
            appendLine("userHome=${System.getProperty("user.home")}")
            appendLine("configuredLogFile=${configuredLogFile.ifBlank { "default" }}")
            appendLine("logFileCount=${logFiles.size}")
            appendLine()
            appendLine("includedLogs:")
            logFiles.forEach { path ->
                val size = runCatching { Files.size(path) }.getOrDefault(-1)
                val modified = runCatching { Files.getLastModifiedTime(path).toString() }.getOrDefault("unknown")
                appendLine("- ${path.toAbsolutePath().normalize()} size=$size modified=$modified")
            }
        }

        zip.putNextEntry(ZipEntry("diagnostics.txt"))
        zip.write(diagnostics.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun resolveDownloadsDir(): Path {
        val homeDir = Path.of(System.getProperty("user.home", "."))
        val downloadsDir = homeDir.resolve("Downloads")
        val targetDir = if (Files.exists(downloadsDir) && !Files.isDirectory(downloadsDir)) homeDir else downloadsDir

        Files.createDirectories(targetDir)
        return targetDir
    }

    private fun uniqueDownloadTarget(targetDir: Path, fileName: String): Path {
        val dotIndex = fileName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
        val extension = if (dotIndex > 0) fileName.substring(dotIndex) else ""

        val firstCandidate = targetDir.resolve(fileName)
        if (!Files.exists(firstCandidate)) return firstCandidate

        var index = 2
        var candidate: Path
        do {
            candidate = targetDir.resolve("$baseName-$index$extension")
            index += 1
        } while (Files.exists(candidate))

        return candidate
    }

    companion object {
        private val DOWNLOAD_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
