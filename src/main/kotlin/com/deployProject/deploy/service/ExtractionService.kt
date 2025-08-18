package com.deployProject.deploy.service

import com.badlogicgames.packr.Packr
import com.badlogicgames.packr.PackrConfig
import com.deployProject.deploy.domain.extraction.ExtractionDto
import com.deployProject.deploy.domain.extraction.TargetOsStatus
import com.deployProject.cli.utilCli.JarCreator
import com.deployProject.cli.utilCli.JlinkCreator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.measureTimeMillis

/**
 * Git 정보 추출 및 실행파일(EXE/APP) 패키징 서비스
 *
 * 핵심:
 *  - 타깃 OS별 JRE 경로를 사용 (custom-jre/<windows|mac|linux>)
 *  - 현재 호스트 OS와 타깃 OS가 같으면 jlink로 최소 JRE 생성 가능
 *    (다르면 미리 해당 OS에서 생성한 jlink 결과를 올려두어야 함)
 *  - Packr 템플릿 캐시 사용으로 속도 최적화
 *  - Windows 타깃일 때는 SFX(7zsd.sfx)로 단일 EXE 생성
 */
@Service
class ExtractionService(
    private val jarCreator: JarCreator = JarCreator
) {
    private val logger = LoggerFactory.getLogger(ExtractionService::class.java)

    /** 현재 호스트 OS 문자열 (동작 분기용) */
    private val hostOsName = System.getProperty("os.name").lowercase()

    /** jlink 결과물(최소 JRE)들이 저장될 베이스 경로: custom-jre/<osTag> */
    private val customJreBase: Path = Paths.get("").toAbsolutePath().resolve("custom-jre")

    /**
     * javaHome:
     *  - 외부에서 -Dapp.javaHome=<path> 로 덮어쓰기 가능
     *  - 호스트 OS에 맞춰 기본값 설정 (호스트에서 jlink를 돌릴 때만 사용)
     */
    private val javaHome: Path = System.getProperty("app.javaHome")?.let { Path.of(it) } ?: run {
        when {
            hostOsName.contains("windows") -> Path.of("C:/Program Files/Java/jdk-17")
            hostOsName.contains("mac")     -> Path.of("/Users/mac/.sdkman/candidates/java/current")
            else                           -> Path.of("/home/bjw/.sdkman/candidates/java/current")
        }
    }

    /** 동시 jlink 실행 방지용 락 */
    private val jlinkLock = Any()

    companion object {
        private const val DEPLOY_JAR_NAME = "deploy-project-cli.jar"
        private const val EXECUTABLE_BASE = "deploy-project-cli"
        private const val CFG_NAME = "deploy-project-cli.cfg"
        private const val MAIN_CLASS = "com.deployProject.cli.ExtractionLauncher"
    }

    /**
     * 엔드포인트 진입: Fat-JAR 생성 → Packr 포장 → (Windows면 SFX EXE / 그 외 ZIP) 결과 반환
     */
    fun extractGitInfo(extractionDto: ExtractionDto): File {
        logger.info("deploy.jar 생성 시작")

        val target = extractionDto.targetOs ?: error("targetOs is null")

        // (1) 타깃 OS용 jlink 최소 JRE 확보 (없으면: 호스트=타깃이면 생성 / 다르면 에러 안내)
        ensureTargetJre(target)

        // (2) 작업용 임시 디렉터리(baseDir) 준비
        val baseDir = resolveBaseDir()
        val jarFile = File(baseDir, DEPLOY_JAR_NAME)

        // (3) Fat-JAR 생성 (소요 시간 로깅)
        val tJar = measureTimeMillis {
            logger.info("deploy-cli.jar 생성")
            try {
                jarCreator.main(
                    arrayOf(
                        extractionDto.localPath,        // 0: repoDir
                        "",                              // 1: relPath
                        extractionDto.since,            // 2: sinceDate
                        extractionDto.until,            // 3: untilDate
                        extractionDto.fileStatusType,   // 4: fileStatusType
                        baseDir.absolutePath,           // 5: jarOutputDir
                        extractionDto.homePath          // 6: deployServerDir (옵션)
                    ) as Array<String>
                )
            } catch (e: Exception) {
                logger.error("Fat JAR 생성 중 오류", e)
                throw e
            }
        }
        logger.info("deploy-cli.jar 종료 ({} ms), path={}", tJar, jarFile.absolutePath)

        // (4) 실행파일 출력 디렉터리 초기화
        val outputDir = File(baseDir, "exe-output").apply { recreateDir() }

        // (5) Packr 포장 (템플릿 캐시 사용)
        val tPackr = measureTimeMillis {
            packWithPackr(jarFile, target, outputDir)
        }
        logger.info("Packr 단계 완료 ({} ms)", tPackr)

        // (6) 산출물 만들기:
        //     - Windows: SFX 단일 EXE
        //     - mac/Linux: 디렉터리 전체를 ZIP
        return if (target == TargetOsStatus.WINDOWS) {
            val sfxModule = extractSfxModule() // classpath:/sfx/7zsd.sfx or -Dapp.sfx.path
            val singleExe = makeSelfExtractingExe(outputDir, sfxModule, exeBase = EXECUTABLE_BASE)
            logger.info("단일 EXE 생성 완료: {}", singleExe.absolutePath)
            singleExe
        } else {
            val zipFile = File(outputDir.parentFile, "bundle-${target.name.lowercase()}.zip")
            zipDirectory(outputDir, zipFile)
            logger.info("ZIP 생성 완료: {}", zipFile.absolutePath)
            zipFile
        }
    }

    /**
     * Packr 포장 (템플릿 캐시 사용)
     *
     * 최초 1회: Packr로 템플릿 디렉터리 생성 (느릴 수 있음)
     * 이후   : 템플릿 복사 + 새 JAR/CFG만 교체 (매우 빠름)
     */
    private fun packWithPackr(
        jarFile: File,
        targetOs: TargetOsStatus,
        outputDir: File
    ): File {
        val exeName = exeNameFor(targetOs)
        val cfgName = CFG_NAME
        val templateDir = packrTemplateDir(targetOs)
        val targetJre = targetJreDir(targetOs)

        fun writeCfg(dir: File) {
            File(dir, cfgName).writeText(
                buildString {
                    appendLine("classpath=${jarFile.name}")
                    appendLine("mainclass=$MAIN_CLASS")
                    appendLine("vmargs=-Xmx512m")
                }
            )
        }

        // (A) 템플릿 캐시 사용 경로
        if (templateDir.exists() && File(templateDir, exeName).exists()) {
            outputDir.recreateFrom(templateDir)
            jarFile.copyTo(File(outputDir, jarFile.name), overwrite = true)
            writeCfg(outputDir)
            return File(outputDir, exeName)
        }

        // (B) 템플릿 없음 → 최초 1회 Packr 실행
        if (templateDir.exists()) templateDir.deleteRecursively()
        templateDir.mkdirs()

        // 타깃 OS용 JRE 존재 검증
        val javaBin = targetJre.resolve("bin").resolve(if (targetOs == TargetOsStatus.WINDOWS) "java.exe" else "java")
        require(Files.exists(javaBin)) { "타깃 OS용 JRE가 없습니다: $targetJre (bin/java 존재 필수)" }

        val config = PackrConfig().apply {
            platform = when (targetOs) {
                TargetOsStatus.WINDOWS -> PackrConfig.Platform.Windows64
                TargetOsStatus.MAC     -> PackrConfig.Platform.MacOS
                TargetOsStatus.LINUX   -> PackrConfig.Platform.Linux64
            }
            jdk = targetJre.toString()             // 타깃 OS용 최소 JRE
            executable = EXECUTABLE_BASE
            classpath = listOf(jarFile.absolutePath)
            mainClass = MAIN_CLASS
            vmArgs = listOf("-Xmx512m")
            outDir = templateDir
            useZgcIfSupportedOs = false
        }

        Packr().pack(config)

        outputDir.recreateFrom(templateDir)
        jarFile.copyTo(File(outputDir, jarFile.name), overwrite = true)
        writeCfg(outputDir)
        return File(outputDir, exeName)
    }

    /**
     * 타깃 OS용 jlink 최소 런타임 확보
     * - 이미 있으면 스킵
     * - 없고, '호스트 OS == 타깃 OS' 인 경우에만 jlink 실행하여 생성
     * - 없고, 호스트와 타깃이 다르면: 에러로 안내 (사전 생성 필요)
     */
    private fun ensureTargetJre(target: TargetOsStatus) {
        val targetDir = targetJreDir(target)
        val javaName = if (target == TargetOsStatus.WINDOWS) "java.exe" else "java"
        val javaBin = targetDir.resolve("bin").resolve(javaName)

        if (Files.exists(javaBin)) {
            logger.info("타깃 JRE 이미 존재: {}", targetDir)
            return
        }

        // 호스트 OS와 타깃 OS가 같을 때만 jlink 생성 시도
        val hostMatchesTarget = when (target) {
            TargetOsStatus.WINDOWS -> hostOsName.contains("windows")
            TargetOsStatus.MAC     -> hostOsName.contains("mac")
            TargetOsStatus.LINUX   -> hostOsName.contains("linux")
        }
        if (!hostMatchesTarget) {
            throw IllegalStateException(
                "타깃(${target.name})용 JRE가 없습니다: $targetDir\n" +
                        "이 서버(OS=${hostOsName})에서는 해당 타깃용 jlink 생성을 수행하지 않습니다.\n" +
                        "해결: 타깃 OS에서 jlink로 최소 JRE를 만들고, 다음 위치에 업로드하세요.\n" +
                        " - $targetDir\n" +
                        "   (예: custom-jre/windows, custom-jre/mac, custom-jre/linux)"
            )
        }

        synchronized(jlinkLock) {
            if (Files.exists(javaBin)) return // 더블체크
            logger.info("타깃 JRE 미존재 → jlink 생성: {}, javaHome={}", targetDir, javaHome)

            val modules = listOf(
                "java.base",
                "java.logging",
                "java.xml",
                // "java.desktop",    // GUI 필요시 주석 해제
                "jdk.unsupported",
                "jdk.crypto.ec",
                "jdk.charsets"
                // "jdk.zipfs"       // zipfs API 필요시
            )

            // 출력 디렉터리를 target 전용으로 생성
            Files.createDirectories(targetDir)
            JlinkCreator.createJlink(modules, targetDir, javaHome)

            require(Files.exists(javaBin)) { "jlink 생성 실패: $javaBin 없음" }
        }
    }

    /* ========================== Paths & Utils =======================*/

    /** 타깃 OS별 JRE 디렉터리 */
    private fun targetJreDir(target: TargetOsStatus): Path =
        when (target) {
            TargetOsStatus.WINDOWS -> customJreBase.resolve("windows")
            TargetOsStatus.MAC     -> customJreBase.resolve("mac")
            TargetOsStatus.LINUX   -> customJreBase.resolve("linux")
        }

    /** 타깃 OS별 Packr 템플릿 디렉터리 캐시 */
    private fun packrTemplateDir(targetOs: TargetOsStatus): File {
        val osTag = when (targetOs) {
            TargetOsStatus.WINDOWS -> "windows"
            TargetOsStatus.MAC     -> "mac"
            TargetOsStatus.LINUX   -> "linux"
        }
        return Paths.get("").toAbsolutePath().resolve("packr-template").resolve(osTag).toFile()
    }

    /** 타깃 OS별 실행 파일 이름 */
    private fun exeNameFor(targetOs: TargetOsStatus): String =
        if (targetOs == TargetOsStatus.WINDOWS) "$EXECUTABLE_BASE.exe" else EXECUTABLE_BASE

    /** 작업용 베이스 디렉터리: 프로젝트 내부 GitInfoJarFile/<UUID> (리눅스 서버는 상위 2단계 기준) */
    private fun resolveBaseDir(): File {
        var base = File("GitInfoJarFile", UUID.randomUUID().toString())

        if (!hostOsName.contains("windows") && !hostOsName.contains("mac")) {
            val wd = Paths.get("").toAbsolutePath()
            val projectRoot = wd.parent?.parent
                ?: throw IllegalStateException("작업 디렉터리 기준으로 두 단계 상위가 존재하지 않습니다.")
            base = File(projectRoot.toAbsolutePath().resolve("GitInfoJarFile").toString(), UUID.randomUUID().toString())
        }

        if (!base.exists()) base.mkdirs()
        return base
    }

    /** 디렉터리 → zip 압축 (상대 경로 유지) */
    private fun zipDirectory(sourceDir: File, targetZip: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(targetZip))).use { zos ->
            sourceDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val entryName = sourceDir.toPath().relativize(file.toPath()).toString().replace("\\", "/")
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
        }
    }

    /** 디렉터리 재생성 */
    private fun File.recreateDir() {
        if (exists()) deleteRecursively()
        mkdirs()
    }

    /** src를 통째로 복사하여 자신을 재생성 */
    private fun File.recreateFrom(src: File) {
        if (exists()) deleteRecursively()
        src.copyRecursively(this, overwrite = true)
    }

    /** 7z/7zz 커맨드 탐색 */
    private fun find7zCommand(): String {
        val candidates = listOf(
            "7z", "7zz",
            "/usr/local/bin/7z", "/opt/homebrew/bin/7z", "/usr/bin/7z",
            "C:\\Program Files\\7-Zip\\7z.exe"
        )
        return candidates.firstOrNull { File(it).canExecute() || which(it) } ?: error(
            "7z/7zz CLI not found. Please install 7-Zip or set PATH. " +
                    "On mac: brew install p7zip (7zz). On Windows: install 7-Zip."
        )
    }

    private fun which(cmd: String): Boolean =
        try {
            val p = ProcessBuilder(
                if (hostOsName.contains("win")) listOf("where", cmd) else listOf("which", cmd)
            ).redirectErrorStream(true).start()
            p.waitFor() == 0
        } catch (_: Exception) { false }

    /* ========================== SFX (Windows only) =======================*/

    /**
     * classpath:/sfx/7zsd.sfx 또는 -Dapp.sfx.path / APP_SFX_PATH 에서 스텁을 로드
     */
    private fun extractSfxModule(): File {
        val candidates = listOf(
            "sfx/7zsd.sfx" to javaClass.classLoader.getResourceAsStream("sfx/7zsd.sfx"),
            "/sfx/7zsd.sfx" to javaClass.getResourceAsStream("/sfx/7zsd.sfx")
        )
        for ((hint, ins) in candidates) {
            if (ins != null) {
                val tmp = Files.createTempFile("7zsd-", ".sfx").toFile().apply { deleteOnExit() }
                ins.use { it.copyTo(tmp.outputStream()) }
                logger.info("SFX module loaded from classpath: {}", hint)
                return tmp
            }
        }
        val externalPath = System.getProperty("app.sfx.path") ?: System.getenv("APP_SFX_PATH")
        if (!externalPath.isNullOrBlank()) {
            val f = File(externalPath)
            if (f.isFile) {
                logger.info("SFX module loaded from external path: {}", f.absolutePath)
                return f
            } else {
                logger.warn("External SFX path is not a file: {}", externalPath)
            }
        }
        logger.error("Classpath does not contain /sfx/7zsd.sfx. java.class.path={}", System.getProperty("java.class.path"))
        throw IllegalStateException(
            "SFX module not found. " +
                    "Put 7zsd.sfx under src/main/resources/sfx/7zsd.sfx " +
                    "or provide -Dapp.sfx.path=/absolute/path/to/7zsd.sfx"
        )
    }

    /**
     * Windows SFX 단일 EXE 생성:
     *  - exe-output 전체를 7z로 묶고 (app.7z)
     *  - 7zsd.sfx + config.txt + app.7z 를 바이너리 연결하여 DeployProjectCLI.exe 생성
     */
    private fun makeSelfExtractingExe(outputDir: File, sfxModule: File, exeBase: String = EXECUTABLE_BASE): File {
        val sevenZip = find7zCommand() // 실제로 사용하도록 수정
        val app7z = File(outputDir, "app.7z")

        // (a) 7z 압축 (속도/안정성 균형: -mx=3)
        runProcess(listOf(sevenZip, "a", "-t7z", "-mx=3", app7z.absolutePath, "."), workDir = outputDir)

        // (b) SFX 설정 파일
        val cfg = File(outputDir, "config.txt").apply {
            writeText(
                """
                ;!@Install@!UTF-8!
                Title="Deploy Project CLI"
                RunProgram="$exeBase.exe"
                ExtractTitle="Preparing..."
                ExtractDialogText="Unpacking..."
                GUIMode="2"
                ;!@InstallEnd@!
                """.trimIndent()
            )
        }

        // (c) 단일 EXE로 합치기
        val result = File(outputDir.parentFile, "DeployProjectCLI.exe")
        FileOutputStream(result).use { out ->
            sfxModule.inputStream().use { it.copyTo(out) }
            cfg.inputStream().use { it.copyTo(out) }
            app7z.inputStream().use { it.copyTo(out) }
        }
        return result
    }

    /** 외부 프로세스 실행 헬퍼 */
    private fun runProcess(cmd: List<String>, workDir: File) {
        val pb = ProcessBuilder(cmd).directory(workDir).redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        val ok = p.waitFor() == 0
        if (!ok) error("Process failed: ${cmd.joinToString(" ")}\n$out")
    }
}