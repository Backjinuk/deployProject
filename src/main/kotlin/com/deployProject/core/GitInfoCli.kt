package com.deployProject.core

import com.deployProject.deploy.domain.site.FileStatusType
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Frame
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JProgressBar
import javax.swing.SwingWorker

/**
 * GitInfoCli: Git 상태 변경 및 diff 경로를 수집하여 ZIP으로 패키징하는 CLI 유틸리티
 */
class GitInfoCli {
    private val log = LoggerFactory.getLogger(GitInfoCli::class.java)
    private val zippedEntries = mutableSetOf<String>()

    /**
     * 진입점: <gitDir> [sinceDate] [untilDate] [fileStatusType]
     */
    companion object {

        @JvmStatic
        fun main(args: Array<String>) {

            val repoPath = args.getOrNull(0) ?: error("Usage: java -jar git-info-cli.jar <gitDir> [sinceDate] [untilDate] [fileStatusType]")
            val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val since = GitInfoCli().parseDateArg(args.getOrNull(2)?.substringBefore('T'), dateFmt)
            val until = GitInfoCli().parseDateArg(args.getOrNull(3)?.substringBefore('T'), dateFmt)
            val statusType =GitInfoCli().parseStatusType(args.getOrNull(4))
            val deployServerDir = args.getOrNull(5)?.takeIf { it.isNotBlank() } ?: "/home/bjw/deployProject/."


            GitInfoCli().run(
                repoPath,
                since,
                until,
                statusType,
                deployServerDir
            )
        }
    }

