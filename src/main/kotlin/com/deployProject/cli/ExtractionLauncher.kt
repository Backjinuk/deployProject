package com.deployProject.cli

import com.deployProject.cli.infoCli.GitInfoCli
import com.deployProject.cli.infoCli.SvnInfoCli
import com.deployProject.cli.utilCli.GitUtil
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Properties

object ExtractionLauncher {
    private val gitDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val svnDateFormat = SimpleDateFormat("yyyy-MM-dd")

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
                untilVersion = untilVersion
            )
        } else {
            SvnInfoCli().svnCliExecution(
                repoPath = repoPath,
                since = sinceSvn,
                until = untilSvn,
                fileStatusType = statusType,
                deployServerDir = deployServerDir,
                sinceVersion = sinceVersion,
                untilVersion = untilVersion
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

        val candidate = File(repoPath)
        return when {
            candidate.name.equals(".git", true) && candidate.isDirectory -> candidate
            candidate.name.equals(".svn", true) && candidate.isDirectory -> candidate
            File(candidate, ".git").isDirectory -> File(candidate, ".git")
            File(candidate, ".svn").isDirectory -> File(candidate, ".svn")
            else -> error("Git/SVN directory not found: $repoPath")
        }
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }
}
