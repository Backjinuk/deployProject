package com.deployProject.deploy.controller

import com.deployProject.deploy.domain.extraction.ExtractionDto
import com.deployProject.deploy.service.ExtractionService
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/git")
class GitController(
    private val extractionService: ExtractionService
) {

    @RequestMapping("/extraction")
    fun extractGitInfo(@RequestBody extractionDto: ExtractionDto){




    }
}