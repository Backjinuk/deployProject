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

class SvnInfoCliTest {

    /** TortoiseSVN/CLI 공용 config 디렉터리 감지 */
    private fun detectSvnConfigDir(): File {
        val os = System.getProperty("os.name").lowercase()
        return if (os.contains("win")) File(System.getenv("APPDATA"), "Subversion")
        else                                File(System.getProperty("user.home"), ".subversion")
    }

    /** 캐시된 인증 사용 clientManager 생성 */
    private fun createClientManagerWithCachedAuth(): SVNClientManager {
        val cfg = detectSvnConfigDir()
        assertTrue(cfg.exists(), "SVN 설정 디렉터리 없음: ${cfg.path}")
        val opts = SVNWCUtil.createDefaultOptions(cfg, true)
        val auth = SVNWCUtil.createDefaultAuthenticationManager(cfg)
        return SVNClientManager.newInstance(opts, auth)
    }

    @Test
    fun `doInfo_캐시된_인증으로_메타데이터_조회_테스트`() {
        // 목적: .svn 메타데이터에서 마지막 커밋 리비전만 읽어오는 테스트
//        val root = "/Users/mac/IdeaProjects/icarevalue_home"
        val root = "D:/DevSpace/FGI_Space/FGI_kisia"

        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        val startDate = fmt.parse("2024-10-01 00:00:00")
        val endDate = fmt.parse("2024-10-31 23:59:59")


        if(File("/Users/mac", ".subversion").exists()){

        }



        val clientManager = createClientManagerWithCachedAuth()

        // 커밋된 파일 목록 수집
        val committedFiles = mutableListOf<String>()
        clientManager.logClient.doLog(
            /*path */          arrayOf(File(root)),
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

        println("▶ 커밋된 파일 목록:")
        committedFiles.forEach { println("   • $it") }


    }

    @Test
    fun `status_캐시된_인증으로_로컬_변경파일_조회_테스트`() {
        // 목적: 캐시된 자격증명만으로 로컬 변경 파일 목록을 조회하는지 검증
        val root = "/Users/mac/IdeaProjects/icarevalue_home"
//        val root = "D:/DevSpace/FGI_Space/FGI_kisia"
        assertTrue(File(root, ".svn").isDirectory, ".svn 폴더가 없습니다: $root")

        val client = createClientManagerWithCachedAuth()
        val changedFiles = mutableListOf<File>()

        // SVNDepth / SVNRevision 없이 boolean 오버로드 사용
        client.statusClient.doStatus(
            File(root),
            /* recursive       = */ true,
            /* remote          = */ false,
            /* reportExternals = */ false,
            /* includeIgnored  = */ false
        ) { status ->
            if (status.contentsStatus != SVNStatusType.STATUS_NORMAL) {
                changedFiles.add(status.file)
            }
        }

        if (changedFiles.isEmpty()) {
            println("▶ 로컬 변경 파일 없음")
        } else {
            println("▶ 로컬 변경 파일:")
            changedFiles.forEach { println("   • ${it.absolutePath}") }
        }
    }





    /**
     * 콘솔에서 SVN 사용자명·비밀번호를 입력받아 반환합니다.
     */
    fun promptForSvnCredentials(): Pair<String, String> {
        val console: Console? = System.console()
        // IDE 환경에서는 console이 null일 수 있으니, fallback으로 readLine() 사용
        val username = "fgi_dev"
        val password = "fgi_dev04@@"

        return username to password
    }

    /**
     * macOS 키체인 대신, 런타임에 입력받은 자격증명으로 SVNClientManager를 생성합니다.
     */
    fun createClientManagerWithInteractiveAuth(): SVNClientManager {
        val (user, pass) = promptForSvnCredentials()

        // 전역 설정 디렉터리 (예: ~/.subversion)
        val cfgDir = detectSvnConfigDir()
        val opts   = SVNWCUtil.createDefaultOptions(cfgDir, true)
        val auth   = BasicAuthenticationManager(user, pass)

        return SVNClientManager.newInstance(opts, auth)
    }

    // 테스트 예시
    @Test
    fun `interactive_auth로_svn_log_조회_테스트`() {
        val root = "/Users/mac/IdeaProjects/icarevalue_home"
        assertTrue(File(root, ".svn").isDirectory, ".svn 폴더가 없습니다: $root")

        val clientManager = createClientManagerWithInteractiveAuth()
        val info = clientManager
            .wcClient
            .doInfo(File(root), SVNRevision.WORKING)

        println("▶ 마지막 커밋 리비전: r${info.committedRevision.number}")
        // …이어서 logClient.doLog() 호출…
    }


    /**
     * rootDir 하위의 모든 파일을 순회하며,
     * lastModified 타임스탬프가 fromDate~toDate 사이인 파일 리스트를 반환합니다.
     */
    fun listFilesModifiedBetween(rootDir: String, fromDate: Date, toDate: Date): List<File> {
        val fromMillis = fromDate.time
        val toMillis   = toDate.time
        return File(rootDir).walkTopDown()
            .filter { it.isFile }
            .filter { it.lastModified() in fromMillis..toMillis }
            .toList()
    }

    // 사용 예시
    @Test
    fun `수정된 모든 파일의 리스트 가지고 오기`() {
        val fmt       = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        val startDate = fmt.parse("2025-05-21 00:00:00")
        val endDate   = fmt.parse("2025-05-22 23:59:59")

        val root = "/Users/mac/IdeaProjects/icarevalue_home"
        val filesInRange = listFilesModifiedBetween(root, startDate, endDate)

        if (filesInRange.isEmpty()) {
            println("▶ 해당 기간에 수정된 파일이 없습니다.")
        } else {
            println("▶ 해당 기간에 수정된 파일 목록:")
            filesInRange.forEach { println("   • ${it.absolutePath}") }
        }
    }


}