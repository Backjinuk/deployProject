package com.deployProject.util

import com.deployProject.deploy.domain.site.FileStatusType
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * GitInfoCli: Git 상태 변경 및 diff 경로를 수집하여 ZIP으로 패키징하는 CLI 유틸리티
 */
object GitInfoCli {
    private val log = LoggerFactory.getLogger(GitInfoCli::class.java)
    private val zippedEntries = mutableSetOf<String>()

    /**
     * 진입점: <gitDir> [sinceDate] [untilDate] [fileStatusType]
     */
    @JvmStatic
    fun main(args: Array<String>) {

        val repoPath = args.getOrNull(0)
            ?: error("Usage: java -jar git-info-cli.jar <gitDir> [sinceDate] [untilDate] [fileStatusType]")

        val dateFmt = DateTimeFormatter.ofPattern("yyyy/MM/dd")
        val since = parseDateArg(args.getOrNull(2), dateFmt)
        val until = parseDateArg(args.getOrNull(3), dateFmt)
        val statusType = parseStatusType(args.getOrNull(4))
        val deployServerDir = args.getOrNull(5)?.takeIf { it.isNotBlank() } ?: "/home/bjw/deployProject/."

        run(repoPath, since, until, statusType, deployServerDir)
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
        val gitDir = parseGitDir(repoPath)
        val workTree = gitDir.parentFile
        val outputZip = determineOutputZip(gitDir)

        val git = Git.open(gitDir)
        val repo = git.repository

        val statusPaths = collectStatusPaths(git, since, until)
        val diffPaths = collectDiffPaths(repo, since, until)

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
                    zip.write( line.joinToString ("\n") .toByteArray(Charsets.UTF_8) )
                    zip.closeEntry()
                }
        }

        println("✅ Created ZIP: ${outputZip.absolutePath}")
    }

    // ───────────────────────────────────────────────────────────
    // 2) Argument Parsing Helpers
    // ───────────────────────────────────────────────────────────
    private fun parseDateArg(
        arg: String?,
        formatter: DateTimeFormatter
    ): LocalDate = arg
        ?.takeIf { it.isNotBlank() }
        ?.let {
            try { LocalDate.parse(it, formatter) }
            catch (e: Exception) { LocalDate.now() }
        }
        ?: LocalDate.now()

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
        val candidates = listOf("$baseName.class", "${baseName}Kt.class")

        val found = Files.walk(workTree.toPath())
            .filter { Files.isRegularFile(it) }
            .filter { it.fileName.toString() in candidates }
            .findFirst().orElse(null) ?: return null

        val entry = workTree.toPath()
            .relativize(found)
            .toString().replace(File.separatorChar, '/')

        return listOf(entry)
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
            if (!file.exists()) { log.warn("Missing: ${file.absolutePath}"); return@forEach }
            val entryName = basePath.relativize(file.toPath())
                .toString().replace(File.separatorChar, '/')
            if (!zippedEntries.add(entryName)) return@forEach
            zip.putNextEntry(ZipEntry(entryName))
            FileInputStream(file).use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun FileStatusType.allowsDiff() = this == FileStatusType.DIFF || this == FileStatusType.ALL
    private fun FileStatusType.allowsStatus() = this == FileStatusType.STATUS || this == FileStatusType.ALL
}
