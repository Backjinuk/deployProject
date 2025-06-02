package com.deployProject.cli.infoCli

import com.deployProject.cli.DeployCliScript
import com.deployProject.deploy.domain.site.FileStatusType
import com.deployProject.cli.utilCli.GitUtil
import com.deployProject.cli.utilCli.GitUtil.allowsDiff
import com.deployProject.cli.utilCli.GitUtil.allowsStatus
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.Repository
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

/**
 * GitInfoCli: Git 상태 변경 및 diff 경로를 수집하여 ZIP으로 패키징하는 CLI 유틸리티
 */
class GitInfoCli {
    private val log = LoggerFactory.getLogger(GitInfoCli::class.java)

    // ───────────────────────────────────────────────────────────
    // 1) Core Logic
    // ───────────────────────────────────────────────────────────
    fun gitCliExecution(
        repoPath: String, since: LocalDate, until: LocalDate, fileStatusType: FileStatusType, deployServerDir: String
    ) {

        GitUtil.showProgressAndRun(initialMessage = "Git 배포를 시작합니다…") {
            // 1) gitDir, workTree, outputZip
            val gitDir = GitUtil.parseDir(repoPath, "git")
            val workTree = gitDir.parentFile
            val outputZip = GitUtil.determineOutputZip(gitDir)

            // 2) 레포 열기
            Git.open(gitDir).use { git ->
                val repo = git.repository

                // 3) 상태/차이 경로 수집
                val statusPaths = collectStatusPaths(git, since, until)
                val diffPaths = collectDiffPaths(repo, since, until)

                GitUtil.buildLatestClassMap(workTree, statusPaths + diffPaths)

                // 4) 클래스 매핑 결과
                val diffEntries = GitUtil.mapSourcesToClasses(statusPaths)
                val statusEntries = GitUtil.mapSourcesToClasses(diffPaths)


                // Create ZIP file
                GitUtil.createZip() { zip ->

                    if (fileStatusType.allowsDiff()) {
                        GitUtil.addZipEntry(zip, workTree, diffEntries)
                    }
                    if (fileStatusType.allowsStatus()) {
                        GitUtil.addZipEntry(zip, workTree, statusEntries)
                    }

                    DeployCliScript().createDeployScript(
                        listOf(diffEntries, statusEntries).flatMap { it }.distinct(), deployServerDir
                    ).forEach { (name, line) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(line.joinToString("\n").toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                }
            }
        }

        JOptionPane.showMessageDialog(
            null, """
          배포가 완료되었습니다.
          파일: $repoPath
          시간: ${SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(Date())}
        """.trimIndent(), "완료", JOptionPane.INFORMATION_MESSAGE
        )

    }


    // ───────────────────────────────────────────────────────────
    // 2) Git Status & Diff Collection
    // ───────────────────────────────────────────────────────────
    private fun collectStatusPaths(
        git: Git, since: LocalDate, until: LocalDate
    ): List<String> {
        val base = git.repository.workTree
        val dateZone = ZoneId.systemDefault()

        return (git.status().call().added + git.status().call().changed + git.status().call().modified).map {
            File(
                base, it
            )
        }.filter { file ->
            file.exists().also { if (!it) log.warn("Missing: $file") }
        }.filter { file ->
            val fileDate = Instant.ofEpochMilli(file.lastModified()).atZone(dateZone).toLocalDate()
            !fileDate.isBefore(since) && !fileDate.isAfter(until)
        }.map { it.absolutePath }
    }

    private fun collectDiffPaths(
        repo: Repository, since: LocalDate, until: LocalDate
    ): List<String> {
        val git = Git(repo)
        val zone = ZoneId.systemDefault()

        val commits = git.log().call().filter { commit ->
            val date = Instant.ofEpochMilli(commit.authorIdent.`when`.time).atZone(zone).toLocalDate()
            !date.isBefore(since) && !date.isAfter(until)
        }

        return commits.flatMap { commit ->
            val reader = repo.newObjectReader()
            val parentTree = commit.parents.firstOrNull()?.let { parent ->
                CanonicalTreeParser().apply {
                    reset(reader, repo.resolve("${parent.name}^{tree}"))
                }
            }
            val newTree = CanonicalTreeParser().apply {
                reset(reader, repo.resolve("${commit.name}^{tree}"))
            }
            Git(repo).diff().setOldTree(parentTree).setNewTree(newTree).call().map { entry ->
                if (entry.changeType == DiffEntry.ChangeType.DELETE) entry.oldPath else entry.newPath
            }
        }
    }
}