package com.deployproject.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.data.util.StreamUtils.zip
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.assertTrue
import kotlin.test.fail

class GitInfoCliTest {

    private val repoGitDir = File(".git")

    @Test
    fun `프로젝트 저장소의 상태 파일은 비어 있지 않아야 한다`(){
        //assertTrue ( repoGitDir.exists()  && repoGitDir.isDirectory, "저장소가 비어있습니다." )
        // Run the CLI against the project repo
        GitInfoCli.main(arrayOf(repoGitDir.absolutePath))

        // The CLI writes a ZIP next to the working tree
        val workTree = repoGitDir.absoluteFile.parentFile!!
        val zipFile = workTree.listFiles()
            ?.firstOrNull { it.name.matches(Regex("git-info-.*\\.zip")) }
            ?: fail("No git-info-*.zip found in project root")

        // Open ZIP and verify status.txt has content
        ZipFile(zipFile).use { zip ->
            val statusEntry = zip.getEntry("status.txt")
                ?: fail("status.txt missing in ZIP")
            val content = zip.getInputStream(statusEntry).bufferedReader().readText().trim()

            assertTrue(content.isNotEmpty(), "Expected non-empty status.txt for project repository")
        }

    }



    @Test
    fun `diff 파일은 README 또는 다른 프로젝트 파일을 포함해야 한다`() {
        // Run CLI again to ensure ZIP exists
        GitInfoCli.main(arrayOf(repoGitDir.absolutePath))

        val workTree = repoGitDir.parentFile!!
        val zipFile = workTree.listFiles()
            ?.firstOrNull { it.name.matches(Regex("git-info-.*\\.zip")) }
            ?: fail("No git-info-*.zip found in project root")

        // Open ZIP and verify diff.txt mentions a known file
        ZipFile(zipFile).use { zip ->
            val diffEntry = zip.getEntry("diff.txt")
                ?: fail("diff.txt missing in ZIP")
            val diffContent = zip.getInputStream(diffEntry).bufferedReader().readText()
            assertTrue(diffContent.contains("README.md") || diffContent.contains("build.gradle.kts"),
                "Expected diff.txt to mention project files such as README.md or build.gradle.kts")
        }
    }

    @Test
    fun `Status 파일만 zip파일로 만들기`() {
        GitInfoCli.main(arrayOf(repoGitDir.absolutePath, "--status"))

        val workTree = repoGitDir.absoluteFile.parentFile!!
        val zipFile = workTree.listFiles()
            ?.firstOrNull { it.name.matches(Regex("git-info-.*\\.zip")) }
            ?: fail("No git-info-*.zip found in project root")

        // Open ZIP and verify status.txt has content
        ZipFile(zipFile).use { zip ->
            val statusEntry = zip.getEntry("status.txt")
                ?: fail("status.txt missing in ZIP")
            val content = zip.getInputStream(statusEntry).bufferedReader().readText().trim()

            assertTrue(content.isNotEmpty(), "Expected non-empty status.txt for project repository")
        }
    }

}