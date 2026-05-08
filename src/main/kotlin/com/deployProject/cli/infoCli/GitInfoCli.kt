package com.deployProject.cli.infoCli

import com.deployProject.cli.DeployCliScript
import com.deployProject.cli.utilCli.GitUtil
import com.deployProject.cli.utilCli.GitUtil.allowsDiff
import com.deployProject.cli.utilCli.GitUtil.allowsStatus
import com.deployProject.deploy.domain.site.FileStatusType
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.TreeWalk
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

class GitInfoCli {
    private val log = LoggerFactory.getLogger(GitInfoCli::class.java)

    private data class DiffSelection(
        val paths: List<String>,
        val revisionByPath: Map<String, String>
    ) {
        companion object {
            val EMPTY = DiffSelection(emptyList(), emptyMap())
        }
    }

    fun gitCliExecution(
        repoPath: String,
        since: LocalDate,
        until: LocalDate,
        fileStatusType: FileStatusType,
        deployServerDir: String,
        jdkPath: String?,
        sinceVersion: String?,
        untilVersion: String?,
        selectedVersions: List<String>,
        selectedFiles: List<String>,
        duplicateFileVersionMap: Map<String, String>
    ) {
        val workTree = GitUtil.parseDir(repoPath, "git")
        val outputDir = GitUtil.determineDesktopOutputDir()

        GitUtil.showProgressAndRun(
            title = "배포 파일 생성",
            initialMessage = "Git 변경 파일을 정리하고 있습니다.",
            detailMessage = "선택한 버전 기준 파일 수집, 클래스 생성, 패치 스크립트 생성을 진행합니다."
        ) {
            Git.open(workTree).use { git ->
                val repo = git.repository
                val artifactProfile = GitUtil.resolveArtifactProfile(workTree)
                val allowBuildArtifacts = GitUtil.profileUsesBuildArtifacts(artifactProfile)

                val statusPaths = if (fileStatusType.allowsStatus()) {
                    collectStatusPaths(git, since, until)
                } else {
                    emptyList()
                }

                val diffSelection = if (fileStatusType.allowsDiff()) {
                    collectDiffSelection(
                        repo = repo,
                        since = since,
                        until = until,
                        sinceVersion = sinceVersion,
                        untilVersion = untilVersion,
                        selectedVersions = selectedVersions,
                        selectedFiles = selectedFiles,
                        duplicateFileVersionMap = duplicateFileVersionMap
                    )
                } else {
                    DiffSelection.EMPTY
                }

                val statusEntries = if (statusPaths.isEmpty()) {
                    emptyList()
                } else {
                    if (artifactProfile == GitUtil.ArtifactProfile.JVM_CLASS_ONLY && statusPaths.any(::isJvmSourcePath)) {
                        GitUtil.compileJvmProject(workTree, jdkPath)
                    }
                    GitUtil.buildLatestClassMap(workTree, statusPaths)
                    GitUtil.normalizeExtractionPaths(
                        workTree,
                        GitUtil.mapPathsForExtraction(statusPaths, artifactProfile)
                    )
                }

                val diffEntries = if (diffSelection.paths.isEmpty()) {
                    emptyList()
                } else {
                    when (artifactProfile) {
                        GitUtil.ArtifactProfile.RAW_FILE_COPY ->
                            writeSelectedRevisionRawFiles(outputDir, repo, diffSelection.revisionByPath)
                        GitUtil.ArtifactProfile.JVM_CLASS_ONLY ->
                            writeSelectedRevisionArtifacts(outputDir, repo, diffSelection.revisionByPath, jdkPath)
                    }
                }

                if (artifactProfile == GitUtil.ArtifactProfile.JVM_CLASS_ONLY) {
                    val changedSourceCount = (statusPaths + diffSelection.paths).count(::isJvmSourcePath)
                    val classEntryCount = (statusEntries + diffEntries).count { it.endsWith(".class", ignoreCase = true) }
                    if (changedSourceCount > 0 && classEntryCount == 0) {
                        log.warn("No class artifacts found for selected Git revisions.")
                    }
                }

                if (fileStatusType.allowsStatus()) {
                    val selectedDiffEntrySet = diffEntries.toSet()
                    val statusEntriesToCopy = statusEntries.filterNot { it in selectedDiffEntrySet }
                    GitUtil.addDirectoryEntry(outputDir, workTree, statusEntriesToCopy, allowBuildArtifacts)
                }

                val allEntries = listOf(diffEntries, statusEntries).flatMap { it }.distinct()
                DeployCliScript().createDeployScript(allEntries, deployServerDir).forEach { (name, line) ->
                    GitUtil.writeTextOutputFile(outputDir, name, line.joinToString("\n"))
                }

                println("Git artifact profile: ${artifactProfile.name}")
                println("Git extraction summary: status=${statusEntries.size}, diff=${diffEntries.size}")
                println("Git selected revision files: ${diffSelection.revisionByPath.size}")
            }

            println("Extraction directory created: ${outputDir.absolutePath}")
        }
        GitUtil.notifyCompletionAndOpenDirectory(outputDir, "Git 배포 파일 생성이 완료되었습니다.")
    }

