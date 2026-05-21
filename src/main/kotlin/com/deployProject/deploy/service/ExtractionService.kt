package com.deployProject.deploy.service

import com.deployProject.cli.infoCli.GitInfoCli
import com.deployProject.cli.infoCli.LocalInfoCli
import com.deployProject.cli.infoCli.SvnInfoCli
import com.deployProject.cli.utilCli.GitUtil
import com.deployProject.deploy.domain.extraction.ExtractionDto
import com.deployProject.deploy.domain.extraction.RepositoryDuplicateFileDto
import com.deployProject.deploy.domain.extraction.RepositoryVersionFileListDto
import com.deployProject.deploy.domain.extraction.RepositoryVersionListDto
import com.deployProject.deploy.domain.extraction.RepositoryVersionOptionDto
import jakarta.annotation.PostConstruct
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
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


/**
 * Bundles repository extraction output into a downloadable package.
 *
 * Flow:
 * 1. Create a managed per-request output directory.
 * 2. Execute the extraction logic in the local desktop server process.
 * 3. Zip the final output directory and return it.
 */
@Service
class ExtractionService {
    private val logger = LoggerFactory.getLogger(ExtractionService::class.java)

    private enum class RepositoryMode {
        GIT,
        SVN,
        LOCAL
    }

    private data class RepositoryContext(
        val mode: RepositoryMode,
        val workTree: File
    )

    /** Current host OS name used for runtime branching. */
    private val hostOsName = System.getProperty("os.name").lowercase()

    private val runtimeWorkspaceRoot: Path = resolveRuntimeWorkspaceRoot()

    // Keep extraction output under a managed workspace root instead of mixing
    // generated artifacts into source directories.
    private val extractionWorkRoot: Path = runtimeWorkspaceRoot.resolve("GitInfoJarFile")

    companion object {
        private const val VERSION_RESULT_LIMIT = 300
    }

    private val STALE_WORK_DIR_RETENTION_HOURS = 24L

    private val versionDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @PostConstruct
    fun cleanupExtractionWorkspaceOnStartup() {
        GitUtil.logExtractionPhase(logger, "cleanup-extraction-workspace-on-startup") {
            cleanupExtractionDirs(deleteAll = true)
        }
    }

    /** Main entry point for package generation. */
    fun extractGitInfo(extractionDto: ExtractionDto): File {
        GitUtil.logExtractionPhase(logger, "cleanup-stale-extraction-dirs") {
            cleanupStaleExtractionDirs()
        }
        val repositoryContext = GitUtil.logExtractionPhase(logger, "resolve-repository-context") {
            resolveRepositoryContext(extractionDto.localPath)
        }

        val targetName = extractionDto.targetOs?.name?.lowercase() ?: "local"
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

        logger.info(
            "Deploy package generation started: mode={}, workTree={}, fileStatusType={}, selectedVersions={}, selectedFiles={}, duplicateSelections={}",
            repositoryContext.mode,
            repositoryContext.workTree.absolutePath,
            extractionDto.fileStatusType,
            selectedVersions.size,
            selectedFiles.size,
            duplicateFileVersionMap.size
        )

        val hasLegacyVersionRange = !extractionDto.sinceVersion.isNullOrBlank() && !extractionDto.untilVersion.isNullOrBlank()
        val requiresDiffSelection =
            repositoryContext.mode != RepositoryMode.LOCAL &&
                !extractionDto.fileStatusType.equals("STATUS", ignoreCase = true)

        if (requiresDiffSelection) {
            require(selectedVersions.isNotEmpty() || hasLegacyVersionRange) {
                "selectedVersions is required when DIFF is enabled"
            }
        }

        // 1. Create a per-request working directory.
        var baseDir: File? = null
        try {
            val workDir = GitUtil.logExtractionPhase(logger, "prepare-work-directory") {
                resolveBaseDir()
            }
            baseDir = workDir
            val outputDir = GitUtil.logExtractionPhase(logger, "prepare-output-directory") {
                File(workDir, "deploy-output").apply { recreateDir() }
            }

            // 2. Execute the extraction immediately on the local desktop server.
            GitUtil.logExtractionPhase(logger, "run-local-extraction") {
                GitUtil.withoutProgressDialog {
                    runLocalExtraction(
                        extractionDto = extractionDto,
                        repositoryContext = repositoryContext,
                        outputDir = outputDir,
                        selectedVersions = selectedVersions,
                        selectedFiles = selectedFiles,
                        duplicateFileVersionMap = duplicateFileVersionMap
                    )
                }
            }
            logger.info("Deploy extraction output prepared: output={}", outputDir.absolutePath)

            // 3. Zip the final package directory.
            val zipFile = File(workDir, "deploy-package-$targetName.zip")
            GitUtil.logExtractionPhase(logger, "zip-output-directory") {
                zipDirectory(outputDir, zipFile)
            }
            logger.info(
                "Deploy package generation finished: zip={}, sizeBytes={}",
                zipFile.absolutePath,
                zipFile.length()
            )

            return zipFile
        } catch (error: Throwable) {
            baseDir?.let { cleanupExtractionWorkDirectory(it) }
            throw error
        }
    }

