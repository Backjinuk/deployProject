package com.deployProject.cli.infoCli

import com.deployProject.cli.DeployCliScript
import com.deployProject.cli.utilCli.GitUtil
import com.deployProject.cli.utilCli.GitUtil.allowsDiff
import com.deployProject.cli.utilCli.GitUtil.allowsStatus
import com.deployProject.deploy.domain.site.FileStatusType
import org.slf4j.LoggerFactory
import org.tmatesoft.svn.core.SVNDepth
import org.tmatesoft.svn.core.SVNException
import org.tmatesoft.svn.core.SVNURL
import org.tmatesoft.svn.core.wc.SVNClientManager
import org.tmatesoft.svn.core.wc.SVNRevision
import org.tmatesoft.svn.core.wc.SVNStatusType
import org.tmatesoft.svn.core.wc.SVNWCUtil
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.Date

class SvnInfoCli {
    private val log = LoggerFactory.getLogger(SvnInfoCli::class.java)

    private data class DiffSelection(
        val paths: List<String>,
        val revisionByPath: Map<String, String>
    ) {
        companion object {
            val EMPTY = DiffSelection(emptyList(), emptyMap())
        }
    }

    private data class WorkingCopyInfo(
        val url: SVNURL,
        val repositoryRelativePath: String?
    )

