package com.deployProject.cli.infoCli

import com.deployProject.cli.DeployCliScript
import com.deployProject.cli.utilCli.GitUtil
import com.deployProject.cli.utilCli.GitUtil.addZipEntryName
import com.deployProject.cli.utilCli.GitUtil.allowsDiff
import com.deployProject.cli.utilCli.GitUtil.allowsStatus
import com.deployProject.deploy.domain.site.FileStatusType
import org.slf4j.LoggerFactory
import org.tmatesoft.svn.core.SVNException
import org.tmatesoft.svn.core.wc.SVNClientManager
import org.tmatesoft.svn.core.wc.SVNRevision
import org.tmatesoft.svn.core.wc.SVNStatusType
import org.tmatesoft.svn.core.wc.SVNWCUtil
import java.awt.GraphicsEnvironment
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import javax.swing.JOptionPane

class SvnInfoCli {
    private val log = LoggerFactory.getLogger(SvnInfoCli::class.java)

    fun svnCliExecution(
        repoPath: String,
        since: Date,
        until: Date,
        fileStatusType: FileStatusType,
        deployServerDir: String,
        sinceVersion: String?,
        untilVersion: String?,
        selectedVersions: List<String>,
        selectedFiles: List<String>,
        duplicateFileVersionMap: Map<String, String>
    ) {
        val svnDir = GitUtil.parseDir(repoPath, "svn")
        val workTree = if (svnDir.name == ".svn") svnDir.parentFile else svnDir
        val workTreeName = workTree.name
        val outputDir = GitUtil.determineDesktopOutputDir()
        val artifactProfile = GitUtil.resolveArtifactProfile(workTree)
        val allowBuildArtifacts = GitUtil.profileUsesBuildArtifacts(artifactProfile)

        GitUtil.showProgressAndRun(title = "SVN Processing", initialMessage = "SVN extraction started") {
            val statusPaths = if (fileStatusType.allowsStatus()) {
                collectStatusPaths(svnDir.path, since, until)
            } else {
                emptyList()
            }

            val diffPaths = if (fileStatusType.allowsDiff()) {
                collectDiffPaths(
                    root = svnDir.path,
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
                emptyList()
            }

            GitUtil.buildLatestClassMap(workTree, statusPaths + diffPaths)
            val statusEntries = if (statusPaths.isEmpty()) {
                emptyList()
            } else {
                GitUtil.mapPathsForExtraction(statusPaths, artifactProfile)
            }
            val diffEntries = if (diffPaths.isEmpty()) {
                emptyList()
            } else {
                GitUtil.mapPathsForExtraction(diffPaths, artifactProfile)
            }
            if (artifactProfile == GitUtil.ArtifactProfile.JVM_CLASS_ONLY) {
                val changedSourceCount = (statusPaths + diffPaths).count {
                    it.endsWith(".java", ignoreCase = true) || it.endsWith(".kt", ignoreCase = true)
                }
                val classEntryCount = (statusEntries + diffEntries).count { it.endsWith(".class", ignoreCase = true) }
                // Modified because class-only mode can look empty when compiled outputs are missing.
                if (changedSourceCount > 0 && classEntryCount == 0) {
                    log.warn("No class artifacts found. Build the project before extraction.")
                }
            }
            val entries = addZipEntryName(
                baseDir = workTree,
                paths = statusEntries + diffEntries,
                allowBuildArtifacts = allowBuildArtifacts
            ).toList()

            // 수정 이유: exe 실행 시 zip 대신 바탕화면 디렉토리 결과물을 바로 확인할 수 있도록 변경한다.
            if (fileStatusType.allowsStatus()) {
                GitUtil.addDirectoryEntry(outputDir, workTree, statusEntries, allowBuildArtifacts)
            }
            if (fileStatusType.allowsDiff()) {
                GitUtil.addDirectoryEntry(outputDir, workTree, diffEntries, allowBuildArtifacts)
            }

            DeployCliScript().createDeployScript(entries, deployServerDir).forEach { (name, lines) ->
                GitUtil.writeTextOutputFile(outputDir, name, lines.joinToString("\n"))
            }

            // 수정 이유: 중복 파일에서 선택한 SVN 리비전의 실제 파일 내용을 결과물에 반영한다.
            val appliedOverrides = if (artifactProfile == GitUtil.ArtifactProfile.RAW_FILE_COPY) {
                val duplicateSelections = duplicateFileVersionMap
                    .mapKeys { normalizePath(it.key, workTreeName) }
                    .mapValues { it.value.trim() }
                    .filter { (path, version) -> path.isNotEmpty() && version.isNotEmpty() }
                applyDuplicateVersionSelections(outputDir, workTree, duplicateSelections, workTreeName)
            } else {
                // Modified because class-only extraction cannot override source text blobs reliably.
                0
            }

            log.info("Created output directory: {}", outputDir.absolutePath)
            println("SVN artifact profile: ${artifactProfile.name}")
            println("SVN extraction summary: status=${statusEntries.size}, diff=${diffEntries.size}, total=${entries.size}")
            println("SVN duplicate overrides applied: $appliedOverrides")
            println("Extraction directory created: ${outputDir.absolutePath}")
        }

        val doneMessage = "Extraction completed.\nOutput: ${outputDir.absolutePath}"
        if (GraphicsEnvironment.isHeadless()) {
            println(doneMessage)
        } else {
            JOptionPane.showMessageDialog(
                null,
                doneMessage,
                "Done",
                JOptionPane.INFORMATION_MESSAGE
            )
        }
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
                val date = Instant.ofEpochMilli(file.lastModified()).atZone(zone).toLocalDate()
                val sinceDate = since.toInstant().atZone(zone).toLocalDate()
                val untilDate = until.toInstant().atZone(zone).toLocalDate()
                if (!date.isBefore(sinceDate) && !date.isAfter(untilDate)) {
                    files.add(file.absolutePath)
                }
            }
        }
        return files
    }

    private fun collectDiffPaths(
        root: String,
        start: Date,
        end: Date,
        sinceVersion: String?,
        untilVersion: String?,
        selectedVersions: List<String>,
        selectedFiles: List<String>,
        duplicateFileVersionMap: Map<String, String>,
        workTreeName: String
    ): List<String> {
        val client = createClientManagerWithCachedAuth()
        val paths = linkedSetOf<String>()
        val selectedFileSet = selectedFiles.map { normalizePath(it, workTreeName) }.toSet()
        val normalizedDuplicateMap = duplicateFileVersionMap
            .mapKeys { normalizePath(it.key, workTreeName) }
            .mapValues { it.value.trim() }
            .filterValues { it.isNotEmpty() }

        try {
            if (selectedVersions.isNotEmpty()) {
                selectedVersions.mapNotNull { it.toLongOrNull() }.distinct().forEach { revision ->
                    val revisionId = revision.toString()
                    val rev = SVNRevision.create(revision)
                    client.logClient.doLog(arrayOf(File(root)), rev, rev, false, true, 1L) { logEntry ->
                        logEntry.changedPaths.values.forEach { change ->
                            val normalized = normalizePath(change.path.orEmpty(), workTreeName)
                            val forcedRevision = normalizedDuplicateMap[normalized]
                            val isSelectedFile = selectedFileSet.isEmpty() || normalized in selectedFileSet
                            val matchesRevision = forcedRevision == null || forcedRevision == revisionId
                            if (normalized.isNotBlank() && isSelectedFile && matchesRevision) {
                                paths.add(normalized)
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
                        val normalized = normalizePath(change.path.orEmpty(), workTreeName)
                        val forcedRevision = normalizedDuplicateMap[normalized]
                        val isSelectedFile = selectedFileSet.isEmpty() || normalized in selectedFileSet
                        val matchesRevision = forcedRevision == null || forcedRevision == entryRevision
                        if (normalized.isNotBlank() && isSelectedFile && matchesRevision) {
                            paths.add(normalized)
                        }
                    }
                }
            }
        } catch (e: SVNException) {
            log.info("SVN log collection failed: {}", e.message)
        }

        return paths.toList()
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

    private fun normalizePath(path: String, workTreeName: String): String {
        var normalized = path.replace("\\", "/").removePrefix("/").trim()
        val rootPrefix = "$workTreeName/"
        if (normalized.startsWith(rootPrefix, ignoreCase = true)) {
            normalized = normalized.substring(rootPrefix.length)
        }
        return normalized.removePrefix("./")
    }

    private fun applyDuplicateVersionSelections(
        outputDir: File,
        workTree: File,
        duplicateFileVersionMap: Map<String, String>,
        workTreeName: String
    ): Int {
        val client = createClientManagerWithCachedAuth()
        var applied = 0

        duplicateFileVersionMap.forEach { (path, revisionText) ->
            val revisionNo = revisionText.toLongOrNull() ?: return@forEach
            val normalizedPath = normalizePath(path, workTreeName)
            val workingFile = File(workTree, normalizedPath.replace("/", File.separator))
            if (!workingFile.exists()) return@forEach

            val bytes = runCatching {
                ByteArrayOutputStream().use { out ->
                    client.wcClient.doGetFileContents(
                        workingFile,
                        SVNRevision.WORKING,
                        SVNRevision.create(revisionNo),
                        true,
                        out
                    )
                    out.toByteArray()
                }
            }.getOrNull() ?: return@forEach

            GitUtil.writeBinaryOutputFile(outputDir, normalizedPath, bytes)
            applied += 1
        }

        return applied
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
}
