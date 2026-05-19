package com.deployProject.deploy.service

import com.badlogicgames.packr.Packr
import com.badlogicgames.packr.PackrConfig
import com.deployProject.deploy.domain.extraction.ExtractionDto
import com.deployProject.deploy.domain.extraction.RepositoryDuplicateFileDto
import com.deployProject.deploy.domain.extraction.RepositoryVersionFileListDto
import com.deployProject.deploy.domain.extraction.RepositoryVersionListDto
import com.deployProject.deploy.domain.extraction.RepositoryVersionOptionDto
import com.deployProject.deploy.domain.extraction.TargetOsStatus
import com.deployProject.cli.utilCli.GitUtil
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
 * Bundles repository extraction output into a deployable desktop package.
 *
 * Flow:
 * 1. Ensure a target-specific minimal JRE exists under `custom-jre/<os>`.
 * 2. Build the extraction CLI fat jar.
 * 3. Wrap it with Packr, reusing a cached template when possible.
 * 4. Zip the final output directory and return it.
 */
@Service
class ExtractionService(
    private val jarCreator: JarCreator = JarCreator
) {
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

    /** Root directory for target-specific minimal JREs. */
    private val customJreBase: Path = runtimeWorkspaceRoot.resolve("custom-jre")

    // Keep extraction output under a managed workspace root instead of mixing
    // generated artifacts into source directories.
    private val extractionWorkRoot: Path = runtimeWorkspaceRoot.resolve("GitInfoJarFile")

    /** Root directory for cached Packr launcher templates. */
    private val packrTemplateBase: Path = runtimeWorkspaceRoot.resolve("packr-template")

    /** Prevent concurrent jlink runs from racing on the same target directory. */
    private val jlinkLock = Any()

    companion object {
        private const val DEPLOY_JAR_NAME = "deploy-project-cli.jar"
        private const val EXECUTABLE_BASE = "deploy-project-cli"
        private const val CFG_NAME = "deploy-project-cli.cfg"
        private const val MAIN_CLASS = "com.deployProject.cli.ExtractionLauncher"
        private const val VERSION_RESULT_LIMIT = 300
    }

    private val STALE_WORK_DIR_RETENTION_HOURS = 24L

    private val versionDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val selectionDelimiter = "|~|"
    private val keyValueDelimiter = "::"

    /** Main entry point for bundle generation. */
    fun extractGitInfo(extractionDto: ExtractionDto): File {
        cleanupStaleExtractionDirs()
        val repositoryContext = resolveRepositoryContext(extractionDto.localPath)
        logger.info("Deploy bundle generation started")

        val target = extractionDto.targetOs ?: error("targetOs is null")
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
        val requiresDiffSelection =
            repositoryContext.mode != RepositoryMode.LOCAL &&
                !extractionDto.fileStatusType.equals("STATUS", ignoreCase = true)

        if (requiresDiffSelection) {
            require(selectedVersions.isNotEmpty() || hasLegacyVersionRange) {
                "selectedVersions is required when DIFF is enabled"
            }
        }

        // 1. Ensure a target-specific runtime exists.
        ensureTargetJre(target, extractionDto.jdkPath)

        // 2. Create a per-request working directory.
        val baseDir = resolveBaseDir()
        val jarFile = File(baseDir, DEPLOY_JAR_NAME)

        // 3. Build the extraction CLI jar.
        val tJar = measureTimeMillis {
            logger.info("Building deploy CLI jar")
            jarCreator.main(
                listOf(
                    extractionDto.localPath ?: "",      // 0: repoDir
                    "",                                 // 1: relPath
                    extractionDto.since ?: "",          // 2: sinceDate
                    extractionDto.until ?: "",          // 3: untilDate
                    extractionDto.fileStatusType ?: "", // 4: fileStatusType
                    baseDir.absolutePath,                // 5: jarOutputDir
                    extractionDto.homePath ?: "",       // 6: deployServerDir
                    extractionDto.sinceVersion ?: "",   // 7: sinceVersion
                    extractionDto.untilVersion ?: "",   // 8: untilVersion
                    selectedVersions.joinToString(selectionDelimiter), // 9: selectedVersions
                    selectedFiles.joinToString(selectionDelimiter),    // 10: selectedFiles
                    encodeDuplicateFileVersionMap(duplicateFileVersionMap), // 11: duplicateFileVersionMap
                    extractionDto.jdkPath ?: "" // 12: jdkPath
                ).toTypedArray()
            )
        }
        logger.info("Deploy CLI jar generated ({} ms), path={}", tJar, jarFile.absolutePath)

        // 4. Reset the output directory for the wrapped executable.
        val outputDir = File(baseDir, "exe-output").apply { recreateDir() }

        // 5. Build or refresh the Packr wrapper template.
        packrTemplateDir(target).takeIf { it.exists() }?.deleteRecursively()
        val tPackr = measureTimeMillis {
            packWithPackr(jarFile, target, outputDir)
        }
        logger.info("Packr completed ({} ms)", tPackr)

        // 6. Zip the final bundle directory.
        val zipFile = File(outputDir.parentFile, "bundle-${target.name.lowercase()}.zip")
        zipDirectory(outputDir, zipFile)
        logger.info("ZIP generated: {}", zipFile.absolutePath)

        return zipFile
    }

    fun cleanupExtractionArtifacts(zipFile: File) {
        val workDir = zipFile.parentFile ?: return
        val workDirPath = runCatching { workDir.toPath().toRealPath() }.getOrElse { return }
        val managedRoot = runCatching {
            Files.createDirectories(extractionWorkRoot)
            extractionWorkRoot.toRealPath()
        }.getOrElse { return }

        // Only delete managed extraction work directories.
        if (!workDirPath.startsWith(managedRoot)) return

        runCatching {
            workDir.deleteRecursively()
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

    /** Wrap the generated jar with Packr and return the executable entry point. */
    private fun packWithPackr(
        jarFile: File,
        targetOs: TargetOsStatus,
        outputDir: File
    ): File {
        val exeName = exeNameFor(targetOs)
        val templateDir = packrTemplateDir(targetOs)
        val targetJre = targetJreDir(targetOs)

        // Keep the Packr classpath pinned to the generated jar filename.
        fun writeCfg(dir: File, jarName: String) {
            File(dir, CFG_NAME).writeText(
                buildString {
                    appendLine("classpath=$jarName")
                    appendLine("mainclass=$MAIN_CLASS")
                    appendLine("vmargs=-Xmx512m")
                }
            )
        }

        // Fast path: reuse the cached template when it already exists.
        if (templateDir.exists() && File(templateDir, exeName).exists()) {
            outputDir.recreateFrom(templateDir)
            jarFile.copyTo(File(outputDir, jarFile.name), overwrite = true)
            writeCfg(outputDir, jarFile.name)
            return File(outputDir, exeName)
        }

        // Slow path: build the Packr template once, then copy from it.
        if (templateDir.exists()) templateDir.deleteRecursively()
        templateDir.mkdirs()

        val javaBin = targetJre.resolve("bin").resolve(
            if (targetOs == TargetOsStatus.WINDOWS) "java.exe" else "java"
        )
        require(Files.exists(javaBin)) {
            "Target JRE was not found for ${targetOs.name}: $targetJre (bin/java is required)"
        }

        val config = PackrConfig().apply {
            platform = when (targetOs) {
                TargetOsStatus.WINDOWS -> PackrConfig.Platform.Windows64
                TargetOsStatus.MAC     -> PackrConfig.Platform.MacOS
                TargetOsStatus.LINUX   -> PackrConfig.Platform.Linux64
            }
            jdk = targetJre.toString()
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
        writeCfg(outputDir, jarFile.name)

        return File(outputDir, exeName)
    }

    /** Ensure that a target-specific minimal JRE exists. */
    private fun ensureTargetJre(target: TargetOsStatus, requestedJdkPath: String?) {
        val targetDir = targetJreDir(target)
        val javaName = if (target == TargetOsStatus.WINDOWS) "java.exe" else "java"
        val javaBin = targetDir.resolve("bin").resolve(javaName)

        if (Files.exists(javaBin)) {
            logger.info("Target JRE already exists: {}", targetDir)
            return
        }

        val hostMatchesTarget = when (target) {
            TargetOsStatus.WINDOWS -> hostOsName.contains("windows")
            TargetOsStatus.MAC     -> hostOsName.contains("mac")
            TargetOsStatus.LINUX   -> hostOsName.contains("linux")
        }
        if (!hostMatchesTarget) {
            throw IllegalStateException(
                "Target JRE is missing for ${target.name}: $targetDir\n" +
                    "This server (OS=$hostOsName) cannot generate that target runtime locally with jlink.\n" +
                    "Prepare the runtime on the target OS and upload it under custom-jre/<windows|mac|linux>.\n" +
                    "Expected path: $targetDir"
            )
        }

        synchronized(jlinkLock) {
            if (Files.exists(javaBin)) return

            val javaHome = resolveJlinkJavaHome(requestedJdkPath)
            logger.info("Generating target JRE with jlink: {}, javaHome={}", targetDir, javaHome)

            val modules = listOf(
                "java.base",
                "java.logging",
                "java.xml",
                "java.desktop",
                "jdk.unsupported",
                "jdk.crypto.ec",
            )

            Files.createDirectories(targetDir)
            JlinkCreator.createJlink(modules, targetDir, javaHome)

            require(Files.exists(javaBin)) { "jlink failed to create $javaBin" }
        }
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

    private fun resolveJlinkJavaHome(requestedJdkPath: String?): Path {
        val candidates = mutableListOf<Path>()

        requestedJdkPath
            ?.takeIf { it.isNotBlank() }
            ?.let { candidates.add(normalizeJavaHomePath(Path.of(it))) }

        System.getProperty("app.javaHome")
            ?.takeIf { it.isNotBlank() }
            ?.let { candidates.add(normalizeJavaHomePath(Path.of(it))) }

        System.getenv("JAVA_HOME")
            ?.takeIf { it.isNotBlank() }
            ?.let { candidates.add(normalizeJavaHomePath(Path.of(it))) }

        candidates.add(normalizeJavaHomePath(Path.of(System.getProperty("java.home"))))

        if (hostOsName.contains("windows")) {
            candidates.add(Path.of("C:/Program Files/Java/jdk-17"))
            candidates.add(Path.of("C:/Program Files/Java/jdk-21"))
        } else if (hostOsName.contains("mac")) {
            candidates.add(Path.of("/Users/mac/.sdkman/candidates/java/current"))
        } else {
            candidates.add(Path.of("/home/bjw/.sdkman/candidates/java/current"))
        }

        return candidates
            .distinct()
            .firstOrNull { hasJlink(it) }
            ?: error(
                "jlink executable was not found. Configure a full JDK path in jdkPath, JAVA_HOME, or -Dapp.javaHome."
            )
    }

    private fun normalizeJavaHomePath(path: Path): Path {
        val fileName = path.fileName?.toString()?.lowercase().orEmpty()
        return when {
            fileName == "bin" -> path.parent ?: path
            fileName == "java.exe" || fileName == "java" || fileName == "jlink.exe" || fileName == "jlink" ->
                path.parent?.parent ?: path
            else -> path
        }.toAbsolutePath()
    }

    private fun hasJlink(javaHome: Path): Boolean {
        val executable = javaHome.resolve("bin").resolve(if (hostOsName.contains("windows")) "jlink.exe" else "jlink")
        return Files.isExecutable(executable)
    }

    /** Target-specific JRE directory. */
    private fun targetJreDir(target: TargetOsStatus): Path =
        when (target) {
            TargetOsStatus.WINDOWS -> customJreBase.resolve("windows")
            TargetOsStatus.MAC     -> customJreBase.resolve("mac")
            TargetOsStatus.LINUX   -> customJreBase.resolve("linux")
        }

    /** Cached Packr template directory for each target OS. */
    private fun packrTemplateDir(targetOs: TargetOsStatus): File {
        val tag = when (targetOs) {
            TargetOsStatus.WINDOWS -> "windows"
            TargetOsStatus.MAC     -> "mac"
            TargetOsStatus.LINUX   -> "linux"
        }
        return packrTemplateBase.resolve(tag).toFile()
    }

    /** Executable name for the target OS. */
    private fun exeNameFor(targetOs: TargetOsStatus): String =
        if (targetOs == TargetOsStatus.WINDOWS) "$EXECUTABLE_BASE.exe" else EXECUTABLE_BASE

    /** Creates a per-request working directory under the managed extraction root. */
    private fun resolveBaseDir(): File {
        val managedBase = extractionWorkRoot.resolve(UUID.randomUUID().toString()).toFile()
        if (!managedBase.exists()) managedBase.mkdirs()
        return managedBase
    }

    /** Removes stale extraction work directories left by older requests. */
    private fun cleanupStaleExtractionDirs() {
        runCatching {
            Files.createDirectories(extractionWorkRoot)
            val expireBefore = System.currentTimeMillis() - STALE_WORK_DIR_RETENTION_HOURS * 60 * 60 * 1000
            cleanupStaleDirsUnder(extractionWorkRoot.toFile(), expireBefore)
            cleanupStaleDirsUnder(File("GitInfoJarFile"), expireBefore)
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

    /** Recreates a directory from scratch. */
    private fun File.recreateDir() {
        if (exists()) deleteRecursively()
        mkdirs()
    }

    /** Recreates this directory by copying the source tree into it. */
    private fun File.recreateFrom(src: File) {
        if (exists()) deleteRecursively()
        src.copyRecursively(this, overwrite = true)
    }
}