    private fun runLocalExtraction(
        extractionDto: ExtractionDto,
        repositoryContext: RepositoryContext,
        outputDir: File,
        selectedVersions: List<String>,
        selectedFiles: List<String>,
        duplicateFileVersionMap: Map<String, String>
    ) {
        val rawSinceDate = parseDateOrToday(extractionDto.since)
        val rawUntilDate = parseDateOrToday(extractionDto.until)
        val sinceDate = minOf(rawSinceDate, rawUntilDate)
        val untilDate = maxOf(rawSinceDate, rawUntilDate)
        val statusType = GitUtil.parseStatusType(extractionDto.fileStatusType)
        val deployServerDir = extractionDto.homePath?.trim().takeUnless { it.isNullOrBlank() }
            ?: "/home/bjw/deployProject/."

        when (repositoryContext.mode) {
            RepositoryMode.GIT -> GitInfoCli().gitCliExecution(
                repoPath = repositoryContext.workTree.path,
                since = sinceDate,
                until = untilDate,
                fileStatusType = statusType,
                deployServerDir = deployServerDir,
                jdkPath = extractionDto.jdkPath,
                sinceVersion = extractionDto.sinceVersion,
                untilVersion = extractionDto.untilVersion,
                selectedVersions = selectedVersions,
                selectedFiles = selectedFiles,
                duplicateFileVersionMap = duplicateFileVersionMap,
                outputDir = outputDir,
                openOnCompletion = false
            )

            RepositoryMode.SVN -> SvnInfoCli().svnCliExecution(
                repoPath = repositoryContext.workTree.path,
                since = dateAtStartOfDay(sinceDate),
                until = dateAtStartOfDay(untilDate),
                fileStatusType = statusType,
                deployServerDir = deployServerDir,
                jdkPath = extractionDto.jdkPath,
                sinceVersion = extractionDto.sinceVersion,
                untilVersion = extractionDto.untilVersion,
                selectedVersions = selectedVersions,
                selectedFiles = selectedFiles,
                duplicateFileVersionMap = duplicateFileVersionMap,
                requestedOutputDir = outputDir
            )

            RepositoryMode.LOCAL -> LocalInfoCli().localCliExecution(
                repoPath = repositoryContext.workTree.path,
                since = sinceDate,
                until = untilDate,
                fileStatusType = statusType,
                deployServerDir = deployServerDir,
                jdkPath = extractionDto.jdkPath,
                selectedFiles = selectedFiles,
                requestedOutputDir = outputDir
            )
        }
    }

