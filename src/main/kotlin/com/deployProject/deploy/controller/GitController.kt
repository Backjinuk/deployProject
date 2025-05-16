package com.deployProject.deploy.controller

import com.deployProject.deploy.domain.extraction.ExtractionDto
import com.deployProject.deploy.service.ExtractionService
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader

@RestController
@RequestMapping("/api/git")
class GitController(
    private val extractionService: ExtractionService
) {

    @RequestMapping("/extraction")
    fun extractGitInfo(@RequestBody extractionDto: ExtractionDto) : ResponseEntity<InputStreamResource?> {
       val jarFile : File =  extractionService.extractGitInfo(extractionDto)

        println("jarFile.name = ${jarFile.name}")

       val resource = InputStreamResource(FileInputStream(jarFile))

        return ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=${jarFile.name}"
            )
            .contentLength(jarFile.length())
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(resource)

    }

}