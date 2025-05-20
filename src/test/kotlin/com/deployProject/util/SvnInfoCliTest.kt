package com.deployProject.util

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.tmatesoft.svn.core.wc.SVNClientManager
import org.tmatesoft.svn.core.wc.SVNInfo
import org.tmatesoft.svn.core.wc.SVNRevision
import org.tmatesoft.svn.core.wc.SVNStatusType
import org.tmatesoft.svn.core.wc.SVNWCUtil
import java.io.File

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
        val root = "/Users/mac/IdeaProjects/icarevalue_home"
        assertTrue(File(root, ".svn").isDirectory, ".svn 폴더가 없습니다: $root")

        val info: SVNInfo = createClientManagerWithCachedAuth()
            .wcClient
            .doInfo(File(root), SVNRevision.WORKING)

        assertTrue(info.committedRevision.number >= 0, "리비전은 0 이상이어야 합니다.")
        println("▶ Revision: r${info.committedRevision.number}")
    }

    @Test
    fun `status_캐시된_인증으로_로컬_변경파일_조회_테스트`() {
        // 목적: 캐시된 자격증명만으로 로컬 변경 파일 목록을 조회하는지 검증
        val root = "/Users/mac/IdeaProjects/icarevalue_home"
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
}