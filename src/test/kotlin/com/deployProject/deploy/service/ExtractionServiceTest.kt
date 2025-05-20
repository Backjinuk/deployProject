package com.deployProject.deploy.service

import com.deployProject.deploy.domain.extraction.ExtractionDto
import com.deployProject.deploy.domain.extraction.TargetOsStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ExtractionServiceTest {


    @Test
    fun testExtractGitInfo() {
        // Given
        val extractionService = ExtractionService()
        val extractionDto = ExtractionDto().apply {
            siteId = 1L
            since = "2025-05-01"
            until = "2025-05-20"
            fileStatusType = "ALL"
            localPath = "/Users/mac/IdeaProjects/deployProject"
            homePath = "/home/bjw/deployProject"
            targetOs = TargetOsStatus.MAC
        }

        // When
        val result = extractionService.extractGitInfo(extractionDto)

        // Then
        assertNotNull(result)
        assertTrue(result.exists())
    }

}