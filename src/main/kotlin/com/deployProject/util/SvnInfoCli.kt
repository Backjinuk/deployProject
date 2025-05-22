package com.deployProject.util

import com.deployProject.deploy.domain.site.FileStatusType
import de.regnis.q.sequence.core.QSequenceAssert.assertTrue
import org.tmatesoft.svn.core.wc.SVNClientManager
import org.tmatesoft.svn.core.wc.SVNRevision
import org.tmatesoft.svn.core.wc.SVNStatusType
import org.tmatesoft.svn.core.wc.SVNWCUtil
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
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextField
import kotlin.math.log

object SvnInfoCli {

    private val zippedEntries = mutableSetOf<String>()

    /**
     * 진입점: <gitDir> [sinceDate] [untilDate] [fileStatusType]
     */
    @JvmStatic
    fun main(args: Array<String>) {

        /* 사용자가 svn 정보를 기입할수 있*/
//        val (svnUser, svnPass) = promptForSvnCredentialsOneShot()
//        println("[DEBUG] SVN Credentials: $svnUser / ${"*".repeat(svnPass.length)}")

        val repoPath = args.getOrNull(0)
            ?: error("Usage: java -jar git-info-cli.jar <gitDir> [sinceDate] [untilDate] [fileStatusType]")

        val dateFmt = SimpleDateFormat("yyyy/MM/dd")
        val since = parseDateArg(args.getOrNull(2), dateFmt)
        val until = parseDateArg(args.getOrNull(3), dateFmt)
        val statusType = parseStatusType(args.getOrNull(4))
        val deployServerDir = args.getOrNull(5)?.takeIf { it.isNotBlank() } ?: "/home/bjw/deployProject/."

        run(repoPath, since, until, statusType, deployServerDir)
    }

    @Throws(IOException::class)
    fun run(
        repoPath: String,
        since: Date,
        until: Date,
        fileStatusType: FileStatusType,
        deployServerDir: String
    ) {

//        val svnDir = parseSvnDir(repoPath)
        println(repoPath)
        val svnDir = File(repoPath)
        val workTree = svnDir.parentFile
        val outputZip = determineOutputZip(svnDir)


        val svnStatusPath = collectStatusPath(repoPath, since, until)
        val svnDiffPath = collectDiffPath(repoPath, since, until)

        svnStatusPath.forEach { path ->
            println("svnStatusPath = ${path}")
        }

        val diffEntries = mapSourcesToClasses(svnDiffPath, workTree)
        val statusEntries = mapSourcesToClasses(svnStatusPath, workTree)

        statusEntries.forEach { path ->
            println("path = ${path}")
        }
//        createZip(outputZip){ zip ->
//           if(fileStatusType.allowsStatus()){
//               println("statusPath: $svnStatusPath")
//               addZipEntry(zip,  workTree, statusEntries)
//           }
//
//            if(fileStatusType.allowsStatus()){
//                println("diffPath: $svnDiffPath")
//                addZipEntry(zip,  workTree, diffEntries)
//            }
//        }

        println("✅ Created ZIP: ${outputZip.absolutePath}")
    }


    private fun createZip(
        output: File,
        block: (ZipOutputStream) -> Unit
    ) {
        ZipOutputStream(Files.newOutputStream(output.toPath())).use(block)
    }


    private fun addZipEntry(zip: ZipOutputStream, baseDir : File, paths : List<String>) {
        val basePath = baseDir.toPath()
        paths.forEach { rel ->
            val file = if (Paths.get(rel).isAbsolute) File(rel) else File(baseDir, rel)

           if (!file.exists()) return@forEach

           if(file.isDirectory){
               Files.walk(file.toPath()).filter { Files.isRegularFile(it) }
                   .forEach { child -> addZipFile(zip, basePath, child.toFile()) }
           }else{
                addZipFile(zip, basePath, file)
           }

        }
    }

    private fun addZipFile(zip: ZipOutputStream, basePath : Path, file : File) {
        val entryName = basePath.relativize(file.toPath())
            .toString()
            .replace(File.separatorChar, '/')

        if (!SvnInfoCli.zippedEntries.add(entryName)) return

        zip.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { it.copyTo(zip) }
        zip.closeEntry()
    }


    private fun collectStatusPath(rootPath: String, since : Date, until : Date): List<String> {
        val svnDir = File(rootPath)
        val dateZone = ZoneId.systemDefault()

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

        return changedFiles.map { File(it.absolutePath) }
            .filter {file ->
                file.exists().also {  if (!it) print("Missing: $file")  }
            }
            .filter { file ->
                val fileDate = Instant.ofEpochMilli(file.lastModified())
                    .atZone(dateZone).toLocalDate()
                !fileDate.isBefore(since.toInstant().atZone(ZoneId.systemDefault()).toLocalDate())
                        && !fileDate.isAfter(until.toInstant().atZone(ZoneId.systemDefault()).toLocalDate())
            }.map { it.absolutePath }
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
                logEntry.changedPaths.values.forEach { change ->
                }
            }
        }catch (e: Exception) {
//            val (user, passwd) = collectSvnCredentials()


            println("Error during SVN log retrieval: ${e.message}")
        }

        return changedFiles.map { path -> path.absolutePath }
    }

    private fun mapSourcesToClasses(
        sources: List<String>,
        workTree: File
    ): List<String> = sources
        .flatMap { src ->
            if (!src.endsWith(".kt") && !src.endsWith(".java")) return@flatMap listOf(src)
            mapToClassEntry(src, workTree) ?: emptyList()
        }
        .distinct()

    private fun mapToClassEntry(
        src: String,
        workTree: File
    ): List<String>? {
        val baseName = File(src).nameWithoutExtension
        val pattern = Regex("^${Regex.escape(baseName)}(\\$.*)?\\.class$")

        val entries = Files.walk(workTree.toPath())
            .filter { path -> Files.isRegularFile(path) }
            .filter { path -> pattern.matches(path.fileName.toString()) }
            .map { path ->
                workTree.toPath()
                    .relativize(path)
                    .toString()
                    .replace(File.separatorChar, '/')
            }.toList()

        return entries
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

    private fun parseDateArg(dateStr: String?, dateFmt: SimpleDateFormat): Date {
        return dateStr?.let {
            try {
                dateFmt.parse(it)
            } catch (e: Exception) {
                error("Invalid date format: $it. Expected format: yyyy/MM/dd")
            }
        } ?: Date()
    }


    private fun parseSvnDir(repoPath: String): File = File(repoPath).apply {
        if (!exists() || !File(this, "config").exists()) {
            error("ERROR: Not a valid Git repository: ${absolutePath}")
        }
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

    private fun FileStatusType.allowsDiff() = this == FileStatusType.DIFF || this == FileStatusType.ALL
    private fun FileStatusType.allowsStatus() = this == FileStatusType.STATUS || this == FileStatusType.ALL
}