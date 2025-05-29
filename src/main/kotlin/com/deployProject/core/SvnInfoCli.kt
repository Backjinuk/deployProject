package com.deployProject.core


import com.deployProject.deploy.domain.site.FileStatusType
import de.regnis.q.sequence.core.QSequenceAssert.assertTrue
import org.slf4j.LoggerFactory
import org.tmatesoft.svn.core.wc.SVNClientManager
import org.tmatesoft.svn.core.wc.SVNRevision
import org.tmatesoft.svn.core.wc.SVNStatusType
import org.tmatesoft.svn.core.wc.SVNWCUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Frame
import java.awt.GridLayout
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.*
import java.util.Date

class SvnInfoCli {

    private val log = LoggerFactory.getLogger(SvnInfoCli::class.java)
    private val zippedEntries = mutableSetOf<String>()
    private var statusClassMap: Map<String, String> = mapOf()
    private var diffClassMap: Map<String, String> = mapOf()

    /**
     * 진입점: <gitDir> [sinceDate] [untilDate] [fileStatusType]
     */
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {

            val repoPath = args.getOrNull(0)
                ?: error("Usage: java -jar git-info-cli.jar <gitDir> [sinceDate] [untilDate] [fileStatusType]")

            val dateFmt = SimpleDateFormat("yyyy/MM/dd")
            val since = SvnInfoCli().parseDateArg(args.getOrNull(2)?.substringBefore('T'), dateFmt)
            val until = SvnInfoCli().parseDateArg(args.getOrNull(3)?.substringBefore('T'), dateFmt)
            val statusType = SvnInfoCli().parseStatusType(args.getOrNull(4))
            val deployServerDir = args.getOrNull(5)?.takeIf { it.isNotBlank() } ?: "/home/bjw/deployProject/."

            SvnInfoCli().run(repoPath, since, until, statusType, deployServerDir)
        }
    }

    @Throws(IOException::class)
    fun run(
        repoPath: String, since: Date, until: Date, fileStatusType: FileStatusType, deployServerDir: String
    ) {

        showProgressAndRun(initialMessage = "SVN 배포를 시작합니다…") {

            val svnDir = parseSvnDir(repoPath)
            val workTree = if (svnDir.name == ".svn") svnDir.parentFile else svnDir
            val outputZip = determineOutputZip(svnDir)
            val svnStatusPath: List<String> = collectStatusPath(svnDir.path, since, until)
            val svnDiffPath: List<String> = collectDiffPath(svnDir.path, since, until)

            // classMap 생성
            statusClassMap = buildLatestClassMap(workTree, svnStatusPath)
            diffClassMap = buildLatestClassMap(workTree, svnDiffPath)

            val diffEntries = mapSourcesToClasses(svnDiffPath)
            val statusEntries = mapSourcesToClasses(svnStatusPath)

            val webappDir = File(workTree, "src/main/webapp")

            val entries = (diffEntries + statusEntries).distinct()

            val relativeEntries = entries.map { absPath ->
                File(absPath)
                    .relativeTo(webappDir)      // src/main/webapp/ 다음부터 상대경로
                    .path
                    .replace("\\", "/")         // 윈도우 '\' 제거
            }

            // ZIP 생성 부분
            createZip(outputZip) { zip ->
                if (fileStatusType.allowsStatus()) {
                    addZipEntry(zip, workTree, statusEntries)
                }
                if (fileStatusType.allowsStatus()) {
                    addZipEntry(zip, workTree, diffEntries)
                }

                ScriptCreate()
                    .getLegacyPatchScripts(relativeEntries, deployServerDir)
                    .forEach { (scriptName, lines) ->
                        zip.putNextEntry(ZipEntry(scriptName))
                        zip.write(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
            }

            println("✅ Created ZIP: ${outputZip.absolutePath}")
        }

        JOptionPane.showMessageDialog(
            null,
            """
          배포가 완료되었습니다.
          파일: ${repoPath.toString()}
          시간: ${SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(Date())}
        """.trimIndent(),
            "완료",
            JOptionPane.INFORMATION_MESSAGE
        )
    }

    private fun buildLatestClassMap(
        workTree: File, paths: List<String>
    ): Map<String, String> {

        val statusBaseNames = paths.filter { it.endsWith(".kt", true) || it.endsWith(".java", true) }
            .map { File(it).nameWithoutExtension }.toSet()

        // 2) 워크트리 전체를 스캔하며, 관심 있는 baseName만 수집
        return Files.walk(workTree.toPath()).use { stream ->
            stream.filter(Files::isRegularFile).filter { it.fileName.toString().endsWith(".class", true) }
                // .class 이름에서 baseName 추출
                .map { path ->
                    val fileName = path.fileName.toString()
                    val base = fileName.substringBeforeLast('.').substringBefore('$')
                    Pair(base, path)
                }
                // 변경된 소스에 해당하는 baseName만 필터
                .filter { (base, _) -> base in statusBaseNames }
                // 그룹핑 후 최신 파일만 남김
                .toList().groupBy({ it.first }, { it.second }).mapValues { (_, paths) ->
                    paths.maxByOrNull { Files.getLastModifiedTime(it).toMillis() }!!.toAbsolutePath().toString()
                }
        }
    }


    private fun createZip(
        output: File, block: (ZipOutputStream) -> Unit
    ) {
        ZipOutputStream(Files.newOutputStream(output.toPath())).use(block)
    }


    private fun addZipEntry(zip: ZipOutputStream, baseDir: File, paths: List<String>) {
        val basePath = baseDir.toPath()
        paths.forEach { rel ->
            val file = if (Paths.get(rel).isAbsolute) File(rel) else File(baseDir, rel)

            if (!file.exists()) return@forEach

            if (file.isDirectory) {
                Files.walk(file.toPath()).filter { Files.isRegularFile(it) }
                    .forEach { child -> addZipFile(zip, basePath, child.toFile()) }
            } else {
                addZipFile(zip, basePath, file)
            }

        }
    }

    private fun addZipFile(zip: ZipOutputStream, basePath: Path, file: File) {
        val entryName = basePath.relativize(file.toPath()).toString().replace(File.separatorChar, '/')

        if (!zippedEntries.add(entryName)) return

        zip.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { it.copyTo(zip) }
        zip.closeEntry()
    }


    private fun collectStatusPath(rootPath: String, since: Date, until: Date): List<String> {
        File(rootPath)
        val dateZone = ZoneId.systemDefault()

        val clientManager = createClientManagerWithCachedAuth()
        clientManager.statusClient
        clientManager.diffClient

        // SVN 상태 수집 로직

        val client = createClientManagerWithCachedAuth()
        val changedFiles = mutableListOf<File>()

        // SVNDepth / SVNRevision 없이 boolean 오버로드 사용
        client.statusClient.doStatus(
            File(rootPath),/* recursive       = */
            true,/* remote          = */
            false,/* reportExternals = */
            false,/* includeIgnored  = */
            false
        ) { status ->
            if (status.contentsStatus in setOf(
                    SVNStatusType.STATUS_MODIFIED,
                    SVNStatusType.STATUS_ADDED,
                    SVNStatusType.STATUS_DELETED,
                    SVNStatusType.STATUS_REPLACED
                ) ) {
                changedFiles.add(status.file)
            }
        }

        return changedFiles.map { File(it.absolutePath) }.filter { file ->
            file.exists().also { if (!it) print("Missing: $file") }
        }.filter { file ->
            val fileDate = Instant.ofEpochMilli(file.lastModified()).atZone(dateZone).toLocalDate()
            !fileDate.isBefore(since.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()) && !fileDate.isAfter(
                until.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
            )
        }.map { it.absolutePath }
    }

    private fun collectDiffPath(rootPath: String, startDate: Date, endDate: Date): List<String> {

        val clientManager = createClientManagerWithCachedAuth()

        // SVN 상태 수집 로직
        val changedFiles = mutableListOf<File>()
        try {

            clientManager.logClient.doLog(/*path */          arrayOf(File(rootPath)),/*startRevision*/
                SVNRevision.create(startDate),/*endRevision */
                SVNRevision.create(endDate),/*stopOnCopy  */
                false,/*discoverPaths*/
                true,/*limit */
                0L /*제한 없음*/
            ){ logEntry ->
                logEntry.changedPaths.values.forEach { change ->
                }
            }
        } catch (e: Exception) {
//            val (user, passwd) = collectSvnCredentials()


            println("Error during SVN log retrieval: ${e.message}")
        }

        return changedFiles.map { path -> path.absolutePath }
    }

    private fun mapSourcesToClasses(
        sources: List<String>
    ): List<String> = sources.flatMap { src ->
        if (!src.endsWith(".kt") && !src.endsWith(".java")) {
            listOf(src)
        } else {
            val base = File(src).nameWithoutExtension
            statusClassMap[base]?.let { listOf(it) }.orEmpty()
        }.distinct()
    }

    private fun mapToClassEntry(
        baseName: String, workTree: File
    ): List<String> {
        val pattern = Regex("^${Regex.escape(baseName)}(\\$.*)?\\.class$")
        return Files.walk(workTree.toPath()).filter(Files::isRegularFile)
            .filter { pattern.matches(it.fileName.toString()) }.map { path ->
                workTree.toPath().relativize(path).toString().replace(File.separatorChar, '/')
            }.toList()
    }

    private fun completeAlert(msg : String){
        val labels: Array<JLabel> = msg
            .split("<br>")
            .map { JLabel(it) }
            .toTypedArray()

        JOptionPane.showMessageDialog(null, labels, "Deploy Man", JOptionPane.INFORMATION_MESSAGE)
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


    /** TortoiseSVN/CLI 공용 config 디렉터리 감지 */
    private fun detectSvnConfigDir(): File {
        val os = System.getProperty("os.name").lowercase()
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

    private fun parseDateArg(dateStr: String?, dateFmt: SimpleDateFormat): Date {
        try {
            val instant = Instant.parse(dateStr)
            return Date.from(instant)
        }catch (e : Exception){
            // dateStr이 ISO 8601 형식이 아닐 경우
            e.printStackTrace()
        }

        return dateStr?.let {
            try {
                dateFmt.parse(it)
            } catch (e: Exception) {
                error("Invalid date format: $it. Expected format: yyyy/MM/dd")
            }
        } ?: Date()
    }


    private fun parseSvnDir(repoPath: String): File {
        val f = File(repoPath)
        return if (f.name == ".svn") { f.parentFile
        } else { f }

    }

    private fun determineOutputZip(svnDir: File): File {
        val date = SimpleDateFormat("yyyyMMdd").format(Date())
        return File(svnDir, "$date.zip")
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
            null, panel, "SVN 로그인 정보 입력", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE
        )

        if (result != JOptionPane.OK_OPTION) {
            throw IllegalStateException("SVN 로그인 입력이 취소되었습니다.")
        }

        val user = userField.text.trim().takeIf { it.isNotEmpty() } ?: throw IllegalStateException("사용자명이 입력되지 않았습니다.")
        val pass = passField.password.concatToString().takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("비밀번호가 입력되지 않았습니다.")

        return user to pass
    }




    fun showProgressAndRun(
        title: String = "SVN 배포 중…",
        initialMessage: String = "잠시만 기다려 주세요…",
        task: () -> Unit
    ) {
        // 1) 다이얼로그 & 컴포넌트 준비
        val dialog = JDialog(null as Frame?, title, true).apply {
            layout = BorderLayout(10, 10)

            // 메시지 라벨
            val label = JLabel(initialMessage).apply {
                horizontalAlignment = JLabel.CENTER
            }
            add(label, BorderLayout.NORTH)

            // 무한 모드 프로그레스 바
            val progressBar = JProgressBar().apply {
                isIndeterminate = true
                preferredSize = Dimension(300, 20)
            }
            add(progressBar, BorderLayout.CENTER)

            pack()
            setLocationRelativeTo(null)
        }

        // 2) 백그라운드에서 실제 작업 수행
        object : SwingWorker<Unit, Unit>() {
            override fun doInBackground() {
                task()
            }
            override fun done() {
                // 작업 끝나면 다이얼로그 닫기
                dialog.dispose()
            }
        }.execute()

        // 3) 모달 다이얼로그 보여주기 (이 뒤는 블록됨)
        dialog.isVisible = true
    }




    private fun FileStatusType.allowsDiff() = this == FileStatusType.DIFF || this == FileStatusType.ALL
    private fun FileStatusType.allowsStatus() = this == FileStatusType.STATUS || this == FileStatusType.ALL
}