    fun svnCliExecution(
        repoPath: String,
        since: Date,
        until: Date,
        fileStatusType: FileStatusType,
        deployServerDir: String,
        jdkPath: String?,
        sinceVersion: String?,
        untilVersion: String?,
        selectedVersions: List<String>,
        selectedFiles: List<String>,
        duplicateFileVersionMap: Map<String, String>,
        requestedOutputDir: File? = null
    ): File {
        val svnDir = GitUtil.parseDir(repoPath, "svn")
        val workTree = if (svnDir.name == ".svn") svnDir.parentFile else svnDir
        val workTreeName = workTree.name
        val outputDir = requestedOutputDir ?: GitUtil.determineDesktopOutputDir()
        val artifactProfile = GitUtil.resolveArtifactProfile(workTree)
        val allowBuildArtifacts = GitUtil.profileUsesBuildArtifacts(artifactProfile)

        GitUtil.showProgressAndRun(
            title = "배포 파일 생성",
            initialMessage = "SVN 변경 파일을 정리하고 있습니다.",
            detailMessage = "선택한 리비전 기준 파일 수집, 클래스 생성, 패치 스크립트 생성을 진행합니다."
        ) {
            val statusPaths = GitUtil.logExtractionPhase(log, "svn.collect-status-paths") {
                if (fileStatusType.allowsStatus()) {
                    collectStatusPaths(workTree.path, since, until)
                } else {
                    emptyList()
                }
            }

            val diffSelection = GitUtil.logExtractionPhase(log, "svn.collect-diff-selection") {
                if (fileStatusType.allowsDiff()) {
                    collectDiffSelection(
                        root = workTree.path,
                        start = since,
                        end = until,
                        sinceVersion = sinceVersion,
                        untilVersion = untilVersion,
                        selectedVersions = selectedVersions,
                        selectedFiles = selectedFiles,
                        duplicateFileVersionMap = duplicateFileVersionMap,
                        workTreeName = workTreeName
                    )
                } else {
                    DiffSelection.EMPTY
                }
            }

            val statusEntries = if (statusPaths.isEmpty()) {
                emptyList()
            } else {
                if (artifactProfile == GitUtil.ArtifactProfile.JVM_CLASS_ONLY && statusPaths.any(::isJvmSourcePath)) {
                    GitUtil.logExtractionPhase(log, "svn.compile-status-jvm") {
                        GitUtil.compileJvmProject(workTree, jdkPath, statusPaths)
                    }
                }
                GitUtil.logExtractionPhase(log, "svn.build-status-class-map") {
                    GitUtil.buildLatestClassMap(workTree, statusPaths)
                }
                GitUtil.logExtractionPhase(log, "svn.map-status-extraction-paths") {
                    GitUtil.normalizeExtractionPaths(
                        workTree,
                        GitUtil.mapPathsForExtraction(statusPaths, artifactProfile)
                    )
                }
            }

            val diffEntries = if (diffSelection.paths.isEmpty()) {
                emptyList()
            } else {
                GitUtil.logExtractionPhase(log, "svn.write-diff-entries") {
                    when (artifactProfile) {
                        GitUtil.ArtifactProfile.RAW_FILE_COPY ->
                            writeSelectedRevisionRawFiles(outputDir, workTree, diffSelection.revisionByPath)
                        GitUtil.ArtifactProfile.JVM_CLASS_ONLY ->
                            writeSelectedRevisionArtifacts(outputDir, workTree, diffSelection.revisionByPath, jdkPath)
                    }
                }
            }

            if (artifactProfile == GitUtil.ArtifactProfile.JVM_CLASS_ONLY) {
                val changedSourceCount = (statusPaths + diffSelection.paths).count(::isJvmSourcePath)
                val classEntryCount = (statusEntries + diffEntries).count { it.endsWith(".class", ignoreCase = true) }
                if (changedSourceCount > 0 && classEntryCount == 0) {
                    log.warn("No class artifacts found for selected SVN revisions.")
                    System.err.println("[WARN] No class artifacts found for selected SVN revisions.")
                }
            }

            if (fileStatusType.allowsStatus()) {
                val selectedDiffEntrySet = diffEntries.toSet()
                val statusEntriesToCopy = statusEntries.filterNot { it in selectedDiffEntrySet }
                GitUtil.logExtractionPhase(log, "svn.copy-status-output-files") {
                    GitUtil.addDirectoryEntry(outputDir, workTree, statusEntriesToCopy, allowBuildArtifacts)
                }
            }

            val entries = (statusEntries + diffEntries).distinct()
            GitUtil.logExtractionPhase(log, "svn.write-deploy-scripts") {
                DeployCliScript().createDeployScript(entries, deployServerDir).forEach { (name, lines) ->
                    GitUtil.writeTextOutputFile(outputDir, name, lines.joinToString("\n"))
                }
            }

            log.info("Created output directory: {}", outputDir.absolutePath)
            println("SVN artifact profile: ${artifactProfile.name}")
            println("SVN extraction summary: status=${statusEntries.size}, diff=${diffEntries.size}, total=${entries.size}")
            println("SVN selected revision files: ${diffSelection.revisionByPath.size}")
            println("Extraction directory created: ${outputDir.absolutePath}")
        }
        GitUtil.notifyCompletionAndOpenDirectory(outputDir, "SVN 배포 파일 생성이 완료되었습니다.")
        return outputDir
    }

    private fun collectStatusPaths(root: String, since: Date, until: Date): List<String> {
        val client = createClientManagerWithCachedAuth()
        val zone = ZoneId.systemDefault()
        val files = mutableListOf<String>()

        client.statusClient.doStatus(
            File(root), true, false, false, false
        ) { status ->
            if (status.contentsStatus in listOf(
                    SVNStatusType.STATUS_MODIFIED,
                    SVNStatusType.STATUS_ADDED,
                    SVNStatusType.STATUS_DELETED,
                    SVNStatusType.STATUS_REPLACED
                )
            ) {
                val file = status.file
                val fileDate = Instant.ofEpochMilli(file.lastModified()).atZone(zone).toLocalDate()
                val sinceDate = since.toInstant().atZone(zone).toLocalDate()
                val untilDate = until.toInstant().atZone(zone).toLocalDate()
                if (!fileDate.isBefore(sinceDate) && !fileDate.isAfter(untilDate)) {
                    files.add(file.absolutePath)
                }
            }
        }
        return files
    }

