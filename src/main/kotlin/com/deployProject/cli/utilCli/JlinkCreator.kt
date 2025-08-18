package com.deployProject.cli.utilCli

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

object JlinkCreator {
    private val logger = org.slf4j.LoggerFactory.getLogger(JlinkCreator::class.java)

    fun createJlink(modules: List<String>, outputDir: Path, javaHome: Path) {
        val jlinkBin = javaHome.resolve("bin").resolve(if (isWindows()) "jlink.exe" else "jlink")
        require(Files.isExecutable(jlinkBin)) { "유효한 jlink 실행 파일을 찾을 수 없습니다: $jlinkBin" }

        val jmodsDir = resolveJmods(javaHome)
        logger.info("javaHome = {}", javaHome)
        logger.info("jlinkBin = {} (exists={})", jlinkBin, Files.exists(jlinkBin))
        logger.info("jmodsDir = {} (exists={})", jmodsDir, Files.isDirectory(jmodsDir))

        // 1) 출력 경로가 이미 있으면 삭제 (완전 삭제 확인)
        if (Files.exists(outputDir)) {
            logger.info("기존 custom JRE 디렉터리를 삭제합니다: {}", outputDir)
            outputDir.toFile().deleteRecursively()
            if (Files.exists(outputDir)) {
                throw IllegalStateException("기존 custom JRE 디렉터리를 삭제하지 못했습니다: $outputDir")
            }
        }

        // 2) jlink 요구사항: --output 경로는 존재하면 안 됨. (부모만 만들어 두기)
        val parent = outputDir.parent
        if (parent != null && !Files.exists(parent)) Files.createDirectories(parent)

        val cmd = listOf(
            jlinkBin.toString(),
            "--module-path", jmodsDir.toString(),
            "--add-modules", modules.joinToString(","),
            "--output", outputDir.toString(),
            "--no-header-files",
            "--no-man-pages",
            "--strip-debug",
            "--compress=2"
        )

        logger.info("jlink 실행: {}", cmd.joinToString(" "))

        val pb = ProcessBuilder(cmd).apply {
            directory(javaHome.toFile())
            redirectErrorStream(true) // stderr -> stdout
        }

        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        val finished = proc.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)
        if (!finished) {
            proc.destroyForcibly()
            logger.error("jlink stdout/stderr:\n{}", out)
            throw RuntimeException("jlink 실행이 10분 내 완료되지 않아 강제 종료되었습니다.")
        }
        val exit = proc.exitValue()
        if (exit != 0) {
            logger.error("jlink stdout/stderr:\n{}", out)   // ← 에러 본문 남기기
            throw RuntimeException("jlink 실행 실패(exit=$exit)")
        }

        val javaBin = outputDir.resolve("bin").resolve(if (isWindows()) "java.exe" else "java")
        require(Files.isExecutable(javaBin)) {
            "jlink 결과에 실행 가능한 java 바이너리가 없습니다: $javaBin\n$out"
        }
        logger.info("custom JRE 생성 완료: {}", outputDir)
    }


    private fun resolveJmods(javaHome: Path): Path {
        val candidates = listOf(
            javaHome.resolve("jmods"),
            javaHome.resolve("lib").resolve("jmods"),
            javaHome.resolve("Contents").resolve("Home").resolve("jmods")
        )
        return candidates.firstOrNull { Files.isDirectory(it) }
            ?: throw IllegalStateException("jmods 디렉터리를 찾을 수 없습니다. JAVA_HOME 확인: $javaHome")
    }

    private fun isWindows() =
        System.getProperty("os.name").lowercase().contains("windows")
}