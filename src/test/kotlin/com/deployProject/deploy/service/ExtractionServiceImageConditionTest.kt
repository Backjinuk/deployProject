package com.deployProject.deploy.service

import com.deployProject.deploy.domain.extraction.ExtractionDto
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.TimeZone

class ExtractionServiceImageConditionTest {

    @TempDir
    lateinit var tempDir: Path

    private val service = ExtractionService()
    private val dateTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @Test
    fun `image date range returns only versions in 2026-04-16 to 2026-04-23`() {
        val repoDir = tempDir.resolve("image-range-repo").toFile()
        val versions = prepareImageLikeGitHistory(repoDir)

        val dto = ExtractionDto().apply {
            localPath = repoDir.absolutePath
            since = "2026-04-16"
            until = "2026-04-23"
        }

        val result = service.listRepositoryVersions(dto)
        val actualVersionIds = result.versions.map { it.value }.toSet()

        // 수정 이유: 이미지 조건(기간 필터)에 맞게 범위 내 버전만 선택 목록에 노출되는지 검증한다.
        assertEquals("GIT", result.vcsType)
        assertTrue(actualVersionIds.contains(versions.latestInRange))
        assertTrue(actualVersionIds.contains(versions.oldInRange))
        assertFalse(actualVersionIds.contains(versions.outOfRange))
    }

    @Test
    fun `image selected versions build duplicate file list for bulk version select`() {
        val repoDir = tempDir.resolve("image-duplicate-repo").toFile()
        val versions = prepareImageLikeGitHistory(repoDir)

        val dto = ExtractionDto().apply {
            localPath = repoDir.absolutePath
            // 이미지처럼 다중 버전을 체크한 상태를 재현한다.
            selectedVersions = listOf(versions.latestInRange, versions.oldInRange)
        }

        val result = service.listVersionFiles(dto)

        val duplicatePathSet = result.duplicateFiles.map { it.path }.toSet()
        val dnaDuplicate = result.duplicateFiles.first { it.path == versions.dnaPath }
        val miaDuplicate = result.duplicateFiles.first { it.path == versions.missingChildPath }

        // 수정 이유: UI의 "중복 파일 버전 선택" 섹션이 정상 동작하려면 중복 파일/버전 옵션이 정확히 생성되어야 한다.
        assertEquals("GIT", result.vcsType)
        assertTrue(result.files.contains(versions.dnaPath))
        assertTrue(result.files.contains(versions.missingChildPath))
        assertTrue(result.files.contains(versions.policePath))
        assertTrue(duplicatePathSet.contains(versions.dnaPath))
        assertTrue(duplicatePathSet.contains(versions.missingChildPath))
        assertEquals(listOf(versions.latestInRange, versions.oldInRange), dnaDuplicate.versions.map { it.value })
        assertEquals(listOf(versions.latestInRange, versions.oldInRange), miaDuplicate.versions.map { it.value })
    }

    private fun prepareImageLikeGitHistory(repoDir: File): ImageScenarioVersions {
        repoDir.mkdirs()
        Git.init().setDirectory(repoDir).call().use { git ->
            val dnaPath = "src/main/webapp/WEB-INF/jsp/web/home/admin/dna/dna1.jsp"
            val missingChildPath = "src/main/webapp/WEB-INF/jsp/web/home/admin/missingChild/miamia15.jsp"
            val policePath = "src/main/webapp/WEB-INF/jsp/web/home/admin/police/police1.jsp"

            val oldInRange = commitFiles(
                git = git,
                repoDir = repoDir,
                files = mapOf(
                    dnaPath to "old dna",
                    missingChildPath to "old missing child"
                ),
                authoredAt = "2026-04-16 16:15:52",
                message = "r583 | bjw - PMS[48864] 신상카드, DNA 검색 조건 추가",
                userId = "백진욱"
            )

            val latestInRange = commitFiles(
                git = git,
                repoDir = repoDir,
                files = mapOf(
                    dnaPath to "new dna condition",
                    missingChildPath to "new missing child condition",
                    policePath to "police search update"
                ),
                authoredAt = "2026-04-21 17:16:22",
                message = "r586 | bjw - 관리자 페이지 검색 조건 값 추가",
                userId = "백진욱"
            )

            val outOfRange = commitFile(
                git = git,
                repoDir = repoDir,
                path = "src/main/webapp/WEB-INF/jsp/web/home/admin/dna/dna9.jsp",
                content = "outside range",
                authoredAt = "2026-04-24 09:00:00",
                message = "r590 | bjw - 기간 외 커밋",
                userId = "백진욱"
            )

            return ImageScenarioVersions(
                oldInRange = oldInRange,
                latestInRange = latestInRange,
                outOfRange = outOfRange,
                dnaPath = dnaPath,
                missingChildPath = missingChildPath,
                policePath = policePath
            )
        }
    }

    private fun commitFile(
        git: Git,
        repoDir: File,
        path: String,
        content: String,
        authoredAt: String,
        message: String,
        userId: String
    ): String {
        val file = File(repoDir, path)
        file.parentFile?.mkdirs()
        file.writeText(content)

        git.add().addFilepattern(path.replace("\\", "/")).call()

        val commitDate = parseDate(authoredAt)
        val timeZone = TimeZone.getTimeZone(ZoneId.systemDefault())
        val ident = PersonIdent(userId, "bjw@example.com", commitDate, timeZone)

        return git.commit()
            .setMessage(message)
            .setAuthor(ident)
            .setCommitter(ident)
            .call()
            .name
    }

    private fun commitFiles(
        git: Git,
        repoDir: File,
        files: Map<String, String>,
        authoredAt: String,
        message: String,
        userId: String
    ): String {
        files.forEach { (path, content) ->
            val file = File(repoDir, path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            git.add().addFilepattern(path.replace("\\", "/")).call()
        }

        val commitDate = parseDate(authoredAt)
        val timeZone = TimeZone.getTimeZone(ZoneId.systemDefault())
        val ident = PersonIdent(userId, "bjw@example.com", commitDate, timeZone)

        return git.commit()
            .setMessage(message)
            .setAuthor(ident)
            .setCommitter(ident)
            .call()
            .name
    }

    private fun parseDate(text: String): Date {
        val localDateTime = LocalDateTime.parse(text, dateTimeFmt)
        return Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant())
    }

    private data class ImageScenarioVersions(
        val oldInRange: String,
        val latestInRange: String,
        val outOfRange: String,
        val dnaPath: String,
        val missingChildPath: String,
        val policePath: String
    )
}
