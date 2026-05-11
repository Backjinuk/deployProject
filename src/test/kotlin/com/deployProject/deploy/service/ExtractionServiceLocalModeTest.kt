package com.deployProject.deploy.service

import com.deployProject.deploy.domain.extraction.ExtractionDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

class ExtractionServiceLocalModeTest {

    @Test
    fun `local path returns local vcs type and modified files`() {
        val workTree = Files.createTempDirectory("extraction-service-local").toFile()
        try {
            File(workTree, "sample.txt").apply {
                writeText("sample")
                setLastModified(System.currentTimeMillis())
            }

            val dto = ExtractionDto().apply {
                localPath = workTree.absolutePath
                since = LocalDate.now().minusDays(1).toString()
                until = LocalDate.now().plusDays(1).toString()
            }

            val service = ExtractionService()
            val versions = service.listRepositoryVersions(dto)
            val files = service.listVersionFiles(dto)

            assertEquals("LOCAL", versions.vcsType)
            assertTrue(versions.versions.isEmpty())
            assertEquals("LOCAL", files.vcsType)
            assertEquals(listOf("sample.txt"), files.files)
            assertTrue(files.duplicateFiles.isEmpty())
        } finally {
            workTree.deleteRecursively()
        }
    }
}
