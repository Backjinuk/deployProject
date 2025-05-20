package com.deployProject.util

import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.XpathAssertions
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.test.assertTrue
import kotlin.test.fail

class GitInfoCliTest {

    private val repoGitDir = File("D:/DevSpace/deployProject")


    @Test
    fun `diff 파일은 README 또는 다른 프로젝트 파일을 포함해야 한다`() {
        // Run CLI again to ensure ZIP exists

        val since = "2025/04/27"
        val until = "2025/05/10"
        val filestatus = "ALL"
        val deployServerDir = "/home/bjw/deployProject"

        GitInfoCli.main(arrayOf(repoGitDir.absolutePath, "", since, until, filestatus, deployServerDir))
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



    @Test
    fun `디렉토리 생성 테스트`(){
        val randomFileName = "GitInfoJarFile/"+UUID.randomUUID().toString();
        val path = Paths.get(randomFileName)
        Files.createDirectories(path)
    }

}