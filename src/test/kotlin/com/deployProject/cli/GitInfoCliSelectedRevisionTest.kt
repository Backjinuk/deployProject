package com.deployProject.cli

import com.deployProject.cli.infoCli.GitInfoCli
import com.deployProject.cli.utilCli.JarCreator
import com.deployProject.deploy.domain.site.FileStatusType
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

class GitInfoCliSelectedRevisionTest {

    @Test
    fun `diff extraction writes selected raw file revision`() {
        val userHome = System.getProperty("user.home")
        val headless = System.getProperty("java.awt.headless")
        val tempHome = Files.createTempDirectory("git-info-cli-home").toFile()
        val repoDir = Files.createTempDirectory("git-info-cli-repo").toFile()

        try {
            System.setProperty("user.home", tempHome.absolutePath)
            System.setProperty("java.awt.headless", "true")

            val desktopDir = File(tempHome, "Desktop").apply { mkdirs() }
            val targetFile = File(repoDir, "sample.txt")

            Git.init().setDirectory(repoDir).call().use { git ->
                git.repository.config.apply {
                    setString("user", null, "name", "tester")
                    setString("user", null, "email", "tester@example.com")
                    save()
                }

                targetFile.writeText("version-1")
                git.add().addFilepattern("sample.txt").call()
                val firstCommit = git.commit().setMessage("first").call()

                targetFile.writeText("version-2")
                git.add().addFilepattern("sample.txt").call()
                val secondCommit = git.commit().setMessage("second").call()

                GitInfoCli().gitCliExecution(
                    repoPath = repoDir.absolutePath,
                    since = LocalDate.now().minusDays(1),
                    until = LocalDate.now().plusDays(1),
                    fileStatusType = FileStatusType.DIFF,
                    deployServerDir = "/tmp/deploy",
                    jdkPath = null,
                    sinceVersion = null,
                    untilVersion = null,
                    selectedVersions = listOf(secondCommit.name, firstCommit.name),
                    selectedFiles = listOf("sample.txt"),
                    duplicateFileVersionMap = mapOf("sample.txt" to firstCommit.name)
                )
            }

            val extractedDir = desktopDir.listFiles()
                ?.filter { it.isDirectory }
                ?.maxByOrNull { it.lastModified() }
                ?: error("No extraction directory created")

            val extractedFile = File(extractedDir, "sample.txt")
            assertEquals("version-1", extractedFile.readText())
        } finally {
            if (userHome != null) System.setProperty("user.home", userHome) else System.clearProperty("user.home")
            if (headless != null) System.setProperty("java.awt.headless", headless) else System.clearProperty("java.awt.headless")
            tempHome.deleteRecursively()
            repoDir.deleteRecursively()
        }
    }

