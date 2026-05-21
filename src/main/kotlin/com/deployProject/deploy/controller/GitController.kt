package com.deployProject.deploy.controller

import com.deployProject.deploy.domain.extraction.ExtractionDto
import com.deployProject.deploy.domain.extraction.RepositoryVersionFileListDto
import com.deployProject.deploy.domain.extraction.RepositoryVersionListDto
import com.deployProject.deploy.service.ExtractionService
import org.slf4j.LoggerFactory
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class ExtractionSaveResponse(
    val fileName: String,
    val path: String,
    val size: Long
)

@RestController
@RequestMapping("/api/git")
class GitController(
    private val extractionService: ExtractionService
) {
    private val logger = LoggerFactory.getLogger(GitController::class.java)

    @PostMapping("/versions")
    fun listVersions(@RequestBody dto: ExtractionDto): RepositoryVersionListDto {
        return extractionService.listRepositoryVersions(dto)
    }

    @PostMapping("/version-files")
    fun listVersionFiles(@RequestBody dto: ExtractionDto): RepositoryVersionFileListDto {
        // 수정 이유: 선택한 버전들의 변경 파일 목록을 먼저 보여주고 파일 단위 체크 추출을 지원하기 위함.
        return extractionService.listVersionFiles(dto)
    }

    @PostMapping("/extraction")
    fun extractGitInfoStream(@RequestBody dto: ExtractionDto): ResponseEntity<StreamingResponseBody> {
        val zipFile: File = extractionService.extractGitInfo(dto)
        val downloadFileName = packageFileName(dto)

        val streamBody = StreamingResponseBody { outputStream ->
            try {
                FileInputStream(zipFile).use { fis ->
                    val buffer = ByteArray(64 * 1024)
                    var read = fis.read(buffer)
                    while (read != -1) {
                        outputStream.write(buffer, 0, read)
                        read = fis.read(buffer)
                    }
                    outputStream.flush()
                }
            } catch (error: IOException) {
                logger.warn("Deploy package download connection closed before completion: {}", zipFile.name)
            } finally {
                // 수정 이유: 사용자 다운로드가 끝난 뒤 서버 임시 산출물을 즉시 정리해 디스크 누적을 막는다.
                extractionService.cleanupExtractionArtifacts(zipFile)
            }
        }

        return ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(downloadFileName, Charsets.UTF_8).build().toString()
            )
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(zipFile.length())
            .body(streamBody)
    }

    @PostMapping("/extraction/save")
    fun extractGitInfoToDownloads(@RequestBody dto: ExtractionDto): ExtractionSaveResponse {
        val zipFile: File = extractionService.extractGitInfo(dto)

        return try {
            val savedFile = copyToDownloads(zipFile, packageFileName(dto))
            logger.info("Deploy package saved to local downloads: {}", savedFile.toAbsolutePath())

            ExtractionSaveResponse(
                fileName = savedFile.fileName.toString(),
                path = savedFile.toAbsolutePath().normalize().toString(),
                size = Files.size(savedFile)
            )
        } finally {
            extractionService.cleanupExtractionArtifacts(zipFile)
        }
    }

    private fun copyToDownloads(source: File, fileName: String): Path {
        val homeDir = Path.of(System.getProperty("user.home", "."))
        val downloadsDir = homeDir.resolve("Downloads")
        val targetDir = if (Files.exists(downloadsDir) && !Files.isDirectory(downloadsDir)) homeDir else downloadsDir

        Files.createDirectories(targetDir)

        val target = uniqueDownloadTarget(targetDir, fileName)
        Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING)

        return target
    }

    private fun packageFileName(dto: ExtractionDto): String {
        val siteName = dto.siteName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.replace(INVALID_FILE_NAME_CHARS, "_")
            ?.replace(WHITESPACE_CHARS, "_")
            ?.trim('_', '.', ' ')
            ?.takeIf { it.isNotEmpty() }
            ?: "deploy-package"
        val timestamp = PACKAGE_TIMESTAMP_FORMATTER.format(LocalDateTime.now())

        return "${siteName}_$timestamp.zip"
    }

    private fun uniqueDownloadTarget(targetDir: Path, fileName: String): Path {
        val dotIndex = fileName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
        val extension = if (dotIndex > 0) fileName.substring(dotIndex) else ""

        val firstCandidate = targetDir.resolve(fileName)
        if (!Files.exists(firstCandidate)) return firstCandidate

        val timestamp = DOWNLOAD_TIMESTAMP_FORMATTER.format(LocalDateTime.now())
        var index = 1
        var candidate: Path

        do {
            val suffix = if (index == 1) timestamp else "$timestamp-$index"
            candidate = targetDir.resolve("$baseName-$suffix$extension")
            index += 1
        } while (Files.exists(candidate))

        return candidate
    }

    companion object {
        private val DOWNLOAD_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val PACKAGE_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
        private val INVALID_FILE_NAME_CHARS = Regex("""[\\/:*?"<>|]""")
        private val WHITESPACE_CHARS = Regex("""\s+""")
    }
}
