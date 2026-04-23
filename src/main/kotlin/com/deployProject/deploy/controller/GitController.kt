package com.deployProject.deploy.controller

import com.deployProject.deploy.domain.extraction.ExtractionDto
import com.deployProject.deploy.domain.extraction.RepositoryVersionFileListDto
import com.deployProject.deploy.domain.extraction.RepositoryVersionListDto
import com.deployProject.deploy.service.ExtractionService
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

@RestController
@RequestMapping("/api/git")
class GitController(
    private val extractionService: ExtractionService
) {

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

        val streamBody = StreamingResponseBody { outputStream ->
            try {
                FileInputStream(zipFile).use { fis ->
                    val buffer = ByteArray(8 * 1024)
                    var read = fis.read(buffer)
                    while (read != -1) {
                        outputStream.write(buffer, 0, read)
                        outputStream.flush()
                        read = fis.read(buffer)
                    }
                }
            } finally {
                // 수정 이유: 사용자 다운로드가 끝난 뒤 서버 임시 산출물을 즉시 정리해 디스크 누적을 막는다.
                extractionService.cleanupExtractionArtifacts(zipFile)
            }
        }

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${zipFile.name}\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(zipFile.length())
            .body(streamBody)
    }
}
