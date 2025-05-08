package com.deployProject.util

import com.deployProject.deploy.domain.site.FileStatusType
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object GitInfoCli {

    private val log = LoggerFactory.getLogger(GitInfoCli::class.java)

    private val zippedEntries = mutableSetOf<String>()

    @JvmStatic
    fun main(args: Array<String>) {
        val repoPath = args.getOrNull(0) ?: error("Usage: <gitDir> [sinceDate] [untilDate]")

        log.info("args : ${args.joinToString(",")}")

        val since = args.getOrNull(2)
            ?.takeIf { it.isBlank() }
            ?.let {
                try {
                    LocalDate.parse(it)
                } catch (e: Exception) {
                    LocalDate.now()
                }
            }
            ?: LocalDate.now()

        val until = args.getOrNull(3)
            ?.takeIf { it.isBlank() }
            ?.let {
                try {
                    LocalDate.parse(it)
                } catch (e: Exception) {
                    LocalDate.now()
                }
            }
            ?: LocalDate.now()

        val fileStatusType = args.getOrNull(4  )
            ?.let {
                try{ FileStatusType.valueOf(it)
                } catch (e: Exception) {
                    FileStatusType.ALL
                }
            }
            ?: FileStatusType.ALL

        run (repoPath, since, until, fileStatusType)
    }


    fun run(
        repoPath: String,
        since: LocalDate,
        until: LocalDate,
        fileStatusType: FileStatusType
    ) {

        val gitDir = parseGitDir(repoPath)
        val outputZip = determineOutputZip(gitDir)
        val git = Git.open(gitDir)
        val repo = git.repository

        val statusPaths = collectStatusPaths(git, repoPath, since, until)
        val diffPaths = collectDiffPaths(repo, since, until)

        val workTree = gitDir.parentFile
        createZip(outputZip) { zip ->
            //diffPaths
            if(fileStatusType == FileStatusType.DIFF || fileStatusType == FileStatusType.ALL) {
                log.info("diffPaths zip 앞축 시작 : ${LocalDateTime.now()}")
                addZipFiles(zip, workTree, diffPaths)
                log.info("diffPaths zip 앞축 종료 : ${LocalDateTime.now()}")
            }

            if (fileStatusType == FileStatusType.STATUS || fileStatusType == FileStatusType.ALL) {
                log.info("statusPaths zip 앞축 시작 : ${LocalDateTime.now()}")
                addZipFiles(zip, workTree, statusPaths)
                log.info("statusPaths zip 앞축 종료 : ${LocalDateTime.now()}")
            }
        }


        println("✅ Created ZIP: ${outputZip.absolutePath}")
    }


    // ───────────────────────────────────────────────────────────
    // 1) 인자 검증 & Git 디렉터리 파싱
    // ───────────────────────────────────────────────────────────
    private fun parseGitDir(repoPath : String): File {
        if (repoPath.isEmpty()) {
            errorExit("Usage: java -jar git-info-cli.jar <git-dir> [<output-zip-path>]")
        }
        val gitDir = File(repoPath)
        if (!gitDir.exists() || !File(gitDir, "config").exists()) {
            errorExit("ERROR: Not a valid Git repository: ${gitDir.absolutePath}")
        }
        return gitDir
    }

    private fun determineOutputZip(gitDir: File): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        return File(gitDir.parentFile, "git-info-$timestamp.zip")
    }

    private fun errorExit(msg: String): Nothing {
        println(msg)
        kotlin.system.exitProcess(1)
    }

    // ───────────────────────────────────────────────────────────
    // 2) Git status 경로 수집
    // ───────────────────────────────────────────────────────────
    private fun collectStatusPaths(git: Git, path: String, since: LocalDate, until: LocalDate): List<String> {
        val workTree = git.repository.workTree       // File 객체
        val status = git.status().call()

        return (status.added + status.changed + status.modified).map { it -> File(workTree, it) }
            // 실제 있는 파일만 filter
            .filter { file ->
                file.exists().also { exists ->
                    if (!exists) {
                        log.warn("File does not exist: $file")
                    }
                }
            }

            .filter { file ->
                val fileDate: LocalDate =
                    Instant.ofEpochMilli(file.lastModified()).atZone(ZoneId.systemDefault()).toLocalDate()

                !fileDate.isBefore(since) && !fileDate.isAfter(until)
            }.map { it.absolutePath }


    }


    private fun collectDiffPaths(repo: Repository, since: LocalDate, until: LocalDate): List<String> {
        val git = Git(repo)

        repo.newObjectReader().use { reader ->

            val commitsInRange = git.log().call().filter { commit ->
                val commitTime: LocalDate =
                    Instant.ofEpochSecond(commit.commitTime.toLong()).atZone(ZoneId.systemDefault()).toLocalDate()

                !commitTime.isBefore(since) && !commitTime.isAfter(until)
            }

            val diffs = mutableListOf<String>()

            // 각 diff 계산
            for (commit in commitsInRange) {

                // 부모 커밋(이전 커밋) 트리 생성 없으면 null
                val parentTree = commit.parents.firstOrNull()?.let { parent ->
                    CanonicalTreeParser().apply {
                        reset(reader, repo.resolve("${parent.name}^{tree}"))
                    }
                }

                // 현재 커밋의 트리 생성
                val currentTree = CanonicalTreeParser().apply {
                    reset(reader, repo.resolve("${commit.name}^{tree}"))
                }

                // diff 계산

                git.diff().setOldTree(parentTree).setNewTree(currentTree).call().forEach { diff ->
                    val path = if (diff.changeType == DiffEntry.ChangeType.DELETE) diff.oldPath
                    else diff.newPath

                    diffs.add(path)
                }
            }

            return diffs.toList()
        }
    }

    // ───────────────────────────────────────────────────────────
    // 4) ZIP 아카이브 생성 헬퍼
    // ───────────────────────────────────────────────────────────
    private fun createZip(outputZip: File, block: (ZipOutputStream) -> Unit) {
        ZipOutputStream(Files.newOutputStream(outputZip.toPath())).use { zip ->
            block(zip)
        }
    }

    // ───────────────────────────────────────────────────────────
    // 5) 실제 파일들을 ZIP에 담기
    // ───────────────────────────────────────────────────────────
    private fun addZipFiles(zip: ZipOutputStream, workTree: File, paths: List<String>) {
        val workTreePath = workTree.toPath()

        for (relative in paths) {

            val file = if (Paths.get(relative).isAbsolute) {
                File(relative)
            } else {
                File(workTree, relative)
            }

            if (!file.exists()) {
                log.warn("File does not exist: ${file.absolutePath}")
                continue
            }

            val entryName = workTreePath.relativize(file.toPath()).toString().replace(File.separatorChar, '/')

            // ❸ 중복 체크: 이미 추가된 엔트리면 스킵
            if (!zippedEntries.add(entryName)) {
                println("Skipping duplicate entry: $entryName")
                continue
            }

            zip.putNextEntry(ZipEntry(entryName))
            FileInputStream(file).use { fis -> fis.copyTo(zip) }
            zip.closeEntry()
        }
    }


    // ───────────────────────────────────────────────────────────
    // 6) 텍스트 엔트리(status.txt 등) 추가
    // ───────────────────────────────────────────────────────────
    private fun addTextEntry(zip: ZipOutputStream, entryName: String, lines: List<String>) {
        zip.putNextEntry(ZipEntry(entryName))
        lines.joinToString("\n").byteInputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }
}