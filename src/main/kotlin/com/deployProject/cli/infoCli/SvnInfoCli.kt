package com.deployProject.cli.infoCli

import com.deployProject.cli.DeployCliScript
import com.deployProject.cli.utilCli.GitUtil
import com.deployProject.cli.utilCli.GitUtil.addZipEntryName
import com.deployProject.cli.utilCli.GitUtil.allowsDiff
import com.deployProject.cli.utilCli.GitUtil.allowsStatus
import com.deployProject.deploy.domain.site.FileStatusType
import org.slf4j.LoggerFactory
import org.tmatesoft.svn.core.SVNException
import org.tmatesoft.svn.core.wc.SVNClientManager
import org.tmatesoft.svn.core.wc.SVNRevision
import org.tmatesoft.svn.core.wc.SVNStatusType
import org.tmatesoft.svn.core.wc.SVNWCUtil
import java.awt.GridLayout
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.*
import java.util.zip.ZipEntry
import javax.swing.*

class SvnInfoCli {

    private val log = LoggerFactory.getLogger(SvnInfoCli::class.java)

    fun svnCliExecution(
        repoPath: String, since: Date, until: Date, fileStatusType: FileStatusType, deployServerDir: String
    ) {
        GitUtil.showProgressAndRun(title = "SVN 작업중...", initialMessage = "SVN 추출를 시작합니다…") {
        val svnDir = GitUtil.parseDir(repoPath, "svn")
        val workTree = if (svnDir.name == ".svn") svnDir.parentFile else svnDir
        val outputZip = GitUtil.determineOutputZip(svnDir)

        // 수집된 경로
        val statusPaths = collectStatusPaths(svnDir.path, since, until)
        val diffPaths = collectDiffPaths(svnDir.path, since, until)

        // 클래스 매핑
        GitUtil.buildLatestClassMap(workTree, statusPaths + diffPaths)
        val statusEntries = GitUtil.mapSourcesToClasses(statusPaths)
        val diffEntries = GitUtil.mapSourcesToClasses(diffPaths)

        // webapp-relative 경로 변환
        val entries = addZipEntryName(workTree,statusEntries + diffEntries).toList()

        // ZIP 생성
        GitUtil.createZip { zip ->
            if (fileStatusType.allowsStatus()) GitUtil.addZipEntry(zip, workTree, statusEntries)
            if (fileStatusType.allowsDiff()) GitUtil.addZipEntry(zip, workTree, diffEntries)

            DeployCliScript().createDeployScript(entries, deployServerDir).forEach { (name, lines) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(lines.joinToString("\n").toByteArray())
                zip.closeEntry()
            }
        }

        log.info("✅ Created ZIP: ${outputZip.absolutePath}")
        }

        JOptionPane.showMessageDialog(
            null,
            "추출이 완료되었습니다.",
            "완료",
            JOptionPane.INFORMATION_MESSAGE
        )
    }


    private fun collectStatusPaths(root: String, since: Date, until: Date): List<String> {
        val client = createClientManagerWithCachedAuth()
        val baseTimeZone = ZoneId.systemDefault()
        val files = mutableListOf<String>()

        client.statusClient.doStatus(
            File(root), true, false, false, false
        ) { status ->
            if (status.contentsStatus in listOf(
                    SVNStatusType.STATUS_MODIFIED,
                    SVNStatusType.STATUS_ADDED,
                    SVNStatusType.STATUS_DELETED,
                    SVNStatusType.STATUS_REPLACED
                )
            ) {
                val file = status.file
                val date = Instant.ofEpochMilli(file.lastModified()).atZone(baseTimeZone).toLocalDate()
                if (!date.isBefore(
                        since.toInstant().atZone(baseTimeZone).toLocalDate()
                    ) && !date.isAfter(until.toInstant().atZone(baseTimeZone).toLocalDate())
                ) {
                    files.add(file.absolutePath)
                }
            }
        }
        return files
    }

    private fun collectDiffPaths(root: String, start: Date, end: Date): List<String> {
        val client = createClientManagerWithCachedAuth()
        val paths = mutableListOf<String>()

        try{
            client.logClient.doLog(
                arrayOf(File(root)), SVNRevision.create(start), SVNRevision.create(end), false, true, 0L
            ) { logEntry ->
                logEntry.changedPaths.values.forEach { change ->
                    paths.add(change.path)
                }
            }
        }catch (e : SVNException){
            log.info("SVN 로그 수집 중 오류 발생: ${e.message}")
        }

        return paths
    }

    private fun createClientManagerWithCachedAuth(): SVNClientManager {
        val dir = detectSvnConfigDir()
        require(dir.exists()) { "SVN 설정 디렉터리 없음: ${dir.path}" }
        val opts = SVNWCUtil.createDefaultOptions(dir, true)
        val auth = SVNWCUtil.createDefaultAuthenticationManager(dir)
        return SVNClientManager.newInstance(opts, auth)
    }

    private fun detectSvnConfigDir(): File {
        val os = System.getProperty("os.name").lowercase()
        return if (os.contains("win")) File(System.getenv("APPDATA"), "Subversion")
        else File(System.getProperty("user.home"), ".subversion")
    }


    /* SVN 계정 정보 받기*/
    private fun collectSvnCredentials(): Pair<String, String> {
        // 1) 필드 생성
        val userField = JTextField(15)
        val passField = JPasswordField(15)

        // 2) 레이블 + 필드를 GridLayout 패널에 붙이기
        val inputPanel = JPanel(GridLayout(2, 2, 5, 5)).apply {
            add(JLabel("SVN 사용자명:"))
            add(userField)
            add(JLabel("SVN 비밀번호:"))
            add(passField)
        }

        // 3) 안내 문구와 입력 패널을 함께 띄우기
        val message = arrayOf(
            JLabel("svn 메타정보를 읽어 오는데 실패 했습니다."),
            JLabel("svn 계정 정보를 입력해 주세요."),
            JLabel("입력하지 않을 경우, 수정된 파일을 기준으로 배포됩니다."),
            inputPanel
        )

        val result = JOptionPane.showConfirmDialog(
            null, message, "SVN 로그인 정보 입력", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE
        )

        // 4) 취소나 닫기 선택 시 test() 호출 후 빈 Pair 반환
        if (result != JOptionPane.OK_OPTION) {
            return "" to ""
        }

        // 5) OK 선택 시 입력값 검증
        val user = userField.text.trim().takeIf { it.isNotEmpty() } ?: throw IllegalStateException("사용자명이 입력되지 않았습니다.")
        val pass = passField.password.concatToString().takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("비밀번호가 입력되지 않았습니다.")

        return user to pass
    }


}