    private fun collectStatusPaths(git: Git, since: LocalDate, until: LocalDate): List<String> {
        val base = git.repository.workTree
        val dateZone = ZoneId.systemDefault()
        val status = git.status().call()

        return (status.added + status.changed + status.modified)
            .map { File(base, it) }
            .filter { file ->
                file.exists().also { if (!it) log.warn("Missing: {}", file.absolutePath) }
            }
            .filter { file ->
                val fileDate = Instant.ofEpochMilli(file.lastModified()).atZone(dateZone).toLocalDate()
                !fileDate.isBefore(since) && !fileDate.isAfter(until)
            }
            .map { it.absolutePath }
    }

    private fun collectDiffSelection(
        repo: Repository,
        since: LocalDate,
        until: LocalDate,
        sinceVersion: String?,
        untilVersion: String?,
        selectedVersions: List<String>,
        selectedFiles: List<String>,
        duplicateFileVersionMap: Map<String, String>
    ): DiffSelection {
        val zone = ZoneId.systemDefault()
        return Git(repo).use { git ->
            val commits = resolveTargetCommits(git, sinceVersion, untilVersion, selectedVersions).filter { commit ->
                val date = Instant.ofEpochMilli(commit.authorIdent.`when`.time).atZone(zone).toLocalDate()
                !date.isBefore(since) && !date.isAfter(until)
            }

            val selectedFileSet = selectedFiles.map(::normalizePath).toSet()
            val normalizedDuplicateMap = duplicateFileVersionMap
                .mapKeys { normalizePath(it.key) }
                .mapValues { it.value.trim() }
                .filterValues { it.isNotEmpty() }

            val pathCandidates = linkedMapOf<String, MutableList<String>>()

            commits.forEach { commit ->
                val commitId = commit.name
                repo.newObjectReader().use { reader ->
                    val parentTree = commit.parents.firstOrNull()?.let { parent ->
                        repo.resolve("${parent.name}^{tree}")?.let { parentTreeId ->
                            CanonicalTreeParser().apply { reset(reader, parentTreeId) }
                        }
                    }
                    val newTree = CanonicalTreeParser().apply {
                        reset(reader, repo.resolve("${commit.name}^{tree}"))
                    }

                    git.diff()
                        .setOldTree(parentTree)
                        .setNewTree(newTree)
                        .call()
                        .mapNotNull { entry ->
                            val path = if (entry.changeType == DiffEntry.ChangeType.DELETE) entry.oldPath else entry.newPath
                            normalizePath(path).takeUnless { it.isBlank() || it == DiffEntry.DEV_NULL }
                        }
                        .filter { path -> selectedFileSet.isEmpty() || path in selectedFileSet }
                        .forEach { path ->
                            pathCandidates.getOrPut(path) { mutableListOf() }.add(commitId)
                        }
                }
            }

            val revisionByPath = linkedMapOf<String, String>()
            pathCandidates.forEach { (path, revisions) ->
                selectRevisionForPath(path, revisions, normalizedDuplicateMap)?.let { chosenRevision ->
                    revisionByPath[path] = chosenRevision
                }
            }

            DiffSelection(
                paths = revisionByPath.keys.toList(),
                revisionByPath = revisionByPath
            )
        }
    }

