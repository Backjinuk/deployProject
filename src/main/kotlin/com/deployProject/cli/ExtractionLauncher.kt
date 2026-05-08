package com.deployProject.cli

import com.deployProject.cli.infoCli.GitInfoCli
import com.deployProject.cli.infoCli.LocalInfoCli
import com.deployProject.cli.infoCli.SvnInfoCli
import com.deployProject.cli.utilCli.GitUtil
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Properties

object ExtractionLauncher {
    private val gitDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val svnDateFormat = SimpleDateFormat("yyyy-MM-dd")
    private const val SELECTION_DELIMITER = "|~|"
    private const val KEY_VALUE_DELIMITER = "::"

    private enum class RepositoryMode {
        GIT,
        SVN,
        LOCAL
    }

    private data class RepositoryTarget(
        val mode: RepositoryMode,
        val path: File
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val defaults = loadDefaultsOrNull()
        val repositoryTarget = resolveRepositoryTarget(args, defaults)
        val sinceRaw = firstNonBlank(args.getOrNull(2), defaults?.getProperty("since"))
        val untilRaw = firstNonBlank(args.getOrNull(3), defaults?.getProperty("until"))
        val statusTypeRaw = firstNonBlank(args.getOrNull(4), defaults?.getProperty("statusType"), "ALL")
        val sinceVersion = firstNonBlank(args.getOrNull(7), defaults?.getProperty("sinceVersion"))
        val untilVersion = firstNonBlank(args.getOrNull(8), defaults?.getProperty("untilVersion"))
        val selectedVersions = parseSelections(firstNonBlank(args.getOrNull(9), defaults?.getProperty("selectedVersions")))
        val selectedFiles = parseSelections(firstNonBlank(args.getOrNull(10), defaults?.getProperty("selectedFiles")))
        val duplicateFileVersionMap = parseSelectionMap(
            firstNonBlank(args.getOrNull(11), defaults?.getProperty("duplicateFileVersionMap"))
        )
        val jdkPath = firstNonBlank(args.getOrNull(12), defaults?.getProperty("jdkPath"))
        val deployServerDir = firstNonBlank(
            args.getOrNull(6),
            defaults?.getProperty("deployServerDir"),
            "/home/bjw/deployProject/."
        ) ?: "/home/bjw/deployProject/."

        val sinceGit: LocalDate = GitUtil.parseDateArg(sinceRaw, gitDateFormat)
        val untilGit: LocalDate = GitUtil.parseDateArg(untilRaw, gitDateFormat)
        val sinceSvn: Date = GitUtil.parseDateArg(sinceRaw, svnDateFormat)
        val untilSvn: Date = GitUtil.parseDateArg(untilRaw, svnDateFormat)
        val statusType = GitUtil.parseStatusType(statusTypeRaw)
        val repoPath = repositoryTarget.path.path.replace(File.separator, "/")

        when (repositoryTarget.mode) {
            RepositoryMode.GIT -> {
                GitInfoCli().gitCliExecution(
                    repoPath = repoPath,
                    since = sinceGit,
                    until = untilGit,
                    fileStatusType = statusType,
                    deployServerDir = deployServerDir,
                    jdkPath = jdkPath,
                    sinceVersion = sinceVersion,
                    untilVersion = untilVersion,
                    selectedVersions = selectedVersions,
                    selectedFiles = selectedFiles,
                    duplicateFileVersionMap = duplicateFileVersionMap
                )
            }

            RepositoryMode.SVN -> {
                SvnInfoCli().svnCliExecution(
                    repoPath = repoPath,
                    since = sinceSvn,
                    until = untilSvn,
                    fileStatusType = statusType,
                    deployServerDir = deployServerDir,
                    jdkPath = jdkPath,
                    sinceVersion = sinceVersion,
                    untilVersion = untilVersion,
                    selectedVersions = selectedVersions,
                    selectedFiles = selectedFiles,
                    duplicateFileVersionMap = duplicateFileVersionMap
                )
            }

            RepositoryMode.LOCAL -> {
                LocalInfoCli().localCliExecution(
                    repoPath = repoPath,
                    since = sinceGit,
                    until = untilGit,
                    fileStatusType = statusType,
                    deployServerDir = deployServerDir,
                    jdkPath = jdkPath,
                    selectedFiles = selectedFiles
                )
            }
        }
    }

    private fun loadDefaultsOrNull(): Properties? {
        return ExtractionLauncher::class.java.getResourceAsStream("/defaults.properties")?.use { inp ->
            Properties().apply { load(inp) }
        }
    }

    private fun resolveRepositoryTarget(args: Array<String>, defaults: Properties?): RepositoryTarget {
        val repoPath = firstNonBlank(args.getOrNull(0), defaults?.getProperty("repoDir"))
            ?: error("repoDir is required. Use args[0] or defaults.properties(repoDir).")

        val start = File(repoPath).canonicalFile
        val startDir = if (start.isDirectory) start else start.parentFile
            ?: error("Repository path is invalid: $repoPath")

        findMetaFromAncestors(startDir)?.let { metaDir ->
            return RepositoryTarget(
                mode = if (metaDir.name.equals(".git", ignoreCase = true)) RepositoryMode.GIT else RepositoryMode.SVN,
                path = metaDir
            )
        }

        findMetaFromDescendants(startDir, maxDepth = 4)?.let { metaDir ->
            return RepositoryTarget(
                mode = if (metaDir.name.equals(".git", ignoreCase = true)) RepositoryMode.GIT else RepositoryMode.SVN,
                path = metaDir
            )
        }

        return RepositoryTarget(RepositoryMode.LOCAL, startDir)
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

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private fun parseSelections(raw: String?): List<String> {
        return raw?.split(SELECTION_DELIMITER)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()
    }

    private fun parseSelectionMap(raw: String?): Map<String, String> {
        return raw?.split(SELECTION_DELIMITER)
            ?.mapNotNull { token ->
                val idx = token.indexOf(KEY_VALUE_DELIMITER)
                if (idx <= 0 || idx >= token.length - KEY_VALUE_DELIMITER.length) {
                    null
                } else {
                    val key = token.substring(0, idx).trim().replace("\\", "/")
                    val value = token.substring(idx + KEY_VALUE_DELIMITER.length).trim()
                    if (key.isEmpty() || value.isEmpty()) null else key to value
                }
            }
            ?.toMap()
            .orEmpty()
    }
}
