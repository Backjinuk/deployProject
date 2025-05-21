package com.deployProject.util

import com.deployProject.deploy.domain.site.FileStatusType
import de.regnis.q.sequence.core.QSequenceAssert.assertTrue
import org.tmatesoft.svn.core.wc.SVNClientManager
import org.tmatesoft.svn.core.wc.SVNRevision
import org.tmatesoft.svn.core.wc.SVNStatusType
import org.tmatesoft.svn.core.wc.SVNWCUtil
import java.awt.GridLayout
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextField

object SvnInfoCli {


    /**
     * 진입점: <gitDir> [sinceDate] [untilDate] [fileStatusType]
     */
    @JvmStatic
    fun main(args: Array<String>) {

        /* 사용자가 svn 정보를 기입할수 있*/
        collectSvnCredentials()
//        val (svnUser, svnPass) = promptForSvnCredentialsOneShot()
//        println("[DEBUG] SVN Credentials: $svnUser / ${"*".repeat(svnPass.length)}")

//        val repoPath = args.getOrNull(0)
//            ?: error("Usage: java -jar git-info-cli.jar <gitDir> [sinceDate] [untilDate] [fileStatusType]")
//
//        val dateFmt = DateTimeFormatter.ofPattern("yyyy/MM/dd")
//        val since = parseDateArg(args.getOrNull(2), dateFmt)
//        val until = parseDateArg(args.getOrNull(3), dateFmt)
//        val statusType = parseStatusType(args.getOrNull(4))
//        val deployServerDir = args.getOrNull(5)?.takeIf { it.isNotBlank() } ?: "/home/bjw/deployProject/."
//
//        SvnInfoCli.run(repoPath, since, until, statusType, deployServerDir)
    }

    @Throws(IOException::class)
    fun run(
        repoPath: String,
        since: LocalDate,
        until: LocalDate,
        fileStatusType: FileStatusType,
        deployServerDir: String
    ) {

        val svnDir = parseSvnDir(repoPath)
        val workTree = svnDir.parentFile
        val outputZip = determineOutputZip(svnDir)


        val svnStatusPath = collectStatusPath(repoPath)
//        val svnDiffPath = collectDiffPath(repoPath, since, until)

    }


    private fun collectStatusPath(rootPath: String): List<String> {
        val svnDir = detectSvnConfigDir()
        val clientManager = createClientManagerWithCachedAuth()
        val svnStatus = clientManager.statusClient
        val svnDiff = clientManager.diffClient

        // SVN 상태 수집 로직

        val client = createClientManagerWithCachedAuth()
        val changedFiles = mutableListOf<File>()

        // SVNDepth / SVNRevision 없이 boolean 오버로드 사용
        client.statusClient.doStatus(
            File(rootPath),
            /* recursive       = */ true,
            /* remote          = */ false,
            /* reportExternals = */ false,
            /* includeIgnored  = */ false
        ) { status ->
            if (status.contentsStatus != SVNStatusType.STATUS_NORMAL) {
                changedFiles.add(status.file)
            }
        }

        return changedFiles.map { path -> path.absolutePath }
    }

    private fun collectDiffPath(rootPath: String, startDate : Date , endDate : Date): List<String> {
        val clientManager = createClientManagerWithCachedAuth()

        // SVN 상태 수집 로직
        val changedFiles = mutableListOf<File>()
        try{

            clientManager.logClient.doLog(
                /*path */          arrayOf(File(rootPath)),
                /*startRevision*/  SVNRevision.create(startDate),
                /*endRevision */   SVNRevision.create(endDate),
                /*stopOnCopy  */   false,
                /*discoverPaths*/  true,
                /*limit */         0L /*제한 없음*/
            )
            /*ISVNLogEntryHandler*/     { logEntry ->
                println("▶ r${logEntry.revision} 에서 변경된 파일:")
                logEntry.changedPaths.values.forEach { change ->
                    println("   ${change.type} ${change.path}")
                }
            }
        }catch (e: Exception) {
            println("Error during SVN log retrieval: ${e.message}")




        }

        return changedFiles.map { path -> path.absolutePath }
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
            null,
            message,
            "SVN 로그인 정보 입력",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE
        )

        // 4) 취소나 닫기 선택 시 test() 호출 후 빈 Pair 반환
        if (result != JOptionPane.OK_OPTION) {
            return "" to ""
        }

        // 5) OK 선택 시 입력값 검증
        val user = userField.text.trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("사용자명이 입력되지 않았습니다.")
        val pass = passField.password.concatToString().takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("비밀번호가 입력되지 않았습니다.")

        return user to pass
    }





    /** TortoiseSVN/CLI 공용 config 디렉터리 감지 */
    private fun detectSvnConfigDir(): File {
        val os = System.getProperty("os.name").lowercase()
        println("OS: $os")
        return if (os.contains("win")) File(System.getenv("APPDATA"), "Subversion")
        else File("/Users/mac", ".subversion")


    }

    /** 캐시된 인증 사용 clientManager 생성 */
    private fun createClientManagerWithCachedAuth(): SVNClientManager {
        val cfg = detectSvnConfigDir()
        assertTrue(cfg.exists(), "SVN 설정 디렉터리 없음: ${cfg.path}")
        val opts = SVNWCUtil.createDefaultOptions(cfg, true)
        val auth = SVNWCUtil.createDefaultAuthenticationManager(cfg)
        return SVNClientManager.newInstance(opts, auth)
    }


    private fun parseStatusType(statusType: String?): FileStatusType = statusType?.let {
        try {
            FileStatusType.valueOf(statusType)
        } catch (e: Exception) {
            FileStatusType.ALL
        }
    } ?: FileStatusType.ALL

    private fun parseDateArg(dateStr: String?, dateFmt: DateTimeFormatter): LocalDate {
        return dateStr?.let {
            try {
                LocalDate.parse(it, dateFmt)
            } catch (e: Exception) {
                error("Invalid date format: $it. Expected format: yyyy/MM/dd")
            }
        } ?: LocalDate.now()
    }


    private fun parseSvnDir(repoPath: String): File = File(repoPath).apply {
        if (!exists() || !File(this, "config").exists()) {
            error("ERROR: Not a valid Git repository: ${absolutePath}")
        }
    }

    private fun determineOutputZip(svnDir: File): File {
        val date = SimpleDateFormat("yyyyMMdd").format(Date())
        return File(svnDir.parentFile, "$date.zip")
    }


    fun promptForSvnCredentialsOneShot(): Pair<String, String> {
        // 1) 필드 생성
        val userField = JTextField(15)
        val passField = JPasswordField(15)

        // 2) 레이블 + 필드를 GridLayout 패널에 붙이기
        val panel = JPanel(GridLayout(2, 2, 5, 5)).apply {
            add(JLabel("SVN 사용자명:"))
            add(userField)
            add(JLabel("SVN 비밀번호:"))
            add(passField)
        }

        // 3) 하나의 다이얼로그로 띄우기
        val result = JOptionPane.showConfirmDialog(
            null,
            panel,
            "SVN 로그인 정보 입력",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE
        )

        if (result != JOptionPane.OK_OPTION) {
            throw IllegalStateException("SVN 로그인 입력이 취소되었습니다.")
        }

        val user = userField.text.trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("사용자명이 입력되지 않았습니다.")
        val pass = passField.password.concatToString()
            .takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("비밀번호가 입력되지 않았습니다.")

        return user to pass
    }

}