    private fun writeSelectedRevisionRawFiles(
        outputDir: File,
        repo: Repository,
        revisionByPath: Map<String, String>
    ): List<String> {
        val writtenEntries = linkedSetOf<String>()

        revisionByPath.forEach { (path, revisionText) ->
            val revisionId = resolveRevisionByPrefix(repo, revisionText) ?: return@forEach
            val fileBytes = readFileBytesAtRevision(repo, revisionId, path)
            if (fileBytes == null) {
                log.warn("Git revision file not found: revision={}, path={}", revisionText, path)
                return@forEach
            }

            GitUtil.writeBinaryOutputFile(outputDir, path, fileBytes)
            writtenEntries.add(path)
        }

        return writtenEntries.toList()
    }

    private fun writeSelectedRevisionArtifacts(
        outputDir: File,
        repo: Repository,
        revisionByPath: Map<String, String>,
        jdkPath: String?
    ): List<String> {
        val writtenEntries = linkedSetOf<String>()

        revisionByPath.entries
            .groupBy({ it.value }, { it.key })
            .forEach { (revisionText, paths) ->
                val snapshotDir = Files.createTempDirectory("deploy-git-revision-").toFile()
                try {
                    materializeRevisionSnapshot(repo, revisionText, snapshotDir)

                    val sourcePaths = paths.filter(::isJvmSourcePath)
                    val rawPaths = paths.filterNot(::isJvmSourcePath)
                    val resolvedRevisionId = resolveRevisionByPrefix(repo, revisionText)

                    rawPaths.forEach { path ->
                        val fileBytes = resolvedRevisionId?.let { readFileBytesAtRevision(repo, it, path) }
                        if (fileBytes != null) {
                            GitUtil.writeBinaryOutputFile(outputDir, path, fileBytes)
                            writtenEntries.add(path)
                        }
                    }

                    if (sourcePaths.isNotEmpty()) {
                        GitUtil.compileJvmProject(snapshotDir, jdkPath)
                        GitUtil.buildLatestClassMap(snapshotDir, sourcePaths)
                        val mappedEntries = GitUtil.mapPathsForExtraction(sourcePaths, GitUtil.ArtifactProfile.JVM_CLASS_ONLY)
                        val actualEntries = GitUtil.addZipEntryName(
                            baseDir = snapshotDir,
                            paths = mappedEntries,
                            allowBuildArtifacts = true
                        ).toList()
                        GitUtil.addDirectoryEntry(
                            targetDir = outputDir,
                            baseDir = snapshotDir,
                            paths = mappedEntries,
                            allowBuildArtifacts = true
                        )
                        writtenEntries.addAll(actualEntries)
                    }
                } finally {
                    snapshotDir.deleteRecursively()
                }
            }

        return writtenEntries.toList()
    }

    private fun materializeRevisionSnapshot(repo: Repository, revisionText: String, targetDir: File) {
        val revisionId = resolveRevisionByPrefix(repo, revisionText)
            ?: error("Unable to resolve revision: $revisionText")

        RevWalk(repo).use { revWalk ->
            val commit = revWalk.parseCommit(revisionId)
            TreeWalk(repo).use { treeWalk ->
                treeWalk.addTree(commit.tree)
                treeWalk.isRecursive = true

                while (treeWalk.next()) {
                    if (treeWalk.getFileMode(0).objectType != Constants.OBJ_BLOB) continue
                    val outFile = File(targetDir, treeWalk.pathString.replace("/", File.separator))
                    outFile.parentFile?.mkdirs()
                    outFile.writeBytes(repo.open(treeWalk.getObjectId(0)).bytes)
                }
            }
        }
    }

