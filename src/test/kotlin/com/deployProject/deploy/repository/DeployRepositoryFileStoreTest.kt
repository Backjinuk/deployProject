package com.deployProject.deploy.repository

import com.deployProject.deploy.domain.site.SiteDto
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DeployRepositoryFileStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private val objectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())

    @Test
    fun `savedPath stores local site without user`() {
        val repository = DeployRepositoryImpl(objectMapper, tempDir.resolve("sites.json").toString())

        repository.savedPath(
            SiteDto().apply {
                text = "local app"
                homePath = "/server/app"
                localPath = "D:/workspace/app"
                jdkPath = "C:/Java/jdk-17"
            }
        )

        val sites = repository.getSites()
        assertEquals(1, sites.size)
        assertEquals(1L, sites.first().id)
        assertTrue(tempDir.resolve("sites.json").toFile().isFile)
    }

    @Test
    fun `existing file with legacy userSeq can be read`() {
        val sitesFile = tempDir.resolve("sites.json")
        Files.writeString(
            sitesFile,
            """
            {
              "nextId": 2,
              "sites": [
                {
                  "id": 1,
                  "text": "legacy",
                  "userSeq": 99,
                  "homePath": "/server/app",
                  "localPath": "D:/workspace/app",
                  "createdAt": "2026-05-20T10:00:00",
                  "useYn": "Y"
                }
              ]
            }
            """.trimIndent()
        )

        val repository = DeployRepositoryImpl(objectMapper, sitesFile.toString())

        assertEquals("legacy", repository.getSites().first().text)
    }

    @Test
    fun `savedPath updates duplicate path instead of appending`() {
        val repository = DeployRepositoryImpl(objectMapper, tempDir.resolve("sites.json").toString())
        val firstSite = SiteDto().apply {
            text = "same app"
            homePath = "/server/app"
            localPath = "D:/workspace/app"
            jdkPath = "C:/Java/jdk-17"
        }
        val duplicateSite = SiteDto().apply {
            text = " same app "
            homePath = "/server/app/"
            localPath = "D:\\workspace\\app"
            jdkPath = "C:/Java/jdk-21"
        }

        repository.savedPath(firstSite)
        repository.savedPath(duplicateSite)

        val sites = repository.getSites()
        assertEquals(1, sites.size)
        assertEquals(1L, sites.first().id)
        assertEquals("C:/Java/jdk-21", sites.first().jdkPath)
    }

    @Test
    fun `updatePath updates and delete hides site`() {
        val repository = DeployRepositoryImpl(objectMapper, tempDir.resolve("sites.json").toString())
        repository.savedPath(
            SiteDto().apply {
                text = "before"
                homePath = "/server/app"
                localPath = "D:/workspace/app"
            }
        )

        repository.updatePath(
            SiteDto().apply {
                id = 1L
                text = "after"
            }
        )

        assertEquals("after", repository.getSites().first().text)

        repository.updatePath(
            SiteDto().apply {
                id = 1L
                useYn = "N"
            }
        )

        assertTrue(repository.getSites().isEmpty())
    }
}
