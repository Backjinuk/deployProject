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
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.slf4j.LoggerFactory
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.zip.ZipEntry
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
        untilVersion: String?
    ) {
        GitUtil.showProgressAndRun(initialMessage = "Git deploy extracting...") {
            val workTree = GitUtil.parseDir(repoPath, "git")

            Git.open(workTree).use { git ->
                val repo = git.repository
                val statusPaths = collectStatusPaths(git, since, until)
                val diffPaths = collectDiffPaths(repo, since, until, sinceVersion, untilVersion)

                GitUtil.buildLatestClassMap(workTree, statusPaths + diffPaths)
                val statusEntries = GitUtil.mapSourcesToClasses(statusPaths)
                val diffEntries = GitUtil.mapSourcesToClasses(diffPaths)

                GitUtil.createZip { zip ->
                    if (fileStatusType.allowsDiff()) {
                        GitUtil.addZipEntry(zip, workTree, diffEntries)
                    }
                    if (fileStatusType.allowsStatus()) {
                        GitUtil.addZipEntry(zip, workTree, statusEntries)
                    }

                    DeployCliScript().createDeployScript(
                        listOf(diffEntries, statusEntries).flatMap { it }.distinct(),
                        deployServerDir
                    ).forEach { (name, line) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(line.joinToString("\n").toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                }
            }
        }

        JOptionPane.showMessageDialog(
            null,
            """
            Deployment extraction completed.
            Path: $repoPath
            Time: ${SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(Date())}
            """.trimIndent(),
            "Done",
            JOptionPane.INFORMATION_MESSAGE
        )
    }

    private fun collectStatusPaths(git: Git, since: LocalDate, until: LocalDate): List<String> {
        val base = git.repository.workTree
        val dateZone = ZoneId.systemDefault()
        val status = git.status().call()

        return (status.added + status.changed + status.modified)
            .map { File(base, it) }
            .filter { file ->
                file.exists().also { if (!it) log.warn("Missing: $file") }
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
        untilVersion: String?
    ): List<String> {
        val zone = ZoneId.systemDefault()
        return Git(repo).use { git ->
            val fromCommit = resolveRevision(git, sinceVersion)
            val toCommit = resolveRevision(git, untilVersion)

            val commits = loadCommitsByVersionRange(git, fromCommit, toCommit).filter { commit ->
                val date = Instant.ofEpochMilli(commit.authorIdent.`when`.time).atZone(zone).toLocalDate()
                !date.isBefore(since) && !date.isAfter(until)
            }

            commits.flatMap { commit ->
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
                        .map { entry ->
                            if (entry.changeType == DiffEntry.ChangeType.DELETE) entry.oldPath else entry.newPath
                        }
                }
            }.distinct()
        }
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
        // 수정 이유: 날짜 필터만으로는 배포 범위가 넓어져서, commit hash 범위를 추가 필터로 사용한다.
        return when {
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
