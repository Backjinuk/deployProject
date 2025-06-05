package com.deployProject.cli.utilCli

import com.deployProject.deploy.domain.site.FileStatusType
import java.awt.BorderLayout
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JProgressBar
import javax.swing.SwingWorker

/**
 * GitUtil: Git/Zip helper utilities for extracting changed files,
 * mapping to class outputs, and packaging results into a zip archive
 */
object GitUtil {
    // 기록된 엔트리를 중복 없이 관리
    private val zippedEntries = mutableSetOf<String>()
    // 소스 파일명과 최신 클래스 파일 경로 맵
    private var statusClassMap: Map<String, String> = mapOf()
    private var diffClassMap:   Map<String, String> = mapOf()

    /**
     * FileStatusType이 DIFF 또는 ALL을 허용하는지 확인
     */
    fun FileStatusType.allowsDiff(): Boolean = this == FileStatusType.DIFF || this == FileStatusType.ALL

    /**
     * FileStatusType이 STATUS 또는 ALL을 허용하는지 확인
     */
    fun FileStatusType.allowsStatus(): Boolean = this == FileStatusType.STATUS || this == FileStatusType.ALL

    // ───────────────────────────────────────────────────────────
    //  Argument Parsing Helpers
    // ───────────────────────────────────────────────────────────

    /**
     * 날짜 문자열(arg)이 비어있으면 오늘 날짜를, 아니면 지정 포맷(fmt)으로 LocalDate 파싱
     */
    fun parseDateArg(arg: String?, fmt: DateTimeFormatter): LocalDate {
        if (arg.isNullOrBlank()) return LocalDate.now()
        return try {
            LocalDate.parse(arg.substringBefore('T'), fmt)
        } catch (e: Exception) {
            error("잘못된 날짜 형식: '$arg'. 기대 형식: $fmt")
        }
    }

    fun parseDateArg(dateStr: String?, dateFmt: SimpleDateFormat): Date {
        return dateStr?.let {
            try {
                dateFmt.parse(it)
            } catch (e: Exception) {
                error("Invalid date format: $it. Expected format: yyyy/MM/dd")
            }
        } ?: Date()
    }

    /**
     * Git 레포 부모 디렉터리에 날짜 기반 ZIP 파일명 결정
     */
    fun determineOutputZip(gitDir: File): File {
        val date = SimpleDateFormat("yyyyMMdd").format(Date())
        return File(gitDir.parentFile, "$date.zip")
    }

    /**
     * '.git' 폴더인지 검사하여 실제 워크트리 디렉터리 반환
     */
    fun parseDir(repoPath: String, dirType: String): File {
        val f = File(repoPath)
        return if (f.name == ".${dirType}") f.parentFile else f
    }

    /**
     * 문자열로 받은 상태 타입을 FileStatusType enum으로 변환
     */
    fun parseStatusType(statusType: String?): FileStatusType = try {
        statusType?.let { FileStatusType.valueOf(it) } ?: FileStatusType.ALL
    } catch (e: Exception) {
        FileStatusType.ALL
    }

    // ───────────────────────────────────────────────────────────
    //  Class Mapping Logic
    // ───────────────────────────────────────────────────────────

    /**
     * 소스 파일 경로 리스트를 클래스 파일 경로 리스트로 매핑
     * .kt/.java는 클래스 맵에서 해당 클래스로 교체, 그 외는 원본 경로 유지
     */
    fun mapSourcesToClasses(sources: List<String>): List<String> = sources.flatMap { src ->
        if (!src.endsWith(".kt") && !src.endsWith(".java")) {
            listOf(src)
        } else {
            val base = File(src).nameWithoutExtension
            statusClassMap[base]?.let { listOf(it) }.orEmpty()
        }
    }.distinct()

    /**
     * 변경된 소스 파일 목록에서 baseName을 추출하고,
     * 워크트리 내 .class 파일 중 최신 수정 파일 맵 생성
     */
    fun buildLatestClassMap(workTree: File, paths: List<String>): Map<String, String> {
        val statusBaseNames = paths.filter { it.endsWith(".kt", true) || it.endsWith(".java", true) }
            .map { File(it).nameWithoutExtension }.toSet()

        // 워크트리 전체 탐색하여 관심 클래스 파일만 필터 후 최신 파일 선택
        return Files.walk(workTree.toPath()).use { stream ->
            stream.filter(Files::isRegularFile)
                .filter { it.toString().endsWith(".class", true) }
                .map { path ->
                    val fileName = path.fileName.toString()
                    val base = fileName.substringBeforeLast('.').substringBefore('$')
                    base to path
                }
                .filter { (base, _) -> base in statusBaseNames }
                .toList()
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, paths) ->
                    paths.maxByOrNull { Files.getLastModifiedTime(it).toMillis() }!!
                        .toAbsolutePath().toString()
                }
        }.also { statusClassMap = it }
    }

    // ───────────────────────────────────────────────────────────
    //  ZIP Packaging Helpers
    // ───────────────────────────────────────────────────────────

    /**
     * ZIP 스트림 생성 및 블록 실행
     */
    fun createZip( block: (ZipOutputStream) -> Unit) {
        val home = System.getProperty("user.home")
        val desktop = File(home, "Desktop").also { if(!it.exists()) it.mkdir() }
        val date : String = SimpleDateFormat("yyyyMMdd").format(Date())

        val output = File(desktop, "$date.zip")

        ZipOutputStream(Files.newOutputStream(output.toPath())).use(block)
    }

    /**
     * 지정된 경로 리스트를 baseDir 기준으로 ZIP에 추가
     */
    fun addZipEntry(zip: ZipOutputStream, baseDir: File, paths: List<String>) {
        val basePath = baseDir.toPath()
        paths.forEach { rel ->
            val file = if (Paths.get(rel).isAbsolute) File(rel) else File(baseDir, rel)
            if (file.exists()) {
                if (file.isDirectory) {
                    Files.walk(file.toPath()).filter(Files::isRegularFile)
                        .forEach { addZipFile(zip, basePath, it.toFile()) }
                } else {
                    addZipFile(zip, basePath, file)
                }
            }
        }
    }

    /**
     * 단일 파일을 ZIP에 추가 (중복 방지)
     */
    private fun addZipFile(zip: ZipOutputStream, basePath: Path, file: File) {
        val entryName = basePath.relativize(file.toPath()).toString().replace(File.separatorChar, '/')
        if (zippedEntries.add(entryName)) {
            zip.putNextEntry(ZipEntry(entryName))
            FileInputStream(file).use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    // ───────────────────────────────────────────────────────────
    //  UI Progress Helper
    // ───────────────────────────────────────────────────────────

    /**
     * Swing 다이얼로그로 인디케이터 표시하며 작업(task) 실행
     */
    fun showProgressAndRun(
        title: String = "Git 작업 중…",
        initialMessage: String = "잠시만 기다려 주세요…",
        task: () -> Unit
    ) {
        val dialog = JDialog(null as JFrame?, title, true).apply {
            layout = BorderLayout(15, 40)
            add(JLabel(initialMessage), BorderLayout.NORTH)
            add(JProgressBar().apply { isIndeterminate = true }, BorderLayout.CENTER)
            pack()
            setLocationRelativeTo(null)
        }

        object : SwingWorker<Unit, Unit>() {
            override fun doInBackground() = task()
            override fun done() = dialog.dispose()
        }.execute()

        dialog.isVisible = true
    }
}