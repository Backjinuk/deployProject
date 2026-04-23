package com.deployProject.cli.utilCli

import com.deployProject.deploy.domain.site.FileStatusType
import java.awt.BorderLayout
import java.awt.GraphicsEnvironment
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
    /**
     * Extraction branch point for future extensibility.
     * - JVM_CLASS_ONLY: extract compiled class artifacts from Java/Kotlin projects.
     * - RAW_FILE_COPY: fallback for non-JVM projects.
     */
    enum class ArtifactProfile {
        JVM_CLASS_ONLY,
        RAW_FILE_COPY
    }

    private val zippedEntries = mutableSetOf<String>()
    private val zippedEntriesPath = mutableSetOf<String>()
    private var statusClassMap: Map<String, List<String>> = emptyMap()

    private data class SourceInfo(
        val aliases: Set<String>,
        val expectedClassSuffixes: Set<String>
    )

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
    private val ignoreMatchers: List<PathMatcher> = ignoreGlobs.map { FileSystems.getDefault().getPathMatcher(it) }

    fun parseDateArg(arg: String?, fmt: DateTimeFormatter): LocalDate {
        if (arg.isNullOrBlank()) return LocalDate.now()
        return try {
            LocalDate.parse(arg.substringBefore("T"), fmt)
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

    fun determineDesktopOutputDir(): File {
        val home = System.getProperty("user.home")
        val desktop = File(home, "Desktop").also { if (!it.exists()) it.mkdirs() }
        val date = SimpleDateFormat("yyyyMMdd").format(Date())
        var candidate = File(desktop, date)
        var seq = 1
        while (candidate.exists()) {
            candidate = File(desktop, "${date}_$seq")
            seq += 1
        }
        candidate.mkdirs()
        return candidate
    }

    fun parseDir(repoPath: String, dirType: String): File {
        val file = File(repoPath)
        return if (file.name.equals(".$dirType", ignoreCase = true)) file.parentFile else file
    }

    fun parseStatusType(statusType: String?): FileStatusType {
        return when (statusType?.trim()?.uppercase()) {
            "GIT", "DIFF" -> FileStatusType.DIFF
            "STATUS" -> FileStatusType.STATUS
            "ALL", null, "" -> FileStatusType.ALL
            else -> FileStatusType.ALL
        }
    }

    fun resolveArtifactProfile(workTree: File): ArtifactProfile {
        val jvmProjectMarkers = listOf(
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "pom.xml"
        )

        // Modified because we need a branch point for future non-JVM extraction logic.
        return if (jvmProjectMarkers.any { File(workTree, it).exists() }) {
            ArtifactProfile.JVM_CLASS_ONLY
        } else {
            ArtifactProfile.RAW_FILE_COPY
        }
    }

    fun mapPathsForExtraction(paths: List<String>, profile: ArtifactProfile): List<String> {
        return when (profile) {
            ArtifactProfile.JVM_CLASS_ONLY -> mapJvmSourcesToClassesOnly(paths)
            ArtifactProfile.RAW_FILE_COPY -> paths.distinct()
        }
    }

    fun mapSourcesToClasses(sources: List<String>): List<String> =
        mapPathsForExtraction(sources, ArtifactProfile.JVM_CLASS_ONLY)

    fun profileUsesBuildArtifacts(profile: ArtifactProfile): Boolean =
        profile == ArtifactProfile.JVM_CLASS_ONLY

    private fun mapJvmSourcesToClassesOnly(paths: List<String>): List<String> {
        return paths.flatMap { path ->
            if (!isJvmSource(path)) {
                listOf(path)
            } else {
                // Modified because output should contain bytecode artifacts, not source files.
                sourceLookupAliases(path)
                    .flatMap { alias -> statusClassMap[alias].orEmpty() }
                    .distinct()
            }
        }.distinct()
    }

    fun buildLatestClassMap(workTree: File, paths: List<String>): Map<String, List<String>> {
        val basePath = workTree.toPath()
        val sourceInfos = paths
            .filter(::isJvmSource)
            .map { sourcePath ->
                val rawNormalized = normalizePathText(sourcePath)
                val relativeNormalized = normalizePathAgainstBase(sourcePath, basePath)
                val baseName = File(sourcePath).nameWithoutExtension

                // Modified because basename-only matching loses classes when same names exist across packages.
                SourceInfo(
                    aliases = linkedSetOf(rawNormalized, relativeNormalized, baseName),
                    expectedClassSuffixes = linkedSetOf<String>().apply {
                        addAll(deriveClassSuffixes(rawNormalized))
                        addAll(deriveClassSuffixes(relativeNormalized))
                    }
                )
            }

        if (sourceInfos.isEmpty()) {
            statusClassMap = emptyMap()
            return emptyMap()
        }

        val candidateRoots = listOf(
            basePath.resolve("build").resolve("classes"),
            basePath.resolve("out").resolve("production"),
            basePath.resolve("target").resolve("classes")
        ).filter { Files.isDirectory(it) }.ifEmpty { listOf(basePath) }

        val mappedClassPaths = linkedMapOf<String, MutableSet<String>>()
        candidateRoots.forEach { root ->
            Files.walk(root).use { stream ->
                stream.filter(Files::isRegularFile)
                    .filter { it.toString().endsWith(".class", true) }
                    .filter { !shouldIgnore(basePath, it, allowBuildArtifacts = true) }
                    .forEach { classFile ->
                        val rel = basePath.relativize(classFile.normalize()).toString().replace(File.separatorChar, '/')
                        val classStem = rel.removeSuffix(".class").substringBefore("$")

                        sourceInfos.forEach { sourceInfo ->
                            if (matchesSourceClass(sourceInfo, classStem)) {
                                sourceInfo.aliases.forEach { alias ->
                                    mappedClassPaths.getOrPut(alias) { linkedSetOf() }.add(rel)
                                }
                            }
                        }
                    }
            }
        }

        val grouped = mappedClassPaths
            .mapValues { (_, relPaths) ->
                relPaths
                    .sortedBy { rel ->
                        runCatching { Files.getLastModifiedTime(basePath.resolve(rel.replace('/', File.separatorChar))).toMillis() }
                            .getOrDefault(0L)
                    }
                    .filter { rel -> !rel.contains("/.") }
                    .distinct()
            }
            .filterValues { it.isNotEmpty() }

        return grouped.also { statusClassMap = it }
    }

    fun createZip(outputFile: File? = null, block: (ZipOutputStream) -> Unit): File {
        val output = outputFile ?: run {
            val home = System.getProperty("user.home")
            val desktop = File(home, "Desktop").also { if (!it.exists()) it.mkdir() }
            val date = SimpleDateFormat("yyyyMMdd").format(Date())
            File(desktop, "$date.zip")
        }
        output.parentFile?.mkdirs()

        zippedEntries.clear()

        ZipOutputStream(Files.newOutputStream(output.toPath())).use(block)
        return output
    }

    fun addZipEntry(
        zip: ZipOutputStream,
        baseDir: File,
        paths: List<String>,
        allowBuildArtifacts: Boolean = false
    ) {
        val basePath = baseDir.toPath()
        paths.forEach { rel ->
            val file = if (Paths.get(rel).isAbsolute) File(rel) else File(baseDir, rel)
            if (!file.exists()) return@forEach

            if (file.isDirectory) {
                Files.walk(file.toPath())
                    .filter(Files::isRegularFile)
                    .filter { !shouldIgnore(basePath, it, allowBuildArtifacts) }
                    .forEach { addZipFile(zip, basePath, it.toFile(), allowBuildArtifacts) }
            } else {
                addZipFile(zip, basePath, file, allowBuildArtifacts)
            }
        }
    }

    fun addZipEntryName(baseDir: File, paths: List<String>, allowBuildArtifacts: Boolean = false): Set<String> {
        val basePath = baseDir.toPath()
        zippedEntriesPath.clear()

        paths.forEach { rel ->
            val file = if (Paths.get(rel).isAbsolute) File(rel) else File(baseDir, rel)
            if (!file.exists()) return@forEach

            if (file.isDirectory) {
                Files.walk(file.toPath())
                    .filter(Files::isRegularFile)
                    .filter { !shouldIgnore(basePath, it, allowBuildArtifacts) }
                    .forEach { addZipFileName(basePath, it.toFile(), allowBuildArtifacts) }
            } else {
                addZipFileName(basePath, file, allowBuildArtifacts)
            }
        }

        return zippedEntriesPath
    }

    fun addDirectoryEntry(
        targetDir: File,
        baseDir: File,
        paths: List<String>,
        allowBuildArtifacts: Boolean = false
    ) {
        val basePath = baseDir.toPath()
        paths.forEach { rel ->
            val file = if (Paths.get(rel).isAbsolute) File(rel) else File(baseDir, rel)
            if (!file.exists()) return@forEach

            if (file.isDirectory) {
                Files.walk(file.toPath())
                    .filter(Files::isRegularFile)
                    .filter { !shouldIgnore(basePath, it, allowBuildArtifacts) }
                    .forEach { copyToOutputDir(targetDir, basePath, it.toFile(), allowBuildArtifacts) }
            } else {
                copyToOutputDir(targetDir, basePath, file, allowBuildArtifacts)
            }
        }
    }

    fun writeTextOutputFile(targetDir: File, relativePath: String, content: String) {
        val outFile = File(targetDir, relativePath.replace("/", File.separator))
        outFile.parentFile?.mkdirs()
        outFile.writeText(content, Charsets.UTF_8)
    }

    fun writeBinaryOutputFile(targetDir: File, relativePath: String, content: ByteArray) {
        val outFile = File(targetDir, relativePath.replace("/", File.separator))
        outFile.parentFile?.mkdirs()
        outFile.writeBytes(content)
    }

    private fun addZipFile(zip: ZipOutputStream, basePath: Path, file: File, allowBuildArtifacts: Boolean) {
        val path = file.toPath()
        if (shouldIgnore(basePath, path, allowBuildArtifacts)) return

        val entryName = basePath.relativize(path).toString().replace(File.separatorChar, '/')
        if (zippedEntries.add(entryName)) {
            zip.putNextEntry(ZipEntry(entryName))
            FileInputStream(file).use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun addZipFileName(basePath: Path, file: File, allowBuildArtifacts: Boolean) {
        if (shouldIgnore(basePath, file.toPath(), allowBuildArtifacts)) return
        val entryName = basePath.relativize(file.toPath()).toString().replace(File.separatorChar, '/')
        zippedEntriesPath.add(entryName)
    }

    private fun copyToOutputDir(targetDir: File, basePath: Path, file: File, allowBuildArtifacts: Boolean) {
        val path = file.toPath()
        if (shouldIgnore(basePath, path, allowBuildArtifacts)) return

        val relative = basePath.relativize(path).toString().replace(File.separatorChar, '/')
        val outFile = File(targetDir, relative.replace("/", File.separator))
        outFile.parentFile?.mkdirs()
        file.copyTo(outFile, overwrite = true)
    }

    fun showProgressAndRun(
        title: String = "Processing",
        initialMessage: String = "Please wait...",
        task: () -> Unit
    ) {
        if (GraphicsEnvironment.isHeadless()) {
            task()
            return
        }

        var failure: Throwable? = null
        val dialog = JDialog(null as JFrame?, title, true).apply {
            layout = BorderLayout(15, 40)
            add(JLabel(initialMessage), BorderLayout.NORTH)
            add(JProgressBar().apply { isIndeterminate = true }, BorderLayout.CENTER)
            pack()
            setLocationRelativeTo(null)
        }

        object : SwingWorker<Unit, Unit>() {
            override fun doInBackground() {
                task()
            }

            override fun done() {
                try {
                    get()
                } catch (e: Exception) {
                    failure = e.cause ?: e
                } finally {
                    dialog.dispose()
                }
            }
        }.execute()

        dialog.isVisible = true
        failure?.let { throw RuntimeException("Extraction task failed", it) }
    }

    private fun shouldIgnore(baseDir: Path, filePath: Path, allowBuildArtifacts: Boolean = false): Boolean {
        val rel = try {
            baseDir.relativize(filePath.normalize())
        } catch (_: Exception) {
            filePath.normalize()
        }

        if (ignoreMatchers.any { it.matches(rel) }) return true

        rel.forEach { seg ->
            val part = seg.toString()
            if (part.startsWith(".")) return true
            if (!allowBuildArtifacts && part.equals("build", true)) return true
        }

        val name = rel.fileName?.toString()?.lowercase() ?: ""
        if (name == "workspace.xml") return true
        if (name.endsWith(".iml") || name.endsWith(".ipr") || name.endsWith(".iws")) return true

        return false
    }

    private fun isJvmSource(path: String): Boolean =
        path.endsWith(".kt", true) || path.endsWith(".java", true)

    private fun sourceLookupAliases(path: String): List<String> {
        val normalized = normalizePathText(path)
        val baseName = File(path).nameWithoutExtension
        return listOf(normalized, baseName).distinct()
    }

    private fun normalizePathAgainstBase(path: String, basePath: Path): String {
        val raw = normalizePathText(path)
        val abs = runCatching { Paths.get(path) }.getOrNull()
        if (abs != null && abs.isAbsolute) {
            val normalizedAbs = abs.normalize()
            val rel = runCatching { basePath.relativize(normalizedAbs) }.getOrNull()
            if (rel != null) return rel.toString().replace(File.separatorChar, '/')
        }
        return raw
    }

    private fun normalizePathText(path: String): String =
        path.replace('\\', '/').removePrefix("/").trim()

    private fun deriveClassSuffixes(sourcePath: String): Set<String> {
        val normalized = normalizePathText(sourcePath)
        val markers = listOf("src/main/java/", "src/main/kotlin/")
        val suffixes = mutableSetOf<String>()

        markers.forEach { marker ->
            val idx = normalized.indexOf(marker)
            if (idx >= 0) {
                val classPath = normalized.substring(idx + marker.length)
                    .removeSuffix(".java")
                    .removeSuffix(".kt")
                if (classPath.isNotBlank()) suffixes.add(classPath)
            }
        }

        val baseName = File(normalized).nameWithoutExtension
        if (baseName.isNotBlank()) suffixes.add(baseName)

        return suffixes
    }

    private fun matchesSourceClass(sourceInfo: SourceInfo, classStem: String): Boolean {
        if (sourceInfo.expectedClassSuffixes.isEmpty()) return false
        return sourceInfo.expectedClassSuffixes.any { suffix ->
            classStem == suffix || classStem.endsWith("/$suffix")
        }
    }
}
