package com.deployProject.cli

import com.deployProject.cli.infoCli.GitInfoCli
import com.deployProject.cli.infoCli.SvnInfoCli
import com.deployProject.cli.utilCli.GitUtil
import com.deployProject.cli.utilCli.GitUtil.parseDateArg
import org.slf4j.LoggerFactory
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Properties

object ExtractionLauncher {
    private val log = LoggerFactory.getLogger(ExtractionLauncher::class.java)
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    @JvmStatic
    fun main(args: Array<String>) {
        // args가 비어 있으면 defaults.properties 로드를 시도
        val props = ExtractionLauncher::class.java.getResourceAsStream("/defaults.properties")?.use { inp ->
            Properties().apply { load(inp) }
        } ?: error("defaults.properties가 JAR에 없습니다")

        // 프로퍼티에서 값을 꺼낸 뒤
        val repoDir = props.getProperty("repoDir")?.replace(File.separator, "/")
            ?.let { path -> if (!path.endsWith("/")) "$path/" else path }?.let { base ->
                File(base, ".git").takeIf { it.exists() } ?: File(base, ".svn").takeIf { it.exists() }
                ?: error("Git/SVN 디렉터리 없음: $base")
            }?.path?.replace(File.separator, "/") as String

        props.getProperty("relPath", "") as String

        val since = props.getProperty("since").substringBefore('T')
        val until = props.getProperty("until").substringBefore('T')

        // since와 until은 날짜 형식이므로, DateTimeFormatter를 사용하여 파싱
        val sinceGit: LocalDate = GitUtil.parseDateArg( since, DateTimeFormatter.ofPattern("yyyy-MM-dd") )
        val untilGit: LocalDate = GitUtil.parseDateArg( until, DateTimeFormatter.ofPattern("yyyy-MM-dd") )

        val sdf = SimpleDateFormat("yyyy-MM-dd")
        val sinceSvn: Date = GitUtil.parseDateArg(since, sdf)
        val untilSvn: Date = GitUtil.parseDateArg(until, sdf)


        val statusType = GitUtil.parseStatusType(props.getProperty("statusType", "ALL"))
        val deployServerDir = props.getProperty("deployServerDir", "/home/bjw/deployProject/.")


        // GitInfoCli에 전달
        repoDir.let {
            if (it.endsWith("/.git")) {
                // Git 처리 로직
                GitInfoCli().gitCliExecution(repoDir, sinceGit, untilGit, statusType, deployServerDir)

            } else {
                // SVN 처리 로직
                SvnInfoCli().svnCliExecution(repoDir, sinceSvn, untilSvn, statusType, deployServerDir)
            }
        }

    }
}