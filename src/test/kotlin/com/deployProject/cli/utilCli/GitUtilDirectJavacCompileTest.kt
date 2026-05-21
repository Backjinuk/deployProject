package com.deployProject.cli.utilCli

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class GitUtilDirectJavacCompileTest {

    @Test
    fun `configured jdk path compiles java project without build tool execution`() {
        val projectDir = Files.createTempDirectory("git-util-direct-javac").toFile()
        try {
            val javaHome = File(System.getProperty("java.home"))
            val javac = File(javaHome, if (isWindows()) "bin/javac.exe" else "bin/javac")
            assumeTrue(javac.isFile, "This test requires a JDK with javac")

            val currentJava = ProjectJavaInspector.inspectVersionToken(System.getProperty("java.version")) ?: 17
            File(projectDir, "pom.xml").writeText(
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId>
                  <artifactId>direct-javac</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.source>$currentJava</maven.compiler.source>
                    <maven.compiler.target>$currentJava</maven.compiler.target>
                  </properties>
                </project>
                """.trimIndent()
            )

            File(projectDir, "src/main/java/example/App.java").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    package example;

                    public class App {
                        public String value() {
                            return "ok";
                        }
                    }
                    """.trimIndent()
                )
            }

            GitUtil.compileJvmProject(projectDir, javaHome.absolutePath)

            assertTrue(
                File(projectDir, "target/classes/example/App.class").isFile,
                "Direct javac compilation should create target/classes/example/App.class"
            )
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun `configured jdk path compiles revision snapshot without build metadata`() {
        val projectDir = Files.createTempDirectory("git-util-direct-javac-no-build-file").toFile()
        try {
            val javaHome = File(System.getProperty("java.home"))
            val javac = File(javaHome, if (isWindows()) "bin/javac.exe" else "bin/javac")
            assumeTrue(javac.isFile, "This test requires a JDK with javac")

            File(projectDir, "src/main/java/example/SnapshotApp.java").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    package example;

                    public class SnapshotApp {
                        public String value() {
                            return "snapshot";
                        }
                    }
                    """.trimIndent()
                )
            }

            GitUtil.compileJvmProject(projectDir, javaHome.absolutePath)

            assertTrue(
                File(projectDir, "target/classes/example/SnapshotApp.class").isFile,
                "Direct javac compilation should use the configured JDK when build metadata is missing"
            )
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun `configured jdk path compiles selected java sources only`() {
        val projectDir = Files.createTempDirectory("git-util-direct-javac-selected").toFile()
        try {
            val javaHome = File(System.getProperty("java.home"))
            val javac = File(javaHome, if (isWindows()) "bin/javac.exe" else "bin/javac")
            assumeTrue(javac.isFile, "This test requires a JDK with javac")

            File(projectDir, "src/main/java/example/SelectedApp.java").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    package example;

                    public class SelectedApp {
                        public String value() {
                            return "selected";
                        }
                    }
                    """.trimIndent()
                )
            }

            File(projectDir, "src/main/java/example/UnselectedApp.java").writeText(
                """
                package example;

                public class UnselectedApp {
                    public String value() {
                        return "unselected";
                    }
                }
                """.trimIndent()
            )

            GitUtil.compileJvmProject(
                projectDir,
                javaHome.absolutePath,
                listOf("src/main/java/example/SelectedApp.java")
            )

            assertTrue(
                File(projectDir, "target/classes/example/SelectedApp.class").isFile,
                "Direct javac compilation should create the selected class"
            )
            assertFalse(
                File(projectDir, "target/classes/example/UnselectedApp.class").exists(),
                "Direct javac compilation should not compile unrelated Java sources"
            )
        } finally {
            projectDir.deleteRecursively()
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")
}
