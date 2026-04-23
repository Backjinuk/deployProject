package com.deployProject.deploy.service

import com.badlogicgames.packr.Packr
import com.badlogicgames.packr.PackrConfig
import com.deployProject.deploy.domain.extraction.ExtractionDto
import com.deployProject.deploy.domain.extraction.RepositoryDuplicateFileDto
import com.deployProject.deploy.domain.extraction.RepositoryVersionFileListDto
import com.deployProject.deploy.domain.extraction.RepositoryVersionListDto
import com.deployProject.deploy.domain.extraction.RepositoryVersionOptionDto
import com.deployProject.deploy.domain.extraction.TargetOsStatus
import com.deployProject.cli.utilCli.JarCreator
import com.deployProject.cli.utilCli.JlinkCreator
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.tmatesoft.svn.core.SVNException
import org.tmatesoft.svn.core.wc.SVNClientManager
import org.tmatesoft.svn.core.wc.SVNRevision
import org.tmatesoft.svn.core.wc.SVNWCUtil
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
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
    // 수정 이유: 추출 산출물이 프로젝트 내부에 누적되지 않도록 OS 임시 경로를 작업 루트로 사용한다.
    private val extractionWorkRoot: Path = Paths.get("").toAbsolutePath().resolve("GitInfoJarFile")

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
        private const val VERSION_RESULT_LIMIT = 300
    }

    // 수정 이유: 상수 선언 줄이 깨진 상태에서도 컴파일 안정성을 확보하기 위해 필요한 값을 명시적으로 다시 선언한다.
    private val CFG_NAME = "deploy-project-cli.cfg"
    private val MAIN_CLASS = "com.deployProject.cli.ExtractionLauncher"
    private val VERSION_RESULT_LIMIT = 300
    private val STALE_WORK_DIR_RETENTION_HOURS = 24L

    private val versionDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val selectionDelimiter = "|~|"
    private val keyValueDelimiter = "::"

    /**
     * 엔드포인트 진입:
     *  - Fat-JAR 생성 → Packr 포장 → ZIP 산출
     */
    fun extractGitInfo(extractionDto: ExtractionDto): File {
        cleanupStaleExtractionDirs()
        logger.info("deploy.jar 생성 시작")

        val target = extractionDto.targetOs ?: error("targetOs is null")
        // 수정 이유: 날짜 기반 조회 후 사용자가 시작/종료 버전을 직접 선택해야 하므로 서버에서도 필수값으로 강제한다.
        val selectedVersions = extractionDto.selectedVersions.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val selectedFiles = extractionDto.selectedFiles.orEmpty()
            .map { it.trim().replace("\\", "/") }
            .filter { it.isNotEmpty() }
            .distinct()

        val duplicateFileVersionMap = extractionDto.duplicateFileVersionMap.orEmpty()
            .mapKeys { it.key.trim().replace("\\", "/") }
            .mapValues { it.value.trim() }
            .filter { (path, version) -> path.isNotEmpty() && version.isNotEmpty() }

        val hasLegacyVersionRange = !extractionDto.sinceVersion.isNullOrBlank() && !extractionDto.untilVersion.isNullOrBlank()
        val requiresDiffSelection = !extractionDto.fileStatusType.equals("STATUS", ignoreCase = true)

        // 수정 이유: 버전 체크 방식이 기본 흐름이므로 선택 버전 목록을 우선 사용하고, 기존 범위 방식은 하위 호환으로만 유지한다.
        if (requiresDiffSelection) {
            require(selectedVersions.isNotEmpty() || hasLegacyVersionRange) {
                "selectedVersions is required when DIFF is enabled"
            }
        }

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
            // 수정 이유: 날짜 + 저장소 버전 조건을 CLI로 전달하고, nullable 인자 캐스팅 예외를 방지한다.
            jarCreator.main(
                listOf(
                    extractionDto.localPath ?: "",      // 0: repoDir
                    "",                                 // 1: relPath
                    extractionDto.since ?: "",          // 2: sinceDate
                    extractionDto.until ?: "",          // 3: untilDate
                    extractionDto.fileStatusType ?: "", // 4: fileStatusType
                    baseDir.absolutePath,               // 5: jarOutputDir
                    extractionDto.homePath ?: "",       // 6: deployServerDir
                    extractionDto.sinceVersion ?: "",   // 7: sinceVersion
                    extractionDto.untilVersion ?: "",   // 8: untilVersion
                    selectedVersions.joinToString(selectionDelimiter), // 9: selectedVersions
                    selectedFiles.joinToString(selectionDelimiter),    // 10: selectedFiles
                    encodeDuplicateFileVersionMap(duplicateFileVersionMap) // 11: duplicateFileVersionMap
                ).toTypedArray()
            )
        }
        logger.info("deploy-cli.jar 종료 ({} ms), path={}", tJar, jarFile.absolutePath)

        // (4) 실행파일 출력 디렉터리 초기화 (매번 새로 생성)
        val outputDir = File(baseDir, "exe-output").apply { recreateDir() }

        // (5) Packr 포장 (템플릿 캐시 활용)
        //     - 템플릿 있으면: 복사 + JAR/CFG 덮어쓰기 (빠름)
        //     - 템플릿 없으면: 최초 1회 Packr 실행 → 템플릿 생성
        // 수정 이유: 오래된 packr-template 캐시로 exe 실행이 실패하는 문제를 피하기 위해 매 요청 템플릿을 갱신한다.
        packrTemplateDir(target).takeIf { it.exists() }?.deleteRecursively()
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

    fun cleanupExtractionArtifacts(zipFile: File) {
        val workDir = zipFile.parentFile ?: return
        val workDirPath = runCatching { workDir.toPath().toRealPath() }.getOrElse { return }
        val managedRoot = runCatching {
            Files.createDirectories(extractionWorkRoot)
            extractionWorkRoot.toRealPath()
        }.getOrElse { return }

        // 수정 이유: 서비스가 생성한 임시 작업 경로만 삭제 대상으로 제한한다.
        if (!workDirPath.startsWith(managedRoot)) return

        runCatching {
            workDir.deleteRecursively()
        }.onFailure { error ->
            logger.warn("Failed to cleanup extraction work directory: {}", workDir.absolutePath, error)
        }
    }

    fun listRepositoryVersions(extractionDto: ExtractionDto): RepositoryVersionListDto {
        val repoMetaDir = resolveRepositoryMetaDir(extractionDto.localPath)
        val rawSinceDate = parseDateOrToday(extractionDto.since)
        val rawUntilDate = parseDateOrToday(extractionDto.until)
        val sinceDate = minOf(rawSinceDate, rawUntilDate)
        val untilDate = maxOf(rawSinceDate, rawUntilDate)

        // 수정 이유: 날짜 선택 이후 그 범위에 포함된 버전만 프론트 선택 목록으로 내려주기 위함.
        return when (repoMetaDir.name.lowercase()) {
            ".git" -> listGitVersions(repoMetaDir.parentFile, sinceDate, untilDate)
            ".svn" -> listSvnVersions(repoMetaDir.parentFile, sinceDate, untilDate)
            else -> RepositoryVersionListDto(vcsType = "UNKNOWN", versions = emptyList())
        }
    }

    fun listVersionFiles(extractionDto: ExtractionDto): RepositoryVersionFileListDto {
        val repoMetaDir = resolveRepositoryMetaDir(extractionDto.localPath)
        val selectedVersions = extractionDto.selectedVersions.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        // 수정 이유: 체크된 버전이 없으면 파일 목록도 비워서 프론트에서 잘못된 이전 상태를 유지하지 않게 한다.
        if (selectedVersions.isEmpty()) {
            return when (repoMetaDir.name.lowercase()) {
                ".git" -> RepositoryVersionFileListDto(vcsType = "GIT", files = emptyList())
                ".svn" -> RepositoryVersionFileListDto(vcsType = "SVN", files = emptyList())
                else -> RepositoryVersionFileListDto(vcsType = "UNKNOWN", files = emptyList())
            }
        }

        return when (repoMetaDir.name.lowercase()) {
            ".git" -> listGitVersionFiles(repoMetaDir.parentFile, selectedVersions)
            ".svn" -> listSvnVersionFiles(repoMetaDir.parentFile, selectedVersions)
            else -> RepositoryVersionFileListDto(vcsType = "UNKNOWN", files = emptyList())
        }
    }

    private fun listGitVersions(workTree: File, sinceDate: LocalDate, untilDate: LocalDate): RepositoryVersionListDto {
        val zone = ZoneId.systemDefault()
        val versions = Git.open(workTree).use { git ->
            git.log().call()
                .asSequence()
                .map { commit ->
                    val committedAt = Instant.ofEpochSecond(commit.commitTime.toLong()).atZone(zone).toLocalDateTime()
                    commit to committedAt
                }
                .filter { (_, committedAt) ->
                    val date = committedAt.toLocalDate()
                    !date.isBefore(sinceDate) && !date.isAfter(untilDate)
                }
                .map { (commit, committedAt) ->
                    val shortHash = commit.name.take(12)
                    val committedAtText = committedAt.format(versionDateFormatter)
                    RepositoryVersionOptionDto(
                        value = commit.name,
                        label = "$shortHash | $committedAtText | ${commit.shortMessage}",
                        committedAt = committedAtText
                    )
                }
                .take(VERSION_RESULT_LIMIT)
                .toList()
        }

        return RepositoryVersionListDto(vcsType = "GIT", versions = versions)
    }

    private fun listGitVersionFiles(workTree: File, selectedVersions: List<String>): RepositoryVersionFileListDto {
        val fileVersionMap = linkedMapOf<String, MutableSet<String>>()
        val versionOptionMap = linkedMapOf<String, RepositoryVersionOptionDto>()
        val resolvedOrder = mutableListOf<String>()
        val zone = ZoneId.systemDefault()

        runCatching {
            Git.open(workTree).use { git ->
                val repo = git.repository
                selectedVersions.forEach { revision ->
                    val commitId = runCatching { repo.resolve(revision) }.getOrNull() ?: return@forEach
                    val commit = git.log().add(commitId).setMaxCount(1).call().firstOrNull() ?: return@forEach
                    val versionId = commit.name
                    val newTreeId = repo.resolve("${versionId}^{tree}") ?: return@forEach
                    if (versionId !in resolvedOrder) resolvedOrder.add(versionId)

                    val committedAtText = Instant.ofEpochSecond(commit.commitTime.toLong())
                        .atZone(zone)
                        .toLocalDateTime()
                        .format(versionDateFormatter)
                    versionOptionMap[versionId] = RepositoryVersionOptionDto(
                        value = versionId,
                        label = "${versionId.take(12)} | $committedAtText | ${commit.shortMessage}",
                        committedAt = committedAtText
                    )

                    repo.newObjectReader().use { reader ->
                        val oldTree = commit.parents.firstOrNull()?.let { parent ->
                            repo.resolve("${parent.name}^{tree}")?.let { treeId ->
                                CanonicalTreeParser().apply { reset(reader, treeId) }
                            }
                        }
                        val newTree = CanonicalTreeParser().apply { reset(reader, newTreeId) }

                        git.diff()
                            .setOldTree(oldTree)
                            .setNewTree(newTree)
                            .call()
                            .mapNotNull { diff ->
                                val raw = if (diff.changeType == DiffEntry.ChangeType.DELETE) diff.oldPath else diff.newPath
                                raw.takeUnless { it.isNullOrBlank() || it == DiffEntry.DEV_NULL }
                            }
                            .mapNotNull { normalizeRepoRelativePath(it, workTree) }
                            .forEach { path ->
                                fileVersionMap.getOrPut(path) { linkedSetOf() }.add(versionId)
                            }
                    }
                }
            }
        }.onFailure { error ->
            logger.warn("Git version file listing failed", error)
        }

        val files = fileVersionMap.keys.sorted()
        val duplicateFiles = buildDuplicateFiles(fileVersionMap, versionOptionMap, resolvedOrder)
        return RepositoryVersionFileListDto(vcsType = "GIT", files = files, duplicateFiles = duplicateFiles)
    }

    private fun listSvnVersions(workTree: File, sinceDate: LocalDate, untilDate: LocalDate): RepositoryVersionListDto {
        val zone = ZoneId.systemDefault()
        val startDate = Date.from(sinceDate.atStartOfDay(zone).toInstant())
        val endDate = Date.from(untilDate.plusDays(1).atStartOfDay(zone).minusNanos(1).toInstant())
        val options = mutableListOf<RepositoryVersionOptionDto>()

        runCatching {
            val client = createSvnClientManager()
            client.logClient.doLog(
                arrayOf(workTree),
                SVNRevision.create(startDate),
                SVNRevision.create(endDate),
                false,
                true,
                VERSION_RESULT_LIMIT.toLong()
            ) { logEntry ->
                val committedAt = logEntry.date?.toInstant()?.atZone(zone)?.toLocalDateTime()
                    ?.format(versionDateFormatter)
                    ?: ""
                val revision = logEntry.revision.toString()
                val shortMessage = logEntry.message?.lineSequence()?.firstOrNull().orEmpty()
                options.add(
                    RepositoryVersionOptionDto(
                        value = revision,
                        label = "r$revision | $committedAt | $shortMessage",
                        committedAt = committedAt
                    )
                )
            }
        }.onFailure { error ->
            if (error is SVNException) {
                logger.warn("SVN revision listing failed: {}", error.message)
            } else {
                logger.warn("SVN revision listing failed", error)
            }
        }

        return RepositoryVersionListDto(vcsType = "SVN", versions = options.sortedByDescending { it.value.toLongOrNull() ?: -1L })
    }

    private fun listSvnVersionFiles(workTree: File, selectedVersions: List<String>): RepositoryVersionFileListDto {
        val fileVersionMap = linkedMapOf<String, MutableSet<String>>()
        val versionOptionMap = linkedMapOf<String, RepositoryVersionOptionDto>()
        val resolvedOrder = mutableListOf<String>()
        val zone = ZoneId.systemDefault()

        runCatching {
            val client = createSvnClientManager()
            selectedVersions.mapNotNull { it.toLongOrNull() }.distinct().forEach { revision ->
                val versionId = revision.toString()
                if (versionId !in resolvedOrder) resolvedOrder.add(versionId)
                val rev = SVNRevision.create(revision)

                client.logClient.doLog(
                    arrayOf(workTree),
                    rev,
                    rev,
                    false,
                    true,
                    1L
                ) { logEntry ->
                    val committedAt = logEntry.date?.toInstant()?.atZone(zone)?.toLocalDateTime()
                        ?.format(versionDateFormatter)
                        ?: ""
                    val shortMessage = logEntry.message?.lineSequence()?.firstOrNull().orEmpty()
                    versionOptionMap[versionId] = RepositoryVersionOptionDto(
                        value = versionId,
                        label = "r$versionId | $committedAt | $shortMessage",
                        committedAt = committedAt
                    )

                    logEntry.changedPaths.values.forEach { change ->
                        val path = change.path?.trim().orEmpty()
                        if (path.isNotEmpty()) {
                            val normalized = normalizeRepoRelativePath(path, workTree)
                            if (normalized != null) {
                                fileVersionMap.getOrPut(normalized) { linkedSetOf() }.add(versionId)
                            }
                        }
                    }
                }
            }
        }.onFailure { error ->
            if (error is SVNException) {
                logger.warn("SVN version file listing failed: {}", error.message)
            } else {
                logger.warn("SVN version file listing failed", error)
            }
        }

        val files = fileVersionMap.keys.sorted()
        val duplicateFiles = buildDuplicateFiles(fileVersionMap, versionOptionMap, resolvedOrder)
        return RepositoryVersionFileListDto(vcsType = "SVN", files = files, duplicateFiles = duplicateFiles)
    }

    private fun buildDuplicateFiles(
        fileVersionMap: Map<String, Set<String>>,
        versionOptionMap: Map<String, RepositoryVersionOptionDto>,
        resolvedOrder: List<String>
    ): List<RepositoryDuplicateFileDto> {
        val order = resolvedOrder.withIndex().associate { it.value to it.index }

        return fileVersionMap.entries.asSequence()
            .filter { it.value.size > 1 }
            .map { (path, versionIds) ->
                val options = versionIds
                    .sortedBy { order[it] ?: Int.MAX_VALUE }
                    .map { versionId ->
                        versionOptionMap[versionId] ?: RepositoryVersionOptionDto(
                            value = versionId,
                            label = versionId,
                            committedAt = ""
                        )
                    }
                RepositoryDuplicateFileDto(path = path, versions = options)
            }
            .sortedBy { it.path }
            .toList()
    }

    private fun normalizeRepoRelativePath(rawPath: String, workTree: File): String? {
        var normalized = rawPath.trim().replace("\\", "/")
        if (normalized.isBlank() || normalized == DiffEntry.DEV_NULL) return null
        normalized = normalized.removePrefix("/")
        val rootPrefix = "${workTree.name}/"
        if (normalized.startsWith(rootPrefix, ignoreCase = true)) {
            normalized = normalized.substring(rootPrefix.length)
        }
        normalized = normalized.removePrefix("./")
        return normalized.takeIf { it.isNotBlank() }
    }

    private fun encodeDuplicateFileVersionMap(data: Map<String, String>): String {
        return data.entries.joinToString(selectionDelimiter) { (path, version) ->
            "$path$keyValueDelimiter$version"
        }
    }

    private fun createSvnClientManager(): SVNClientManager {
        val configDir = detectSvnConfigDir()
        require(configDir.exists()) { "SVN config directory not found: ${configDir.path}" }
        val opts = SVNWCUtil.createDefaultOptions(configDir, true)
        val auth = SVNWCUtil.createDefaultAuthenticationManager(configDir)
        return SVNClientManager.newInstance(opts, auth)
    }

    private fun detectSvnConfigDir(): File {
        return if (hostOsName.contains("windows")) {
            File(System.getenv("APPDATA"), "Subversion")
        } else {
            File(System.getProperty("user.home"), ".subversion")
        }
    }

    private fun resolveRepositoryMetaDir(localPath: String?): File {
        val basePath = localPath?.trim().orEmpty()
        require(basePath.isNotEmpty()) { "localPath is required" }

        val start = File(basePath).canonicalFile
        val startDir = if (start.isDirectory) start else start.parentFile
            ?: error("Repository path is invalid: $basePath")

        // 수정 이유: 사용자가 하위 디렉터리를 선택해도 상위로 올라가며 .git/.svn을 자동 감지한다.
        findMetaFromAncestors(startDir)?.let { return it }

        // 수정 이유: 워크스페이스 루트가 입력된 경우를 위해 제한된 깊이에서 하위 디렉터리도 탐색한다.
        findMetaFromDescendants(startDir, maxDepth = 4)?.let { return it }

        error("Repository metadata directory not found under/above: $basePath")
    }

    private fun findMetaFromAncestors(startDir: File): File? {
        var current: File? = startDir
        while (current != null) {
            resolveMetaDirAt(current)?.let { return it }
            current = current.parentFile
        }
        return null
    }

    private fun findMetaFromDescendants(startDir: File, maxDepth: Int): File? {
        return runCatching {
            Files.walk(startDir.toPath(), maxDepth).use { stream ->
                stream
                    .filter(Files::isDirectory)
                    .map { dir -> resolveMetaDirAt(dir.toFile()) }
                    .filter { it != null }
                    .findFirst()
                    .orElse(null)
            }
        }.getOrNull()
    }

    private fun resolveMetaDirAt(dir: File): File? {
        if (!dir.isDirectory) return null
        if (dir.name.equals(".git", true) || dir.name.equals(".svn", true)) return dir

        val gitDir = File(dir, ".git")
        if (gitDir.isDirectory) return gitDir

        val svnDir = File(dir, ".svn")
        if (svnDir.isDirectory) return svnDir

        return null
    }

    private fun parseDateOrToday(raw: String?): LocalDate {
        val text = raw?.substringBefore("T")?.trim().orEmpty()
        return if (text.isBlank()) LocalDate.now() else LocalDate.parse(text)
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
        // 수정 이유: 작업 산출물을 공용 임시 루트 아래 UUID 폴더로 분리하면 요청 단위 정리가 안전해진다.
        val managedBase = extractionWorkRoot.resolve(UUID.randomUUID().toString()).toFile()
        if (!managedBase.exists()) managedBase.mkdirs()
        return managedBase
        /*

        

        
        
        
                ?: throw IllegalStateException("작업 디렉터리 기준으로 두 단계 상위가 존재하지 않습니다.")
            base = File(projectRoot.toAbsolutePath().resolve("GitInfoJarFile").toString(), UUID.randomUUID().toString())
        }

        if (!base.exists()) base.mkdirs()
        return base
        */
    }

    /** 디렉터리 → ZIP 압축 (상대 경로 유지) */
    private fun cleanupStaleExtractionDirs() {
        runCatching {
            Files.createDirectories(extractionWorkRoot)
            
            val expireBefore = System.currentTimeMillis() - STALE_WORK_DIR_RETENTION_HOURS * 60 * 60 * 1000

            cleanupStaleDirsUnder(extractionWorkRoot.toFile(), expireBefore)
            cleanupStaleDirsUnder(File("GitInfoJarFile"), expireBefore)
        
        
        
                    // 수정 이유: 비정상 종료로 남은 작업 폴더를 다음 요청에서 정리해 누적을 막는다.
        
        
        }.onFailure { error ->
            logger.warn("Failed to cleanup stale extraction directories: {}", extractionWorkRoot, error)
        }
    }

    private fun cleanupStaleDirsUnder(root: File, expireBefore: Long) {
        if (!root.exists() || !root.isDirectory) return
        root.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.filter { it.lastModified() < expireBefore }
            ?.forEach { oldDir ->
                oldDir.deleteRecursively()
            }
    }

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
