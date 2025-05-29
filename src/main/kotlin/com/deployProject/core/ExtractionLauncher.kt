package com.deployProject.core

import com.deployProject.core.GitInfoCli
import com.deployProject.core.SvnInfoCli
import org.hibernate.query.`QueryLogging_$logger`
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*

object ExtractionLauncher {
    private val log = LoggerFactory.getLogger(ExtractionLauncher::class.java)

    @JvmStatic
    fun main(args: Array<String>) {
        // args가 비어 있으면 defaults.properties 로드를 시도
        val props = ExtractionLauncher::class.java.getResourceAsStream("/defaults.properties")?.use { inp ->
                Properties().apply { load(inp) }
            } ?: error("defaults.properties가 JAR에 없습니다")

        // 프로퍼티에서 값을 꺼낸 뒤
        val repoDir = props.getProperty("repoDir")
            ?.replace(File.separator, "/")
            ?.let { path -> if (!path.endsWith("/")) "$path/" else path }
            ?.let { base ->
                File(base, ".git").takeIf { it.exists() }
                    ?: File(base, ".svn").takeIf { it.exists() }
                    ?: error("Git/SVN 디렉터리 없음: $base")
            }
            ?.path
            ?.replace(File.separator, "/")


        val relPath = props.getProperty("relPath", "")
        val since = props.getProperty("since")
        val until = props.getProperty("until")
        val statusType = props.getProperty("statusType", "ALL")
        val deployServerDir = props.getProperty("deployServerDir", "/home/bjw/deployProject/.")

        // GitInfoCli에 전달
        repoDir?.let {
            if (it.endsWith("/.git")) {
                // Git 처리 로직
                GitInfoCli.main(arrayOf(repoDir, relPath, since, until, statusType, deployServerDir))

            } else {
                // SVN 처리 로직
                SvnInfoCli.main(arrayOf(repoDir, relPath, since, until, statusType, deployServerDir))
            }
        }

    }
}