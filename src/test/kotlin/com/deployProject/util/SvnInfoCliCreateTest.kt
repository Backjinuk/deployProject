package com.deployProject.util

import com.deployProject.cli.ExtractionLauncher
import com.deployProject.cli.utilCli.JarCreator
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

class SvnInfoCliCreateTest {

//    private val repoGitDir = File("D:/DevSpace/NCRC_Space/ncrc_icarevalue_admin/")
    private val repoGitDir = File("/Users/mac/IdeaProjects/icarevalue_home/")

    @Test
    fun `캐시된_인증으로_로컬_변경파일_조회_테스트`() {
        // 목적: 캐시된 자격증명만으로 로컬 변경 파일 목록을 조회하는지 검증

            val since = "2025/04/01"
            val until = "2025/05/22"
            val filestatus = "ALL"
            val deployServerDir = "/home/bjw/deployProject"

        ExtractionLauncher.main(arrayOf(repoGitDir.absolutePath, "", since, until, filestatus, deployServerDir))
    }


    @Test
    fun `Jar파일 생성 테스트`() {

        val since = "2025/05/10"
        val until = ""
        val filestatus = "ALL"
        val randomFileName = "GitInfoJarFile/"+UUID.randomUUID().toString();
        val deployServerDir = "/home/bjw/deployProject"

        JarCreator.main(arrayOf(repoGitDir.absolutePath, "", since, until, filestatus, randomFileName, deployServerDir))

    }

}