    private fun normalizePath(path: String): String =
        path.replace("\\", "/").removePrefix("/").trim()

    private fun selectRevisionForPath(
        path: String,
        revisions: List<String>,
        duplicateFileVersionMap: Map<String, String>
    ): String? {
        val normalizedRevisions = revisions.distinct()
        if (normalizedRevisions.isEmpty()) return null

        val selectedRevision = duplicateFileVersionMap[path]
        if (!selectedRevision.isNullOrBlank()) {
            normalizedRevisions.firstOrNull { revision ->
                revision == selectedRevision ||
                    revision.startsWith(selectedRevision, ignoreCase = true) ||
                    selectedRevision.startsWith(revision, ignoreCase = true)
            }?.let { return it }
        }

        return normalizedRevisions.first()
    }

    private fun resolveRevisionByPrefix(repo: Repository, revisionText: String): ObjectId? {
        val trimmed = revisionText.trim()
        if (trimmed.isBlank()) return null

        runCatching { repo.resolve(trimmed) }.getOrNull()?.let { return it }

        return runCatching {
            Git(repo).use { git ->
                git.log().call().firstOrNull { it.name.startsWith(trimmed, ignoreCase = true) }?.id
            }
        }.getOrNull()
    }

    private fun readFileBytesAtRevision(repo: Repository, revisionId: ObjectId, path: String): ByteArray? {
        return runCatching {
            RevWalk(repo).use { revWalk ->
                val commit = revWalk.parseCommit(revisionId)
                TreeWalk.forPath(repo, path, commit.tree)?.use { treeWalk ->
                    repo.open(treeWalk.getObjectId(0)).bytes
                }
            }
        }.getOrNull()
    }

    private fun resolveTargetCommits(
        git: Git,
        sinceVersion: String?,
        untilVersion: String?,
        selectedVersions: List<String>
    ): List<RevCommit> {
        if (selectedVersions.isNotEmpty()) {
            return selectedVersions.mapNotNull { revision ->
                resolveRevision(git, revision)?.let { id ->
                    git.log().add(id).setMaxCount(1).call().firstOrNull()
                }
            }.distinctBy { it.name }
        }

        val fromCommit = resolveRevision(git, sinceVersion)
        val toCommit = resolveRevision(git, untilVersion)
        return loadCommitsByVersionRange(git, fromCommit, toCommit)
    }

    private fun resolveRevision(git: Git, revision: String?): ObjectId? {
        if (revision.isNullOrBlank()) return null
        return runCatching { git.repository.resolve(revision.trim()) }.getOrNull()
    }

    private fun loadCommitsByVersionRange(
        git: Git,
        fromCommit: ObjectId?,
        toCommit: ObjectId?
    ): List<RevCommit> {
        return when {
            fromCommit != null && toCommit != null && fromCommit == toCommit ->
                git.log().add(fromCommit).setMaxCount(1).call().toList()

            fromCommit != null && toCommit != null -> {
                val direct = git.log().addRange(fromCommit, toCommit).call().toList()
                if (direct.isNotEmpty()) direct else git.log().addRange(toCommit, fromCommit).call().toList()
            }

            toCommit != null -> git.log().add(toCommit).call().toList()

            fromCommit != null -> {
                val head = git.repository.resolve("HEAD") ?: return emptyList()
                git.log().add(head).not(fromCommit).call().toList()
            }

            else -> git.log().call().toList()
        }
    }

    private fun isJvmSourcePath(path: String): Boolean =
        path.endsWith(".kt", ignoreCase = true) || path.endsWith(".java", ignoreCase = true)
}
