package com.deployProject.cli.utilCli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class GitUtilArtifactProfileTest {

    @Test
    fun `jvm profile maps java source to class artifact only`() {
        val root = Files.createTempDirectory("git-util-artifact-test").toFile()
        try {
            File(root, "build.gradle").writeText("plugins { id(\"java\") }")
            val source = File(root, "src/main/java/com/example/Foo.java").apply {
                parentFile.mkdirs()
                writeText("class Foo {}")
            }
            File(root, "build/classes/java/main/com/example/Foo.class").apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))
            }

            GitUtil.buildLatestClassMap(root, listOf(source.absolutePath))
            val mapped = GitUtil.mapPathsForExtraction(
                listOf(source.absolutePath),
                GitUtil.ArtifactProfile.JVM_CLASS_ONLY
            )

            // Modified because extraction output must contain bytecode artifacts instead of `.java`.
            assertTrue(mapped.contains("build/classes/java/main/com/example/Foo.class"))
            assertFalse(mapped.any { it.endsWith(".java", ignoreCase = true) })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `artifact profile branch point is jvm when build marker exists otherwise raw`() {
        val jvmRoot = Files.createTempDirectory("git-util-jvm-profile").toFile()
        val rawRoot = Files.createTempDirectory("git-util-raw-profile").toFile()
        try {
            File(jvmRoot, "pom.xml").writeText("<project/>")
            assertEquals(GitUtil.ArtifactProfile.JVM_CLASS_ONLY, GitUtil.resolveArtifactProfile(jvmRoot))
            assertEquals(GitUtil.ArtifactProfile.RAW_FILE_COPY, GitUtil.resolveArtifactProfile(rawRoot))
        } finally {
            jvmRoot.deleteRecursively()
            rawRoot.deleteRecursively()
        }
    }

    @Test
    fun `svn working copy path is normalized relative to repository root`() {
        val normalized = GitUtil.normalizeSvnWorkingCopyRepositoryPath(
            workingCopyUrlPath = "/svn/company/trunk/project",
            repositoryRootUrlPath = "/svn/company"
        )

        assertEquals("trunk/project", normalized)
    }

    @Test
    fun `svn repository log path is normalized relative to working copy`() {
        val normalized = GitUtil.normalizeSvnRepositoryPath(
            rawPath = "/trunk/project/src/main/java/com/example/Foo.java",
            workTreeName = "project",
            workingCopyRepositoryPath = "trunk/project"
        )

        assertEquals("src/main/java/com/example/Foo.java", normalized)
    }

    @Test
    fun `svn repository log path keeps existing working copy relative path`() {
        val normalized = GitUtil.normalizeSvnRepositoryPath(
            rawPath = "src/main/java/com/example/Foo.java",
            workTreeName = "project",
            workingCopyRepositoryPath = "trunk/project"
        )

        assertEquals("src/main/java/com/example/Foo.java", normalized)
    }
}
