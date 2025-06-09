package com.deployProject.deploy.controller

import com.deployProject.deploy.domain.extraction.ExtractionDto
import com.deployProject.deploy.service.ExtractionService
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.concurrent.Callable

@RestController
@RequestMapping("/api/git")
class GitController(
    private val extractionService: ExtractionService
) {

    @PostMapping("/extraction")
    fun extractGitInfoStream(@RequestBody dto: ExtractionDto): ResponseEntity<StreamingResponseBody> {
        // 1) 압축 파일 생성 (긴 시간)
        val jarFile: File = extractionService.extractGitInfo(dto)

        // 2) 파일을 청크 단위로 내려보내는 StreamingResponseBody
        val streamBody = StreamingResponseBody { outputStream ->
            FileInputStream(jarFile).use { fis ->
                val buffer = ByteArray(8 * 1024) // 8KB
                var read = fis.read(buffer)
                while (read != -1) {
                    outputStream.write(buffer, 0, read)
                    outputStream.flush()
                    read = fis.read(buffer)
                }
            }
        }

        // 3) 헤더만 빨리 보내기
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${jarFile.name}\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(jarFile.length())
            .body(streamBody)
    }

}