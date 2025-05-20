package com.deployProject.util

import java.util.Properties

object ExtractionLauncher {
    @JvmStatic
    fun main(args: Array<String>) {
        // args가 비어 있으면 defaults.properties 로드를 시도
        val props = ExtractionLauncher::class.java
            .getResourceAsStream("/defaults.properties")
            ?.use { inp ->
                Properties().apply { load(inp) }
            } ?: error("defaults.properties가 JAR에 없습니다")

        // 프로퍼티에서 값을 꺼낸 뒤
        val repoDir         = props.getProperty("repoDir")
        val relPath         = props.getProperty("relPath", "")
        val since           = props.getProperty("since")
        val until           = props.getProperty("until")
        val statusType      = props.getProperty("statusType", "ALL")
        val deployServerDir = props.getProperty("deployServerDir", "/home/bjw/deployProject/.")


        if(deployServerDir.endsWith(".git")){
            GitInfoCli.main(arrayOf(repoDir, relPath, since, until, statusType, deployServerDir))
        }else{
            // SVN 처리 로직
            println("SVN 처리 로직을 구현하세요.")
        }

        // GitInfoCli에 전달
    }
}