    private fun collectDiffSelection(
        root: String,
        start: Date,
        end: Date,
        sinceVersion: String?,
        untilVersion: String?,
        selectedVersions: List<String>,
        selectedFiles: List<String>,
        duplicateFileVersionMap: Map<String, String>,
        workTreeName: String
    ): DiffSelection {
        val client = createClientManagerWithCachedAuth()
        val workingCopyInfo = resolveWorkingCopyInfo(client, File(root))
        println(
            "[INFO] SVN working copy repository path: " +
                (workingCopyInfo?.repositoryRelativePath?.takeIf { it.isNotBlank() } ?: ".")
        )
        val selectedFileSet = selectedFiles
            .mapNotNull {
                GitUtil.normalizeSvnRepositoryPath(
                    rawPath = it,
                    workTreeName = workTreeName,
                    workingCopyRepositoryPath = workingCopyInfo?.repositoryRelativePath
                )
            }
            .toSet()
        val normalizedDuplicateMap = duplicateFileVersionMap
            .mapNotNull { (path, revision) ->
                GitUtil.normalizeSvnRepositoryPath(
                    rawPath = path,
                    workTreeName = workTreeName,
                    workingCopyRepositoryPath = workingCopyInfo?.repositoryRelativePath
                )?.let { normalizedPath -> normalizedPath to revision.trim() }
            }
            .filter { (_, revision) -> revision.isNotEmpty() }
            .toMap()
        val pathCandidates = linkedMapOf<String, MutableList<String>>()

        try {
            if (selectedVersions.isNotEmpty()) {
                selectedVersions.mapNotNull { it.toLongOrNull() }.distinct().forEach { revision ->
                    val revisionId = revision.toString()
                    val rev = SVNRevision.create(revision)
                    client.logClient.doLog(arrayOf(File(root)), rev, rev, false, true, 1L) { logEntry ->
                        logEntry.changedPaths.values.forEach { change ->
                            val path = GitUtil.normalizeSvnRepositoryPath(
                                rawPath = change.path.orEmpty(),
                                workTreeName = workTreeName,
                                workingCopyRepositoryPath = workingCopyInfo?.repositoryRelativePath
                            )
                            if (!path.isNullOrBlank() && (selectedFileSet.isEmpty() || path in selectedFileSet)) {
                                pathCandidates.getOrPut(path) { mutableListOf() }.add(revisionId)
                            }
                        }
                    }
                }
            } else {
                val startRevision = parseSvnRevisionOrDate(sinceVersion, start)
                val endRevision = parseSvnRevisionOrDate(untilVersion, end)
                val (normalizedStart, normalizedEnd) = normalizeRevisionRange(startRevision, endRevision)

                client.logClient.doLog(arrayOf(File(root)), normalizedStart, normalizedEnd, false, true, 0L) { logEntry ->
                    val entryRevision = logEntry.revision.toString()
                    logEntry.changedPaths.values.forEach { change ->
                        val path = GitUtil.normalizeSvnRepositoryPath(
                            rawPath = change.path.orEmpty(),
                            workTreeName = workTreeName,
                            workingCopyRepositoryPath = workingCopyInfo?.repositoryRelativePath
                        )
                        if (!path.isNullOrBlank() && (selectedFileSet.isEmpty() || path in selectedFileSet)) {
                            pathCandidates.getOrPut(path) { mutableListOf() }.add(entryRevision)
                        }
                    }
                }
            }
        } catch (e: SVNException) {
            log.info("SVN log collection failed: {}", e.message)
        }

        val revisionByPath = linkedMapOf<String, String>()
        pathCandidates.forEach { (path, revisions) ->
            selectRevisionForPath(path, revisions, normalizedDuplicateMap)?.let { chosenRevision ->
                revisionByPath[path] = chosenRevision
            }
        }

        println(
            "[INFO] SVN diff selection: selectedFiles=${selectedFileSet.size}, " +
                "candidatePaths=${pathCandidates.size}, selectedPaths=${revisionByPath.size}"
        )

        return DiffSelection(
            paths = revisionByPath.keys.toList(),
            revisionByPath = revisionByPath
        )
    }

