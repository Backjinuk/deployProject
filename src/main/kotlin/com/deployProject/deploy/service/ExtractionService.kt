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
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.measureTimeMillis

/**
 * Git 정보 추출 + 실행파일(EXE/APP) 패키징 서비스
 *
 * 동작 개요:
 *  1) 타깃 OS용 최소 JRE(custom-jre/<windows|mac|linux>) 확보
 *     - 없으면: 호스트 OS == 타깃 OS일 때만 jlink로 생성
 *  2) Fat-JAR(deploy-project-cli.jar) 생성
 *  3) Packr 포장 (템플릿 캐시 사용)
 *     - 최초 1회만 느림, 이후 템플릿 복사 + JAR/CFG 덮어쓰기만 수행
 *  4) 결과 디렉터리를 ZIP으로 묶어 반환
 *
 * 설계 포인트:
 *  - classpath는 "단일 JAR 파일명"으로 사용(디렉터리 classpath 사용 안 함)
 *  - SFX(7zsd.sfx)·JAR 폭파(explode)는 사용하지 않음 → 단순/안정
 */
@Service
class ExtractionService(
    private val jarCreator: JarCreator = JarCreator
) {
    private val logger = LoggerFactory.getLogger(ExtractionService::class.java)

    /** 현재 호스트 OS 문자열 (동작 분기용) */
    private val hostOsName = System.getProperty("os.name").lowercase()

    /** 타깃 OS별 jlink 결과물(최소 JRE) 루트: custom-jre/<windows|mac|linux> */
    private val customJreBase: Path = Paths.get("").toAbsolutePath().resolve("custom-jre")

    /**
     * jlink 수행 시 사용할 javaHome
     *  - -Dapp.javaHome 로 외부에서 지정 가능
     *  - 지정이 없으면 호스트 OS 기준의 합리적 기본값 사용
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
        private const val DEPLOY_JAR_NAME = "deploy-project-cli.jar"        // 생성할 Fat-JAR 이름
        private const val EXECUTABLE_BASE = "deploy-project-cli"            // Packr 실행파일 베이스
        private const val CFG_NAME = "deploy-project-cli.cfg"               // Packr cfg 파일명
        private const val MAIN_CLASS = "com.deployProject.cli.ExtractionLauncher" // 메인 클래스
    }

    /**
     * 엔드포인트 진입:
     *  - Fat-JAR 생성 → Packr 포장 → ZIP 산출
     */
    fun extractGitInfo(extractionDto: ExtractionDto): File {
        logger.info("deploy.jar 생성 시작")

        val target = extractionDto.targetOs ?: error("targetOs is null")

        // (1) 타깃 OS용 jlink 최소 JRE 확보
        //     - 존재하면 스킵
        //     - 없고, 호스트==타깃일 때만 jlink 생성
        ensureTargetJre(target)

        // (2) 작업용 임시 디렉터리(baseDir) 준비 (ex: GitInfoJarFile/<UUID>)
        val baseDir = resolveBaseDir()
        val jarFile = File(baseDir, DEPLOY_JAR_NAME)

        // (3) Fat-JAR 생성 (가장 시간이 걸릴 수 있으므로 측정 로깅)
        val tJar = measureTimeMillis {
            logger.info("deploy-cli.jar 생성")
            jarCreator.main(
                arrayOf(
                    extractionDto.localPath,      // 0: repoDir
                    "",                            // 1: relPath
                    extractionDto.since,          // 2: sinceDate
                    extractionDto.until,          // 3: untilDate
                    extractionDto.fileStatusType, // 4: fileStatusType
                    baseDir.absolutePath,         // 5: jarOutputDir (JAR이 여기에 생성됨)
                    extractionDto.homePath        // 6: deployServerDir (옵션)
                ) as Array<String>
            )
        }
        logger.info("deploy-cli.jar 종료 ({} ms), path={}", tJar, jarFile.absolutePath)

        // (4) 실행파일 출력 디렉터리 초기화 (매번 새로 생성)
        val outputDir = File(baseDir, "exe-output").apply { recreateDir() }

        // (5) Packr 포장 (템플릿 캐시 활용)
        //     - 템플릿 있으면: 복사 + JAR/CFG 덮어쓰기 (빠름)
        //     - 템플릿 없으면: 최초 1회 Packr 실행 → 템플릿 생성
        val tPackr = measureTimeMillis {
            packWithPackr(jarFile, target, outputDir)
        }
        logger.info("Packr 단계 완료 ({} ms)", tPackr)

        // (6) 결과 디렉터리 → ZIP 파일로 묶어 반환
        val zipFile = File(outputDir.parentFile, "bundle-${target.name.lowercase()}.zip")
        zipDirectory(outputDir, zipFile)
        logger.info("ZIP 생성 완료: {}", zipFile.absolutePath)

        return zipFile
    }

    /**
     * Packr 포장 (템플릿 캐시 사용, JAR 그대로 classpath에 올림)
     *
     * 동작:
     *  - 템플릿 있음: 템플릿 복사 → 신규 JAR 복사 → CFG(classpath=<JAR>) 갱신
     *  - 템플릿 없음: Packr 한 번 실행(outDir=templateDir) → 동일 루틴
     */
    private fun packWithPackr(
        jarFile: File,
        targetOs: TargetOsStatus,
        outputDir: File
    ): File {
        val exeName     = exeNameFor(targetOs)              // OS별 실행 파일명
        val templateDir = packrTemplateDir(targetOs)        // OS별 템플릿 캐시 위치
        val targetJre   = targetJreDir(targetOs)            // OS별 최소 JRE 위치

        // CFG 파일 작성: classpath는 "단일 JAR 파일명"만 명시
        fun writeCfg(dir: File, jarName: String) {
            File(dir, CFG_NAME).writeText(
                buildString {
                    appendLine("classpath=$jarName")
                    appendLine("mainclass=$MAIN_CLASS")
                    appendLine("vmargs=-Xmx512m")
                }
            )
        }

        // (A) 템플릿 경로가 이미 준비되어 있을 경우(빠름)
        if (templateDir.exists() && File(templateDir, exeName).exists()) {
            // 1) 템플릿을 결과 디렉터리로 통째 복사
            outputDir.recreateFrom(templateDir)

            // 2) 이번 요청에서 생성된 Fat-JAR을 결과 디렉터리 최상단에 복사(덮어쓰기)
            jarFile.copyTo(File(outputDir, jarFile.name), overwrite = true)

            // 3) CFG를 현재 JAR 파일명으로 갱신
            writeCfg(outputDir, jarFile.name)

            return File(outputDir, exeName)
        }

        // (B) 템플릿이 없을 경우: 최초 1회 Packr 실행으로 템플릿 생성
        if (templateDir.exists()) templateDir.deleteRecursively()
        templateDir.mkdirs()

        // 타깃 OS용 JRE 존재 검증 (bin/java 있어야 정상)
        val javaBin = targetJre.resolve("bin").resolve(
            if (targetOs == TargetOsStatus.WINDOWS) "java.exe" else "java"
        )
        require(Files.exists(javaBin)) { "타깃 OS용 JRE가 없습니다: $targetJre (bin/java 필요)" }

        val config = PackrConfig().apply {
            platform = when (targetOs) {
                TargetOsStatus.WINDOWS -> PackrConfig.Platform.Windows64
                TargetOsStatus.MAC     -> PackrConfig.Platform.MacOS
                TargetOsStatus.LINUX   -> PackrConfig.Platform.Linux64
            }
            jdk        = targetJre.toString()      // jlink로 만든 최소 JRE
            executable = EXECUTABLE_BASE
            classpath  = listOf(jarFile.absolutePath) // 템플릿 생성 시점에는 절대경로 OK
            mainClass  = MAIN_CLASS
            vmArgs     = listOf("-Xmx512m")
            outDir     = templateDir
            useZgcIfSupportedOs = false
        }

        // 최초 1회 포장 (느릴 수 있음)
        Packr().pack(config)

        // 템플릿이 만들어졌으니, 결과 디렉터리를 템플릿 복사로 생성
        outputDir.recreateFrom(templateDir)

        // 현재 요청의 JAR로 교체
        jarFile.copyTo(File(outputDir, jarFile.name), overwrite = true)

        // CFG 갱신
        writeCfg(outputDir, jarFile.name)

        return File(outputDir, exeName)
    }

    /**
     * 타깃 OS용 최소 JRE 확보
     * - 이미 있으면 스킵
     * - 없고, 호스트 OS == 타깃 OS일 때만 jlink로 생성
     * - 없고, 호스트와 타깃이 다르면: 사전 준비 필요(오류 안내)
     */
    private fun ensureTargetJre(target: TargetOsStatus) {
        val targetDir = targetJreDir(target)
        val javaName  = if (target == TargetOsStatus.WINDOWS) "java.exe" else "java"
        val javaBin   = targetDir.resolve("bin").resolve(javaName)

        if (Files.exists(javaBin)) {
            logger.info("타깃 JRE 이미 존재: {}", targetDir)
            return
        }

        // 현재 서버에서 해당 타깃용 jlink를 생성할 수 있는지(호스트==타깃) 확인
        val hostMatchesTarget = when (target) {
            TargetOsStatus.WINDOWS -> hostOsName.contains("windows")
            TargetOsStatus.MAC     -> hostOsName.contains("mac")
            TargetOsStatus.LINUX   -> hostOsName.contains("linux")
        }
        if (!hostMatchesTarget) {
            throw IllegalStateException(
                "타깃(${target.name})용 JRE가 없습니다: $targetDir\n" +
                        "이 서버(OS=$hostOsName)에서는 해당 타깃용 jlink 생성을 수행하지 않습니다.\n" +
                        "해결: 타깃 OS 환경에서 jlink로 최소 JRE를 만들어 다음 경로에 업로드하세요.\n" +
                        " - $targetDir   (예: custom-jre/windows, custom-jre/mac, custom-jre/linux)"
            )
        }

        // 실제 jlink 실행(모듈 최소화 구성)
        synchronized(jlinkLock) {
            if (Files.exists(javaBin)) return // 더블체크

            logger.info("타깃 JRE 미존재 → jlink 생성: {}, javaHome={}", targetDir, javaHome)

            val modules = listOf(
                "java.base",
                "java.logging",
                "java.xml",
                "java.desktop",   // Swing/AWT 사용 시 필요
                "jdk.unsupported",
                "jdk.crypto.ec",
                "jdk.charsets"
                // "jdk.zipfs"     // Zip FS API 필요 시 활성화
            )

            Files.createDirectories(targetDir)
            JlinkCreator.createJlink(modules, targetDir, javaHome)

            require(Files.exists(javaBin)) { "jlink 생성 실패: $javaBin 없음" }
        }
    }

    /* -------------------------- Paths & Utils -------------------------- */

    /** 타깃 OS별 JRE 디렉터리 */
    private fun targetJreDir(target: TargetOsStatus): Path =
        when (target) {
            TargetOsStatus.WINDOWS -> customJreBase.resolve("windows")
            TargetOsStatus.MAC     -> customJreBase.resolve("mac")
            TargetOsStatus.LINUX   -> customJreBase.resolve("linux")
        }

    /** 타깃 OS별 Packr 템플릿 디렉터리 (캐시 위치) */
    private fun packrTemplateDir(targetOs: TargetOsStatus): File {
        val tag = when (targetOs) {
            TargetOsStatus.WINDOWS -> "windows"
            TargetOsStatus.MAC     -> "mac"
            TargetOsStatus.LINUX   -> "linux"
        }
        return Paths.get("").toAbsolutePath().resolve("packr-template").resolve(tag).toFile()
    }

    /** 타깃 OS별 실행파일 이름(Windows는 .exe 확장자) */
    private fun exeNameFor(targetOs: TargetOsStatus): String =
        if (targetOs == TargetOsStatus.WINDOWS) "$EXECUTABLE_BASE.exe" else EXECUTABLE_BASE

    /**
     * 작업용 베이스 디렉터리 생성
     *  - 기본: 프로젝트 내부 GitInfoJarFile/<UUID>
     *  - 리눅스(서버): 작업 디렉터리 기준 상위 2단계를 프로젝트 루트로 보고 그 아래에 생성
     */
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

    /** 디렉터리 → ZIP 압축 (상대 경로 유지) */
    private fun zipDirectory(sourceDir: File, targetZip: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(targetZip))).use { zos ->
            zos.setLevel(Deflater.BEST_SPEED)
            sourceDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val entryName = sourceDir.toPath()
                        .relativize(file.toPath())
                        .toString()
                        .replace("\\", "/")
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
        }
    }

    /** 유틸: 디렉터리 재생성 */
    private fun File.recreateDir() {
        if (exists()) deleteRecursively()
        mkdirs()
    }

    /** 유틸: src를 통째 복사하여 자신을 재생성 */
    private fun File.recreateFrom(src: File) {
        if (exists()) deleteRecursively()
        src.copyRecursively(this, overwrite = true)
    }
}