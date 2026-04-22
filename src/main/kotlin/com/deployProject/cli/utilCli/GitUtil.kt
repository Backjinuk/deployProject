package com.deployProject.cli.utilCli

import com.deployProject.deploy.domain.site.FileStatusType
import java.awt.BorderLayout
import java.io.File
import java.io.FileInputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
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
import javax.swing.JProgressBar
import javax.swing.SwingWorker

object GitUtil {
    private val zippedEntries = mutableSetOf<String>()
    private val zippedEntriesPath = mutableSetOf<String>()
    private var statusClassMap: Map<String, List<String>> = mapOf()

    fun FileStatusType.allowsDiff(): Boolean = this == FileStatusType.DIFF || this == FileStatusType.ALL
    fun FileStatusType.allowsStatus(): Boolean = this == FileStatusType.STATUS || this == FileStatusType.ALL

    private val ignoreGlobs = listOf(
        "glob:**/.idea/**",
        "glob:**/.git/**",
        "glob:**/.vscode/**",
        "glob:**/.gradle/**",
        "glob:**/*.iml",
        "glob:**/*.ipr",
        "glob:**/*.iws",
        "glob:**/workspace.xml",
        "glob:**/.DS_Store"
    )
    private val ignoreMatchers: List<PathMatcher> = ignoreGlobs.map {
        FileSystems.getDefault().getPathMatcher(it)
    }

    fun parseDateArg(arg: String?, fmt: DateTimeFormatter): LocalDate {
        if (arg.isNullOrBlank()) return LocalDate.now()
        return try {
            LocalDate.parse(arg.substringBefore('T'), fmt)
        } catch (_: Exception) {
            error("Invalid date format: '$arg'. Expected: $fmt")
        }
    }

    fun parseDateArg(dateStr: String?, dateFmt: SimpleDateFormat): Date {
        return dateStr?.let {
            try {
                dateFmt.parse(it)
            } catch (_: Exception) {
                error("Invalid date format: $it. Expected format: yyyy-MM-dd")
            }
        } ?: Date()
    }

    fun determineOutputZip(gitDir: File): File {
        val date = SimpleDateFormat("yyyyMMdd").format(Date())
        return File(gitDir.parentFile, "$date.zip")
    }

    fun parseDir(repoPath: String, dirType: String): File {
        val f = File(repoPath)
        return if (f.name.equals(".$dirType", ignoreCase = true)) f.parentFile else f
    }

    fun parseStatusType(statusType: String?): FileStatusType {
        // 수정 이유: 프론트와 백엔드 enum 명칭 차이(GIT vs DIFF)를 서버에서 안전하게 흡수한다.
        return when (statusType?.trim()?.uppercase()) {
            "GIT", "DIFF" -> FileStatusType.DIFF
            "STATUS" -> FileStatusType.STATUS
            "ALL", null, "" -> FileStatusType.ALL
            else -> FileStatusType.ALL
        }
    }

    fun mapSourcesToClasses(sources: List<String>): List<String> = sources.flatMap { src ->
        if (!src.endsWith(".kt", true) && !src.endsWith(".java", true)) {
            listOf(src)
        } else {
            val base = File(src).nameWithoutExtension
            statusClassMap[base].orEmpty()
        }
    }.distinct()