    private fun parseSvnRevisionOrDate(version: String?, date: Date): SVNRevision {
        val revision = version?.trim()?.toLongOrNull()
        return if (revision != null) SVNRevision.create(revision) else SVNRevision.create(date)
    }

    private fun normalizeRevisionRange(start: SVNRevision, end: SVNRevision): Pair<SVNRevision, SVNRevision> {
        val startNo = start.number
        val endNo = end.number
        return if (startNo >= 0 && endNo >= 0 && startNo > endNo) end to start else start to end
    }

    private fun selectRevisionForPath(
        path: String,
        revisions: List<String>,
        duplicateFileVersionMap: Map<String, String>
    ): String? {
        val normalizedRevisions = revisions.distinct()
        if (normalizedRevisions.isEmpty()) return null

        val selectedRevision = duplicateFileVersionMap[path]
        if (!selectedRevision.isNullOrBlank()) {
            normalizedRevisions.firstOrNull { revision -> revision == selectedRevision }?.let { return it }
        }

        return normalizedRevisions.first()
    }

    private fun writeSelectedRevisionRawFiles(
        outputDir: File,
        workTree: File,
        revisionByPath: Map<String, String>
    ): List<String> {
        val client = createClientManagerWithCachedAuth()
        val rootUrl = resolveWorkingCopyInfo(client, workTree)?.url ?: return emptyList()
        val writtenEntries = linkedSetOf<String>()

        revisionByPath.forEach { (path, revisionText) ->
            val revisionNo = revisionText.toLongOrNull() ?: return@forEach
            val fileBytes = readFileBytesAtRevision(client, rootUrl, path, revisionNo)
            if (fileBytes == null) {
                log.warn("SVN revision file not found: revision={}, path={}", revisionText, path)
                System.err.println("[WARN] SVN revision file not found: revision=$revisionText, path=$path")
                return@forEach
            }

            GitUtil.writeBinaryOutputFile(outputDir, path, fileBytes)
            writtenEntries.add(path)
        }

        return writtenEntries.toList()
    }

    private fun writeSelectedRevisionArtifacts(
        outputDir: File,
        workTree: File,
        revisionByPath: Map<String, String>,
        jdkPath: String?
    ): List<String> {
        val client = createClientManagerWithCachedAuth()
        val rootUrl = resolveWorkingCopyInfo(client, workTree)?.url ?: return emptyList()
        val writtenEntries = linkedSetOf<String>()

        revisionByPath.entries
            .groupBy({ it.value }, { it.key })
            .forEach { (revisionText, paths) ->
                val revisionNo = revisionText.toLongOrNull() ?: return@forEach
                val snapshotDir = Files.createTempDirectory("deploy-svn-revision-").toFile()
                try {
                    GitUtil.logExtractionPhase(log, "svn.export-revision-snapshot[$revisionText]") {
                        exportRevisionSnapshot(client, rootUrl, revisionNo, snapshotDir)
                    }

                    val sourcePaths = paths.filter(::isJvmSourcePath)
                    val rawPaths = paths.filterNot(::isJvmSourcePath)

                    if (rawPaths.isNotEmpty()) {
                        val rawEntries = GitUtil.logExtractionPhase(log, "svn.collect-revision-raw-entries[$revisionText]") {
                            GitUtil.addZipEntryName(snapshotDir, rawPaths).toList()
                        }
                        GitUtil.logExtractionPhase(log, "svn.copy-revision-raw-files[$revisionText]") {
                            GitUtil.addDirectoryEntry(outputDir, snapshotDir, rawPaths)
                        }
                        writtenEntries.addAll(rawEntries)
                    }

                    if (sourcePaths.isNotEmpty()) {
                        GitUtil.logExtractionPhase(log, "svn.compile-revision-jvm[$revisionText]") {
                            GitUtil.compileJvmProject(snapshotDir, jdkPath, sourcePaths)
                        }
                        GitUtil.logExtractionPhase(log, "svn.build-revision-class-map[$revisionText]") {
                            GitUtil.buildLatestClassMap(snapshotDir, sourcePaths)
                        }
                        val mappedEntries = GitUtil.mapPathsForExtraction(sourcePaths, GitUtil.ArtifactProfile.JVM_CLASS_ONLY)
                        val actualEntries = GitUtil.logExtractionPhase(log, "svn.collect-revision-artifact-entries[$revisionText]") {
                            GitUtil.addZipEntryName(
                                baseDir = snapshotDir,
                                paths = mappedEntries,
                                allowBuildArtifacts = true
                            ).toList()
                        }
                        if (actualEntries.isEmpty()) {
                            System.err.println(
                                "[WARN] No SVN class artifacts copied: revision=$revisionText, " +
                                    "sources=${sourcePaths.joinToString(",")}, mapped=${mappedEntries.joinToString(",")}"
                            )
                        }
                        GitUtil.logExtractionPhase(log, "svn.copy-revision-artifacts[$revisionText]") {
                            GitUtil.addDirectoryEntry(
                                targetDir = outputDir,
                                baseDir = snapshotDir,
                                paths = mappedEntries,
                                allowBuildArtifacts = true
                            )
                        }
                        writtenEntries.addAll(actualEntries)
                    }
                } finally {
                    snapshotDir.deleteRecursively()
                }
            }

        return writtenEntries.toList()
    }

