package com.deployProject.cli

import com.deployProject.cli.infoCli.GitInfoCli
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

    @JvmStatic
    fun main(args: Array<String>) {
        val defaults = loadDefaultsOrNull()

        // 수정 이유: 기존 코드는 main(args)를 사실상 무시해서 defaults.properties가 없으면 즉시 실패했다.
        // CLI/테스트/서버 실행 모두에서 동작하도록 args 우선, defaults fallback 구조로 변경한다.
        val repoMetaDir = resolveRepositoryDir(args, defaults)
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
        val repoPath = repoMetaDir.path.replace(File.separator, "/")

        if (repoMetaDir.name.equals(".git", ignoreCase = true)) {
            // 수정 이유: 날짜 기반 필터에 더해 저장소 버전 범위도 함께 전달한다.
            GitInfoCli().gitCliExecution(
                repoPath = repoPath,
                since = sinceGit,
                until = untilGit,
                fileStatusType = statusType,
                deployServerDir = deployServerDir,
                sinceVersion = sinceVersion,
                untilVersion = untilVersion,
                selectedVersions = selectedVersions,
                selectedFiles = selectedFiles,
                duplicateFileVersionMap = duplicateFileVersionMap
            )
        } else {
            SvnInfoCli().svnCliExecution(
                repoPath = repoPath,
                since = sinceSvn,
                until = untilSvn,
                fileStatusType = statusType,
                deployServerDir = deployServerDir,
                sinceVersion = sinceVersion,
                untilVersion = untilVersion,
                selectedVersions = selectedVersions,
                selectedFiles = selectedFiles,
                duplicateFileVersionMap = duplicateFileVersionMap
            )
        }
    }

    private fun loadDefaultsOrNull(): Properties? {
        return ExtractionLauncher::class.java.getResourceAsStream("/defaults.properties")?.use { inp ->
            Properties().apply { load(inp) }
        }
    }

    private fun resolveRepositoryDir(args: Array<String>, defaults: Properties?): File {
        val repoPath = firstNonBlank(args.getOrNull(0), defaults?.getProperty("repoDir"))
            ?: error("repoDir is required. Use args[0] or defaults.properties(repoDir).")

        val start = File(repoPath).canonicalFile
        val startDir = if (start.isDirectory) start else start.parentFile
            ?: error("Repository path is invalid: $repoPath")

        // 수정 이유: 추출 시 입력 경로가 저장소 하위여도 상위 경로까지 탐색해서 메타 디렉터리를 자동 감지한다.
        findMetaFromAncestors(startDir)?.let { return it }

        // 수정 이유: 워크스페이스 루트를 입력한 경우를 위해 제한 깊이로 하위 경로도 탐색한다.
        findMetaFromDescendants(startDir, maxDepth = 4)?.let { return it }

        error("Git/SVN metadata directory not found under/above: $repoPath")
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