    @Test
    fun `packaged cli jar writes selected raw file revision`() {
        val userHome = System.getProperty("user.home")
        val headless = System.getProperty("java.awt.headless")
        val tempHome = Files.createTempDirectory("git-info-cli-jar-home").toFile()
        val repoDir = Files.createTempDirectory("git-info-cli-jar-repo").toFile()
        val jarOutputDir = Files.createTempDirectory("git-info-cli-jar-out").toFile()

        try {
            System.setProperty("user.home", tempHome.absolutePath)
            System.setProperty("java.awt.headless", "true")

            val desktopDir = File(tempHome, "Desktop").apply { mkdirs() }
            val targetFile = File(repoDir, "sample.txt")

            Git.init().setDirectory(repoDir).call().use { git ->
                git.repository.config.apply {
                    setString("user", null, "name", "tester")
                    setString("user", null, "email", "tester@example.com")
                    save()
                }

                targetFile.writeText("version-1")
                git.add().addFilepattern("sample.txt").call()
                val firstCommit = git.commit().setMessage("first").call()

                targetFile.writeText("version-2")
                git.add().addFilepattern("sample.txt").call()
                val secondCommit = git.commit().setMessage("second").call()

                JarCreator.main(
                    arrayOf(
                        repoDir.absolutePath,
                        "",
                        LocalDate.now().minusDays(1).toString(),
                        LocalDate.now().plusDays(1).toString(),
                        "DIFF",
                        jarOutputDir.absolutePath,
                        "/tmp/deploy",
                        "",
                        "",
                        listOf(secondCommit.name, firstCommit.name).joinToString("|~|"),
                        "sample.txt",
                        "sample.txt::${firstCommit.name}"
                    )
                )
            }

            val javaBin = File(System.getProperty("java.home"), "bin/java.exe")
                .takeIf { it.exists() }
                ?: File(System.getProperty("java.home"), "bin/java")
            val jarFile = File(jarOutputDir, "deploy-project-cli.jar")

            val process = ProcessBuilder(
                javaBin.absolutePath,
                "-Duser.home=${tempHome.absolutePath}",
                "-Djava.awt.headless=true",
                "-jar",
                jarFile.absolutePath
            )
                .directory(jarOutputDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            check(exitCode == 0) { "packaged cli failed (exit=$exitCode)\n$output" }

            val extractedDir = desktopDir.listFiles()
                ?.filter { it.isDirectory }
                ?.maxByOrNull { it.lastModified() }
                ?: error("No extraction directory created by packaged cli")

            val extractedFile = File(extractedDir, "sample.txt")
            assertEquals("version-1", extractedFile.readText())
        } finally {
            if (userHome != null) System.setProperty("user.home", userHome) else System.clearProperty("user.home")
            if (headless != null) System.setProperty("java.awt.headless", headless) else System.clearProperty("java.awt.headless")
            tempHome.deleteRecursively()
            repoDir.deleteRecursively()
            jarOutputDir.deleteRecursively()
        }
    }

    @Test
    fun `all extraction keeps selected diff raw file when status overlaps`() {
        val userHome = System.getProperty("user.home")
        val headless = System.getProperty("java.awt.headless")
        val tempHome = Files.createTempDirectory("git-info-cli-all-home").toFile()
        val repoDir = Files.createTempDirectory("git-info-cli-all-repo").toFile()

        try {
            System.setProperty("user.home", tempHome.absolutePath)
            System.setProperty("java.awt.headless", "true")

            val desktopDir = File(tempHome, "Desktop").apply { mkdirs() }
            val targetFile = File(repoDir, "sample.txt")

            Git.init().setDirectory(repoDir).call().use { git ->
                git.repository.config.apply {
                    setString("user", null, "name", "tester")
                    setString("user", null, "email", "tester@example.com")
                    save()
                }

                targetFile.writeText("version-1")
                git.add().addFilepattern("sample.txt").call()
                val firstCommit = git.commit().setMessage("first").call()

                targetFile.writeText("version-2")
                git.add().addFilepattern("sample.txt").call()
                val secondCommit = git.commit().setMessage("second").call()

                // Leave a working tree change so STATUS and DIFF both target the same output path.
                targetFile.writeText("working-copy-version")

                GitInfoCli().gitCliExecution(
                    repoPath = repoDir.absolutePath,
                    since = LocalDate.now().minusDays(1),
                    until = LocalDate.now().plusDays(1),
                    fileStatusType = FileStatusType.ALL,
                    deployServerDir = "/tmp/deploy",
                    jdkPath = null,
                    sinceVersion = null,
                    untilVersion = null,
                    selectedVersions = listOf(secondCommit.name, firstCommit.name),
                    selectedFiles = listOf("sample.txt"),
                    duplicateFileVersionMap = mapOf("sample.txt" to firstCommit.name)
                )
            }

            val extractedDir = desktopDir.listFiles()
                ?.filter { it.isDirectory }
                ?.maxByOrNull { it.lastModified() }
                ?: error("No extraction directory created")

            val extractedFile = File(extractedDir, "sample.txt")
            assertEquals("version-1", extractedFile.readText())
        } finally {
            if (userHome != null) System.setProperty("user.home", userHome) else System.clearProperty("user.home")
            if (headless != null) System.setProperty("java.awt.headless", headless) else System.clearProperty("java.awt.headless")
            tempHome.deleteRecursively()
            repoDir.deleteRecursively()
        }
    }

    @Test
    fun `jvm project extraction writes selected non-class file revision`() {
        val userHome = System.getProperty("user.home")
        val headless = System.getProperty("java.awt.headless")
        val tempHome = Files.createTempDirectory("git-info-cli-jvm-home").toFile()
        val repoDir = Files.createTempDirectory("git-info-cli-jvm-repo").toFile()

        try {
            System.setProperty("user.home", tempHome.absolutePath)
            System.setProperty("java.awt.headless", "true")

            val desktopDir = File(tempHome, "Desktop").apply { mkdirs() }
            File(repoDir, "build.gradle").writeText("plugins { id 'java' }")
            val targetFile = File(repoDir, "src/main/webapp/index.jsp").apply { parentFile.mkdirs() }

            Git.init().setDirectory(repoDir).call().use { git ->
                git.repository.config.apply {
                    setString("user", null, "name", "tester")
                    setString("user", null, "email", "tester@example.com")
                    save()
                }

                targetFile.writeText("jsp-version-1")
                git.add().addFilepattern("build.gradle").call()
                git.add().addFilepattern("src/main/webapp/index.jsp").call()
                val firstCommit = git.commit().setMessage("first").call()

                targetFile.writeText("jsp-version-2")
                git.add().addFilepattern("src/main/webapp/index.jsp").call()
                val secondCommit = git.commit().setMessage("second").call()

                GitInfoCli().gitCliExecution(
                    repoPath = repoDir.absolutePath,
                    since = LocalDate.now().minusDays(1),
                    until = LocalDate.now().plusDays(1),
                    fileStatusType = FileStatusType.DIFF,
                    deployServerDir = "/tmp/deploy",
                    jdkPath = null,
                    sinceVersion = null,
                    untilVersion = null,
                    selectedVersions = listOf(secondCommit.name, firstCommit.name),
                    selectedFiles = listOf("src/main/webapp/index.jsp"),
                    duplicateFileVersionMap = mapOf("src/main/webapp/index.jsp" to firstCommit.name)
                )
            }

            val extractedDir = desktopDir.listFiles()
                ?.filter { it.isDirectory }
                ?.maxByOrNull { it.lastModified() }
                ?: error("No extraction directory created")

            val extractedFile = File(extractedDir, "src/main/webapp/index.jsp")
            assertEquals("jsp-version-1", extractedFile.readText())
        } finally {
            if (userHome != null) System.setProperty("user.home", userHome) else System.clearProperty("user.home")
            if (headless != null) System.setProperty("java.awt.headless", headless) else System.clearProperty("java.awt.headless")
            tempHome.deleteRecursively()
            repoDir.deleteRecursively()
        }
    }
}