    private fun resolveWorkingCopyInfo(client: SVNClientManager, workTree: File): WorkingCopyInfo? {
        return runCatching {
            val info = client.wcClient.doInfo(workTree, SVNRevision.WORKING)
            WorkingCopyInfo(
                url = info.url,
                repositoryRelativePath = GitUtil.normalizeSvnWorkingCopyRepositoryPath(
                    workingCopyUrlPath = info.url.path,
                    repositoryRootUrlPath = info.repositoryRootURL?.path
                )
            )
        }.getOrNull()
    }

    private fun readFileBytesAtRevision(
        client: SVNClientManager,
        rootUrl: SVNURL,
        path: String,
        revisionNo: Long
    ): ByteArray? {
        val normalizedPath = path.replace("\\", "/").removePrefix("/").trim()
        if (normalizedPath.isBlank()) return null

        return runCatching {
            val revision = SVNRevision.create(revisionNo)
            ByteArrayOutputStream().use { out ->
                client.wcClient.doGetFileContents(
                    rootUrl.appendPath(normalizedPath, false),
                    revision,
                    revision,
                    true,
                    out
                )
                out.toByteArray()
            }
        }.getOrNull()
    }

    private fun exportRevisionSnapshot(
        client: SVNClientManager,
        rootUrl: SVNURL,
        revisionNo: Long,
        targetDir: File
    ) {
        val revision = SVNRevision.create(revisionNo)
        client.updateClient.doExport(
            rootUrl,
            targetDir,
            revision,
            revision,
            null,
            true,
            SVNDepth.INFINITY
        )
    }

    private fun createClientManagerWithCachedAuth(): SVNClientManager {
        val dir = detectSvnConfigDir()
        require(dir.exists()) { "SVN config directory not found: ${dir.path}" }
        val opts = SVNWCUtil.createDefaultOptions(dir, true)
        val auth = SVNWCUtil.createDefaultAuthenticationManager(dir)
        return SVNClientManager.newInstance(opts, auth)
    }

    private fun detectSvnConfigDir(): File {
        val os = System.getProperty("os.name").lowercase()
        return if (os.contains("win")) File(System.getenv("APPDATA"), "Subversion")
        else File(System.getProperty("user.home"), ".subversion")
    }

    private fun isJvmSourcePath(path: String): Boolean =
        path.endsWith(".java", ignoreCase = true) || path.endsWith(".kt", ignoreCase = true)
}