    // ───────────────────────────────────────────────────────────
    // 1) Core Logic
    // ───────────────────────────────────────────────────────────
    fun run(
        repoPath: String,
        since: LocalDate,
        until: LocalDate,
        fileStatusType: FileStatusType,
        deployServerDir: String
    ) {

        showProgressAndRun(initialMessage = "SVN 배포를 시작합니다…") {
            // 1) gitDir, workTree, outputZip
            val gitDir = parseGitDir(repoPath)
            val workTree = gitDir.parentFile
            val outputZip = determineOutputZip(gitDir)

            // 2) 레포 열기
            val git = Git.open(gitDir)
            val repo = git.repository

            // 3) 상태/차이 경로 수집
            val statusPaths = collectStatusPaths(git, since, until)
            val diffPaths = collectDiffPaths(repo, since, until)

            // 4) 클래스 매핑 결과
            val diffEntries = mapSourcesToClasses(diffPaths, workTree)
            val statusEntries = mapSourcesToClasses(statusPaths, workTree)


            // Create ZIP file
            createZip(outputZip) { zip ->

                if (fileStatusType.allowsDiff()) {
                    addZipFiles(zip, workTree, diffEntries)
                }
                if (fileStatusType.allowsStatus()) {
                    addZipFiles(zip, workTree, statusEntries)
                }

                ScriptCreate()
                    .getLegacyPatchScripts(
                        listOf(diffEntries, statusEntries).flatMap { it }.distinct(),
                        deployServerDir
                    ).forEach { (name, line) ->

                        zip.putNextEntry(ZipEntry(name))
                        zip.write(line.joinToString("\n").toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
            }
        }

        JOptionPane.showMessageDialog(
            null,
            """
          배포가 완료되었습니다.
          파일: ${repoPath.toString()}
          시간: ${SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(Date())}
        """.trimIndent(),
            "완료",
            JOptionPane.INFORMATION_MESSAGE
        )

    }

    // ───────────────────────────────────────────────────────────
    // 2) Argument Parsing Helpers
    // ───────────────────────────────────────────────────────────
    private fun parseDateArg(
        arg: String?,
        formatter: DateTimeFormatter
    ): LocalDate {
        try {
            return  LocalDate.parse(arg, formatter)
        } catch (e: Exception) {
            error("Invalid date format: $arg. Expected format: ${formatter}")
        }
    }

    private fun parseStatusType(arg: String?): FileStatusType = arg
        ?.let { value ->
            try { FileStatusType.valueOf(value) }
            catch (e: Exception) { FileStatusType.ALL }
        } ?: FileStatusType.ALL

    // ───────────────────────────────────────────────────────────
    // 3) Repository & Path Utilities
    // ───────────────────────────────────────────────────────────
    private fun parseGitDir(repoPath: String): File = File(repoPath).apply {
        if (!exists() || !File(this, "config").exists()) {
            error("ERROR: Not a valid Git repository: ${absolutePath}")
        }
    }

    private fun determineOutputZip(gitDir: File): File {
        val date = SimpleDateFormat("yyyyMMdd").format(Date())
        return File(gitDir.parentFile, "$date.zip")
    }

    // ───────────────────────────────────────────────────────────
    // 4) Git Status & Diff Collection
    // ───────────────────────────────────────────────────────────
    private fun collectStatusPaths(
        git: Git,
        since: LocalDate,
        until: LocalDate
    ): List<String> {
        val base = git.repository.workTree
        val dateZone = ZoneId.systemDefault()

        return (git.status().call().added
                + git.status().call().changed
                + git.status().call().modified)
            .map { File(base, it) }
            .filter { file ->
                file.exists().also { if (!it) log.warn("Missing: $file") }
            }
            .filter { file ->
                val fileDate = Instant.ofEpochMilli(file.lastModified())
                    .atZone(dateZone).toLocalDate()
                !fileDate.isBefore(since) && !fileDate.isAfter(until)
            }
            .map { it.absolutePath }
    }

    private fun collectDiffPaths(
        repo: Repository,
        since: LocalDate,
        until: LocalDate
    ): List<String> {
        val git = Git(repo)
        val zone = ZoneId.systemDefault()

        val commits = git.log().call().filter { commit ->
            val date = Instant.ofEpochMilli(commit.authorIdent.`when`.time)
                .atZone(zone).toLocalDate()
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
            Git(repo).diff()
                .setOldTree(parentTree)
                .setNewTree(newTree)
                .call()
                .map { entry ->
                    if (entry.changeType == DiffEntry.ChangeType.DELETE)
                        entry.oldPath else entry.newPath
                }
        }
    }

    // ───────────────────────────────────────────────────────────
    // 5) Class Mapping & ZIP Helpers
    // ───────────────────────────────────────────────────────────
    private fun mapSourcesToClasses(
        sources: List<String>,
        workTree: File
    ): List<String> = sources
        .flatMap { src ->
            if (!src.endsWith(".kt") && !src.endsWith(".java")) return@flatMap listOf(src)
            mapToClassEntry(src, workTree) ?: emptyList()
        }
        .distinct()

    private fun mapToClassEntry(
        src: String,
        workTree: File
    ): List<String>? {
        val baseName = File(src).nameWithoutExtension
        val pattern = Regex("^${Regex.escape(baseName)}(\\$.*)?\\.class$")

        val entries = Files.walk(workTree.toPath())
            .filter { path -> Files.isRegularFile(path) }
            .filter { path -> pattern.matches(path.fileName.toString()) }
            .map { path ->
                workTree.toPath()
                    .relativize(path)
                    .toString()
                    .replace(File.separatorChar, '/')
            }.toList()

        return entries
    }

    private fun createZip(
        output: File,
        block: (ZipOutputStream) -> Unit
    ) {
        ZipOutputStream(Files.newOutputStream(output.toPath())).use(block)
    }

    private fun addZipFiles(
        zip: ZipOutputStream,
        baseDir: File,
        paths: List<String>
    ) {
        val basePath = baseDir.toPath()
        paths.forEach { rel ->
            val file = if (Paths.get(rel).isAbsolute) File(rel) else File(baseDir, rel)

            if (!file.exists() || !file.isFile) {
                println("Skipping missing file: ${file.absolutePath}")
                return@forEach
            }

            val entryName = basePath.relativize(file.toPath()) .toString().replace(File.separatorChar, '/')
            if (!zippedEntries.add(entryName)){
                println("Skipping already zipped entry: $entryName")
                return@forEach
            }
            zip.putNextEntry(ZipEntry(entryName))
            FileInputStream(file).use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    fun showProgressAndRun(
        title: String = "GIT 배포 중…",
        initialMessage: String = "잠시만 기다려 주세요…",
        task: () -> Unit
    ) {
        // 1) 다이얼로그 & 컴포넌트 준비
        val dialog = JDialog(null as Frame?, title, true).apply {
            layout = BorderLayout(10, 10)

            // 메시지 라벨
            val label = JLabel(initialMessage).apply {
                horizontalAlignment = JLabel.CENTER
            }
            add(label, BorderLayout.NORTH)

            // 무한 모드 프로그레스 바
            val progressBar = JProgressBar().apply {
                isIndeterminate = true
                preferredSize = Dimension(300, 20)
            }
            add(progressBar, BorderLayout.CENTER)

            pack()
            setLocationRelativeTo(null)
        }

        // 2) 백그라운드에서 실제 작업 수행
        object : SwingWorker<Unit, Unit>() {
            override fun doInBackground() {
                task()
            }
            override fun done() {
                // 작업 끝나면 다이얼로그 닫기
                dialog.dispose()
            }
        }.execute()

        // 3) 모달 다이얼로그 보여주기 (이 뒤는 블록됨)
        dialog.isVisible = true
    }


    private fun FileStatusType.allowsDiff() = this == FileStatusType.DIFF || this == FileStatusType.ALL
    private fun FileStatusType.allowsStatus() = this == FileStatusType.STATUS || this == FileStatusType.ALL




}