    fun buildLatestClassMap(workTree: File, paths: List<String>): Map<String, List<String>> {
        val basePath = workTree.toPath()
        val sourceBases = paths
            .filter { it.endsWith(".kt", true) || it.endsWith(".java", true) }
            .map { File(it).nameWithoutExtension }
            .toSet()

        val grouped = Files.walk(basePath).use { stream ->
            stream.filter(Files::isRegularFile)
                .filter { it.toString().endsWith(".class", true) }
                .filter { path -> !shouldIgnore(basePath, path) }
                .map { classFile ->
                    val rel = basePath.relativize(classFile.normalize())
                    val nameNoExt = rel.fileName.toString().removeSuffix(".class")
                    val baseName = nameNoExt.substringBefore('$')
                    baseName to rel
                }
                .filter { (baseName, _) -> baseName in sourceBases }
                .toList()
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, relPaths) ->
                    relPaths.sortedBy { Files.getLastModifiedTime(basePath.resolve(it)).toMillis() }
                        .map { it.toString().replace(File.separatorChar, '/') }
                        .filter { rel -> !rel.contains("/.") }
                }
                .filterValues { it.isNotEmpty() }
        }

        return grouped.also { statusClassMap = it }
    }

    fun createZip(block: (ZipOutputStream) -> Unit) {
        val home = System.getProperty("user.home")
        val desktop = File(home, "Desktop").also { if (!it.exists()) it.mkdir() }
        val date = SimpleDateFormat("yyyyMMdd").format(Date())
        val output = File(desktop, "$date.zip")

        // 수정 이유: object 전역 상태를 초기화하지 않으면 요청 간 ZIP 엔트리가 누락될 수 있다.
        zippedEntries.clear()

        ZipOutputStream(Files.newOutputStream(output.toPath())).use(block)
    }

    fun addZipEntry(zip: ZipOutputStream, baseDir: File, paths: List<String>) {
        val basePath = baseDir.toPath()
        paths.forEach { rel ->
            val file = if (Paths.get(rel).isAbsolute) File(rel) else File(baseDir, rel)
            if (!file.exists()) return@forEach

            if (file.isDirectory) {
                Files.walk(file.toPath())
                    .filter(Files::isRegularFile)
                    .filter { !shouldIgnore(basePath, it) }
                    .forEach { addZipFile(zip, basePath, it.toFile()) }
            } else {
                addZipFile(zip, basePath, file)
            }
        }
    }

    fun addZipEntryName(baseDir: File, paths: List<String>): Set<String> {
        val basePath = baseDir.toPath()

        // 수정 이유: 이전 호출 결과가 누적되면 deploy script 대상 경로가 오염된다.
        zippedEntriesPath.clear()

        paths.forEach { rel ->
            val file = if (Paths.get(rel).isAbsolute) File(rel) else File(baseDir, rel)
            if (!file.exists()) return@forEach

            if (file.isDirectory) {
                Files.walk(file.toPath())
                    .filter(Files::isRegularFile)
                    .filter { !shouldIgnore(basePath, it) }
                    .forEach { addZipFileName(basePath, it.toFile()) }
            } else {
                addZipFileName(basePath, file)
            }
        }

        return zippedEntriesPath
    }

    private fun addZipFile(zip: ZipOutputStream, basePath: Path, file: File) {
        val path = file.toPath()
        if (shouldIgnore(basePath, path)) return

        val entryName = basePath.relativize(path).toString().replace(File.separatorChar, '/')
        if (zippedEntries.add(entryName)) {
            zip.putNextEntry(ZipEntry(entryName))
            FileInputStream(file).use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun addZipFileName(basePath: Path, file: File) {
        val entryName = basePath.relativize(file.toPath()).toString().replace(File.separatorChar, '/')
        zippedEntriesPath.add(entryName)
    }

    fun showProgressAndRun(
        title: String = "Processing",
        initialMessage: String = "Please wait...",
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

    private fun shouldIgnore(baseDir: Path, filePath: Path): Boolean {
        val rel = try {
            baseDir.relativize(filePath.normalize())
        } catch (_: Exception) {
            filePath.normalize()
        }

        if (ignoreMatchers.any { it.matches(rel) }) return true

        rel.forEach { seg ->
            val part = seg.toString()
            if (part.startsWith(".")) return true
            if (part.equals("build", true)) return true
        }

        val name = rel.fileName?.toString()?.lowercase() ?: ""
        if (name == "workspace.xml") return true
        if (name.endsWith(".iml") || name.endsWith(".ipr") || name.endsWith(".iws")) return true

        return false
    }
}
