package com.deployProject.deploy.service

import com.deployProject.deploy.domain.extraction.ExtractionDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

class ExtractionServiceTest {

    @Test
    fun `print extract target files with image conditions without zip`() {
        val extractionService = ExtractionService()
        val repoPath = System.getProperty("test.extraction.repoPath", "D:/DevSpace/FGI_Space/0009_missingchild")
        assumeTrue(
            File(repoPath).exists(),
            "Skip: set -Dtest.extraction.repoPath to your local Git/SVN working copy path"
        )

        val versionQuery = ExtractionDto().apply {
            since = "2026-04-16"
            until = "2026-04-23"
            localPath = repoPath
        }

        val versionList = extractionService.listRepositoryVersions(versionQuery)
        val selectedVersions = versionList.versions
            .filter { option ->
                option.value == "586" ||
                    option.value == "583" ||
                    option.label.contains("r586") ||
                    option.label.contains("r583")
            }
            .map { it.value }
            .ifEmpty { versionList.versions.take(2).map { it.value } }

        assertTrue(selectedVersions.isNotEmpty())

        val filesQuery = ExtractionDto().apply {
            localPath = repoPath
            this.selectedVersions = selectedVersions
        }
        val fileList = extractionService.listVersionFiles(filesQuery)

        // 수정 이유: ZIP 생성 없이 어떤 파일이 추출 대상인지 바로 확인한다.
        println("=== Extract Target Files (${fileList.vcsType}) ===")
        fileList.files.sorted().forEachIndexed { idx, path ->
            println("${idx + 1}. $path")
        }
        println("=== Duplicate Files ===")
        fileList.duplicateFiles.forEach { dup ->
            val versions = dup.versions.joinToString(", ") { it.value }
            println("${dup.path} -> [$versions]")
        }

        assertTrue(fileList.files.isNotEmpty())
        assertEquals(fileList.files.size, fileList.files.distinct().size)
    }

    @Test
    fun `cleanupExtractionArtifacts deletes only managed temp workdir`() {
        val extractionService = ExtractionService()
        val root = File("GitInfoJarFile")
        val workDir = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        val zipFile = File(workDir, "bundle-windows.zip").apply { writeText("dummy") }

        extractionService.cleanupExtractionArtifacts(zipFile)

        // 수정 이유: 다운로드 완료 후 서버 임시 산출물이 누적되지 않도록 정리 동작을 보장한다.
        assertFalse(workDir.exists())
    }

    @Test
    fun `cleanupExtractionArtifacts does not delete unmanaged directory`() {
        val extractionService = ExtractionService()
        val unmanagedRoot = File(System.getProperty("java.io.tmpdir"), "deploy-project-unmanaged-test")
            .apply { mkdirs() }
        val unmanagedDir = File(unmanagedRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        val zipFile = File(unmanagedDir, "bundle-windows.zip").apply { writeText("dummy") }

        extractionService.cleanupExtractionArtifacts(zipFile)

        // 수정 이유: 서비스가 관리하는 루트가 아닌 경로는 삭제하면 안 된다.
        assertTrue(unmanagedDir.exists())
        unmanagedRoot.deleteRecursively()
    }
}
