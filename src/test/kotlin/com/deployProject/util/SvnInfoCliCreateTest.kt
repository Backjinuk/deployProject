package com.deployProject.util

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.tmatesoft.svn.core.auth.BasicAuthenticationManager
import org.tmatesoft.svn.core.wc.SVNClientManager
import org.tmatesoft.svn.core.wc.SVNInfo
import org.tmatesoft.svn.core.wc.SVNRevision
import org.tmatesoft.svn.core.wc.SVNStatusType
import org.tmatesoft.svn.core.wc.SVNWCUtil
import java.io.Console
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

class SvnInfoCliCreateTest {

    private val repoGitDir = File("D:/DevSpace/NCRC_Space/ncrc_icarevalue_admin/")
    //private val repoGitDir = File("/Users/mac/IdeaProjects/deployProject/.git")

    @Test
    fun `캐시된_인증으로_로컬_변경파일_조회_테스트`() {
        // 목적: 캐시된 자격증명만으로 로컬 변경 파일 목록을 조회하는지 검증

            val since = "2025/04/01"
            val until = "2025/05/22"
            val filestatus = "ALL"
            val deployServerDir = "/home/bjw/deployProject"

        SvnInfoCli.main(arrayOf(repoGitDir.absolutePath, "", since, until, filestatus, deployServerDir))
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