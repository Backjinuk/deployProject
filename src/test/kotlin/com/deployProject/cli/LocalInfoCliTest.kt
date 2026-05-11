package com.deployProject.cli

import com.deployProject.cli.infoCli.LocalInfoCli
import com.deployProject.deploy.domain.site.FileStatusType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

class LocalInfoCliTest {

    @Test
    fun `local extraction writes selected modified file`() {
        val userHome = System.getProperty("user.home")
        val headless = System.getProperty("java.awt.headless")
        val tempHome = Files.createTempDirectory("local-info-cli-home").toFile()
        val workTree = Files.createTempDirectory("local-info-cli-worktree").toFile()

        try {
            System.setProperty("user.home", tempHome.absolutePath)
            System.setProperty("java.awt.headless", "true")

            val desktopDir = File(tempHome, "Desktop").apply { mkdirs() }
            val targetFile = File(workTree, "sample.txt").apply {
                writeText("local-version")
                setLastModified(System.currentTimeMillis())
            }

            LocalInfoCli().localCliExecution(
                repoPath = workTree.absolutePath,
                since = LocalDate.now().minusDays(1),
                until = LocalDate.now().plusDays(1),
                fileStatusType = FileStatusType.STATUS,
                deployServerDir = "/tmp/deploy",
                jdkPath = null,
                selectedFiles = listOf("sample.txt")
            )

            val extractedDir = desktopDir.listFiles()
                ?.filter { it.isDirectory }
                ?.maxByOrNull { it.lastModified() }
                ?: error("No extraction directory created")

            assertEquals("local-version", File(extractedDir, "sample.txt").readText())
        } finally {
            if (userHome != null) System.setProperty("user.home", userHome) else System.clearProperty("user.home")
            if (headless != null) System.setProperty("java.awt.headless", headless) else System.clearProperty("java.awt.headless")
            tempHome.deleteRecursively()
            workTree.deleteRecursively()
        }
    }
}