    private fun dateAtStartOfDay(date: LocalDate): Date =
        Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant())

    fun cleanupExtractionArtifacts(zipFile: File) {
        val workDir = zipFile.parentFile ?: return
        cleanupExtractionWorkDirectory(workDir)
    }

    private fun cleanupExtractionWorkDirectory(workDir: File) {
        val workDirPath = runCatching { workDir.toPath().toRealPath() }.getOrElse { return }
        val managedRoots = managedExtractionRoots()

        // Only delete managed extraction work directories.
        if (managedRoots.none { workDirPath.startsWith(it) }) return

        runCatching {
            workDir.deleteRecursively()
            val workDirParent = workDirPath.parent
            managedRoots
                .filter { root -> workDirParent == root }
                .forEach { root -> deleteDirectoryIfEmpty(root.toFile()) }
        }.onFailure { error ->
            logger.warn("Failed to cleanup extraction work directory: {}", workDir.absolutePath, error)
        }
    }
    fun listRepositoryVersions(extractionDto: ExtractionDto): RepositoryVersionListDto {
        val repositoryContext = resolveRepositoryContext(extractionDto.localPath)
        val rawSinceDate = parseDateOrToday(extractionDto.since)
        val rawUntilDate = parseDateOrToday(extractionDto.until)
        val sinceDate = minOf(rawSinceDate, rawUntilDate)
        val untilDate = maxOf(rawSinceDate, rawUntilDate)

        return when (repositoryContext.mode) {
            RepositoryMode.GIT -> listGitVersions(repositoryContext.workTree, sinceDate, untilDate)
            RepositoryMode.SVN -> listSvnVersions(repositoryContext.workTree, sinceDate, untilDate)
            RepositoryMode.LOCAL -> RepositoryVersionListDto(vcsType = "LOCAL", versions = emptyList())
        }
    }

    fun listVersionFiles(extractionDto: ExtractionDto): RepositoryVersionFileListDto {
        val repositoryContext = resolveRepositoryContext(extractionDto.localPath)
        val selectedVersions = extractionDto.selectedVersions.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val rawSinceDate = parseDateOrToday(extractionDto.since)
        val rawUntilDate = parseDateOrToday(extractionDto.until)
        val sinceDate = minOf(rawSinceDate, rawUntilDate)
        val untilDate = maxOf(rawSinceDate, rawUntilDate)

        if (repositoryContext.mode == RepositoryMode.LOCAL) {
            return listLocalVersionFiles(repositoryContext.workTree, sinceDate, untilDate)
        }

        if (selectedVersions.isEmpty()) {
            return when (repositoryContext.mode) {
                RepositoryMode.GIT -> RepositoryVersionFileListDto(vcsType = "GIT", files = emptyList())
                RepositoryMode.SVN -> RepositoryVersionFileListDto(vcsType = "SVN", files = emptyList())
                RepositoryMode.LOCAL -> RepositoryVersionFileListDto(vcsType = "LOCAL", files = emptyList())
            }
        }

        return when (repositoryContext.mode) {
            RepositoryMode.GIT -> listGitVersionFiles(repositoryContext.workTree, selectedVersions)
            RepositoryMode.SVN -> listSvnVersionFiles(repositoryContext.workTree, selectedVersions)
            RepositoryMode.LOCAL -> RepositoryVersionFileListDto(vcsType = "LOCAL", files = emptyList())
        }
    }

    private fun listLocalVersionFiles(workTree: File, sinceDate: LocalDate, untilDate: LocalDate): RepositoryVersionFileListDto {
        val files = GitUtil.collectModifiedFilesByDate(workTree, sinceDate, untilDate)
        return RepositoryVersionFileListDto(vcsType = "LOCAL", files = files, duplicateFiles = emptyList())
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
            val workingCopyRepositoryPath = resolveSvnWorkingCopyRepositoryPath(client, workTree)
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
                            val normalized = GitUtil.normalizeSvnRepositoryPath(
                                rawPath = path,
                                workTreeName = workTree.name,
                                workingCopyRepositoryPath = workingCopyRepositoryPath
                            )
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

    private fun createSvnClientManager(): SVNClientManager {
        val configDir = detectSvnConfigDir()
        require(configDir.exists()) { "SVN config directory not found: ${configDir.path}" }
        val opts = SVNWCUtil.createDefaultOptions(configDir, true)
        val auth = SVNWCUtil.createDefaultAuthenticationManager(configDir)
        return SVNClientManager.newInstance(opts, auth)
    }

    private fun resolveSvnWorkingCopyRepositoryPath(client: SVNClientManager, workTree: File): String? {
        return runCatching {
            val info = client.wcClient.doInfo(workTree, SVNRevision.WORKING)
            GitUtil.normalizeSvnWorkingCopyRepositoryPath(
                workingCopyUrlPath = info.url.path,
                repositoryRootUrlPath = info.repositoryRootURL?.path
            )
        }.getOrNull()
    }

    private fun detectSvnConfigDir(): File {
        return if (hostOsName.contains("windows")) {
            File(System.getenv("APPDATA"), "Subversion")
        } else {
            File(System.getProperty("user.home"), ".subversion")
        }
    }

    private fun resolveRepositoryContext(localPath: String?): RepositoryContext {
        val basePath = localPath?.trim().orEmpty()
        require(basePath.isNotEmpty()) { "localPath is required" }

        val start = File(basePath).canonicalFile
        val startDir = if (start.isDirectory) start else start.parentFile
            ?: error("Repository path is invalid: $basePath")

        findMetaFromAncestors(startDir)?.let { metaDir ->
            return RepositoryContext(
                mode = if (metaDir.name.equals(".git", ignoreCase = true)) RepositoryMode.GIT else RepositoryMode.SVN,
                workTree = metaDir.parentFile
            )
        }

        findMetaFromDescendants(startDir, maxDepth = 4)?.let { metaDir ->
            return RepositoryContext(
                mode = if (metaDir.name.equals(".git", ignoreCase = true)) RepositoryMode.GIT else RepositoryMode.SVN,
                workTree = metaDir.parentFile
            )
        }

        return RepositoryContext(
            mode = RepositoryMode.LOCAL,
            workTree = startDir
        )
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

    /* -------------------------- Paths & Utils -------------------------- */

    private fun resolveRuntimeWorkspaceRoot(): Path {
        System.getProperty("deploy.workspace")
            ?.takeIf { it.isNotBlank() }
            ?.let { return Files.createDirectories(Path.of(it).toAbsolutePath()) }

        val currentDir = Paths.get("").toAbsolutePath()
        if (Files.isWritable(currentDir)) return currentDir

        val fallback = Paths.get(System.getProperty("user.home"), ".deploy-project", "runtime")
        return Files.createDirectories(fallback)
    }

    /** Creates a per-request working directory under the managed extraction root. */
    private fun resolveBaseDir(): File {
        val managedBase = extractionWorkRoot.resolve(UUID.randomUUID().toString()).toFile()
        if (!managedBase.exists()) managedBase.mkdirs()
        return managedBase
    }

    /** Removes stale extraction work directories left by older requests. */
    private fun cleanupStaleExtractionDirs() {
        cleanupExtractionDirs(deleteAll = false)
    }

    private fun cleanupExtractionDirs(deleteAll: Boolean) {
        runCatching {
            val expireBefore = System.currentTimeMillis() - STALE_WORK_DIR_RETENTION_HOURS * 60 * 60 * 1000
            cleanupDirsUnder(extractionWorkRoot.toFile(), expireBefore, deleteAll)
            cleanupDirsUnder(File("GitInfoJarFile"), expireBefore, deleteAll)
        }.onFailure { error ->
            logger.warn("Failed to cleanup stale extraction directories: {}", extractionWorkRoot, error)
        }
    }

    private fun cleanupDirsUnder(root: File, expireBefore: Long, deleteAll: Boolean) {
        if (!root.exists() || !root.isDirectory) return
        root.listFiles()
            ?.asSequence()
            ?.filter { deleteAll || it.lastModified() < expireBefore }
            ?.forEach { oldArtifact ->
                oldArtifact.deleteRecursively()
            }
        deleteDirectoryIfEmpty(root)
    }

    private fun managedExtractionRoots(): List<Path> {
        return listOf(
            extractionWorkRoot,
            Path.of("GitInfoJarFile").toAbsolutePath().normalize()
        ).mapNotNull { root ->
            runCatching {
                if (Files.exists(root)) root.toRealPath() else root.toAbsolutePath().normalize()
            }.getOrNull()
        }.distinct()
    }

    private fun deleteDirectoryIfEmpty(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        if (dir.listFiles()?.isEmpty() == true) {
            dir.delete()
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

    /** Recreates a directory from scratch. */
    private fun File.recreateDir() {
        if (exists()) deleteRecursively()
        mkdirs()
    }

}
