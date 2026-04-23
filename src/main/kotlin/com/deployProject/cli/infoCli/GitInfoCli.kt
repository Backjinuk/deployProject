package com.deployProject.cli.infoCli

import com.deployProject.cli.DeployCliScript
import com.deployProject.cli.utilCli.GitUtil
import com.deployProject.cli.utilCli.GitUtil.allowsDiff
import com.deployProject.cli.utilCli.GitUtil.allowsStatus
import com.deployProject.deploy.domain.site.FileStatusType
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.slf4j.LoggerFactory
import java.awt.GraphicsEnvironment
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import javax.swing.JOptionPane

class GitInfoCli {
    private val log = LoggerFactory.getLogger(GitInfoCli::class.java)

    fun gitCliExecution(
        repoPath: String,
        since: LocalDate,
        until: LocalDate,
        fileStatusType: FileStatusType,
        deployServerDir: String,
        sinceVersion: String?,
        untilVersion: String?,
        selectedVersions: List<String>,
        selectedFiles: List<String>,
        duplicateFileVersionMap: Map<String, String>
    ) {
        val workTree = GitUtil.parseDir(repoPath, "git")
        val outputDir = GitUtil.determineDesktopOutputDir()

        GitUtil.showProgressAndRun(initialMessage = "Git deploy extracting...") {
            Git.open(workTree).use { git ->
                val repo = git.repository
                val artifactProfile = GitUtil.resolveArtifactProfile(workTree)
                val allowBuildArtifacts = GitUtil.profileUsesBuildArtifacts(artifactProfile)

                val statusPaths = if (fileStatusType.allowsStatus()) {
                    collectStatusPaths(git, since, until)
                } else {
                    emptyList()
                }

                val diffPaths = if (fileStatusType.allowsDiff()) {
                    collectDiffPaths(
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
                    // Modified because class-only mode can look empty when build artifacts do not exist.
                    if (changedSourceCount > 0 && classEntryCount == 0) {
                        log.warn("No class artifacts found. Build the project before extraction.")
                    }
                }

                // 수정 이유: exe 실행 시 zip 대신 바탕화면 디렉토리 결과물을 바로 확인할 수 있도록 변경한다.
                if (fileStatusType.allowsDiff()) {
                    GitUtil.addDirectoryEntry(outputDir, workTree, diffEntries, allowBuildArtifacts)
                }
                if (fileStatusType.allowsStatus()) {
                    GitUtil.addDirectoryEntry(outputDir, workTree, statusEntries, allowBuildArtifacts)
                }

                DeployCliScript().createDeployScript(
                    listOf(diffEntries, statusEntries).flatMap { it }.distinct(),
                    deployServerDir
                ).forEach { (name, line) ->
                    GitUtil.writeTextOutputFile(outputDir, name, line.joinToString("\n"))
                }

                // 수정 이유: 중복 파일에서 사용자가 고른 버전의 실제 파일 내용을 결과물에 반영한다.
                val appliedOverrides = if (artifactProfile == GitUtil.ArtifactProfile.RAW_FILE_COPY) {
                    val duplicateSelections = duplicateFileVersionMap
                        .mapKeys { normalizePath(it.key) }
                        .mapValues { it.value.trim() }
                        .filter { (path, version) -> path.isNotEmpty() && version.isNotEmpty() }
                    applyDuplicateVersionSelections(outputDir, repo, duplicateSelections)
                } else {
                    // Modified because class-only extraction cannot safely override source file bytes.
                    0
                }

                println("Git artifact profile: ${artifactProfile.name}")
                println("Git extraction summary: status=${statusEntries.size}, diff=${diffEntries.size}")
                println("Git duplicate overrides applied: $appliedOverrides")
            }

            println("Extraction directory created: ${outputDir.absolutePath}")
        }

        val doneMessage = """
            Deployment extraction completed.
            Path: $repoPath
            Output: ${outputDir.absolutePath}
            Time: ${SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(Date())}
        """.trimIndent()

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

    private fun collectDiffPaths(
        repo: Repository,
        since: LocalDate,
        until: LocalDate,
        sinceVersion: String?,
        untilVersion: String?,
        selectedVersions: List<String>,
        selectedFiles: List<String>,
        duplicateFileVersionMap: Map<String, String>
    ): List<String> {
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

            commits.flatMap { commit ->
                val commitId = commit.name
                repo.newObjectReader().use { reader ->
                    val parentTree = commit.parents.firstOrNull()?.let { parent ->
                        CanonicalTreeParser().apply {
                            reset(reader, repo.resolve("${parent.name}^{tree}"))
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
                        .filter { path -> matchesDuplicateSelection(path, commitId, normalizedDuplicateMap) }
                }
            }.distinct()
        }
    }

    private fun normalizePath(path: String): String =
        path.replace("\\", "/").removePrefix("/").trim()

    private fun matchesDuplicateSelection(
        path: String,
        commitId: String,
        duplicateFileVersionMap: Map<String, String>
    ): Boolean {
        val selectedVersion = duplicateFileVersionMap[path] ?: return true
        return commitId == selectedVersion || commitId.startsWith(selectedVersion)
    }

    private fun applyDuplicateVersionSelections(
        outputDir: File,
        repo: Repository,
        duplicateFileVersionMap: Map<String, String>
    ): Int {
        var applied = 0

        duplicateFileVersionMap.forEach { (path, revisionText) ->
            val revision = resolveRevisionByPrefix(repo, revisionText) ?: return@forEach
            val fileBytes = readFileBytesAtRevision(repo, revision, path) ?: return@forEach
            GitUtil.writeBinaryOutputFile(outputDir, path, fileBytes)
            applied += 1
        }

        return applied
    }

    private fun resolveRevisionByPrefix(repo: Repository, revisionText: String): ObjectId? {
        val trimmed = revisionText.trim()
        if (trimmed.isBlank()) return null

        // 우선 일반 revision resolve를 시도한다.
        runCatching { repo.resolve(trimmed) }.getOrNull()?.let { return it }

        // UI에서 짧은 commit hash가 전달된 경우를 위해 prefix 매칭 fallback을 둔다.
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
}
