package com.deployProject.cli.utilCli

import com.deployProject.deploy.domain.site.FileStatusType
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.BufferedReader
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Comparator
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.SwingWorker
import javax.swing.WindowConstants
import javax.swing.border.EmptyBorder

object GitUtil {
    private val log = LoggerFactory.getLogger(GitUtil::class.java)

    private data class BuildCommandResolution(
        val command: List<String>?,
        val failureReason: String? = null
    )

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

    fun normalizeExtractionPaths(baseDir: File, paths: List<String>): List<String> {
        val basePath = baseDir.toPath().toAbsolutePath().normalize()
        return paths.map { path ->
            val candidate = runCatching { Paths.get(path) }.getOrNull()
            if (candidate != null && candidate.isAbsolute) {
                val normalizedCandidate = candidate.toAbsolutePath().normalize()
                runCatching { basePath.relativize(normalizedCandidate) }.getOrNull()
                    ?.toString()
                    ?.replace(File.separatorChar, '/')
                    ?: normalizePathText(path)
            } else {
                normalizePathText(path)
            }
        }.distinct()
    }

    fun collectModifiedFilesByDate(workTree: File, since: LocalDate, until: LocalDate): List<String> {
        val basePath = workTree.toPath().toAbsolutePath().normalize()
        return Files.walk(basePath).use { stream ->
            stream.filter(Files::isRegularFile)
                .filter { !shouldIgnore(basePath, it, allowBuildArtifacts = false) }
                .filter { path ->
                    val modifiedDate = Files.getLastModifiedTime(path).toInstant()
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                    !modifiedDate.isBefore(since) && !modifiedDate.isAfter(until)
                }
                .map { basePath.relativize(it.normalize()).toString().replace(File.separatorChar, '/') }
                .filter { it.isNotBlank() }
                .sorted()
                .toList()
        }
    }

    fun compileJvmProject(workTree: File, javaHomeOverride: String? = null) {
        val javaSpec = ProjectJavaInspector.inspect(workTree)
            ?: error("Unable to detect project Java version from ${workTree.absolutePath}")
        val targetJavaVersion = javaSpec.targetJavaVersion ?: javaSpec.buildJavaVersion
            ?: error("Unable to determine target Java version from ${javaSpec.detectedFrom}")
        val buildJavaVersion = javaSpec.buildJavaVersion ?: targetJavaVersion
        val javaHome = resolveConfiguredJavaHome(javaHomeOverride)
            ?: resolveLocalJavaHome(buildJavaVersion)
            ?: error(
                "JDK path is not configured and JDK $buildJavaVersion was not found on this machine. " +
                    "Detected from ${javaSpec.detectedFrom} for ${workTree.absolutePath}"
            )

        // When the user explicitly provides a JDK path, prefer direct javac compilation.
        // This keeps extraction working even when Maven/Gradle are not installed locally.
        if (!javaHomeOverride.isNullOrBlank()) {
            compileJvmProjectWithDirectJdk(workTree, javaHome, targetJavaVersion, javaSpec.detectedFrom)
            verifyCompiledClasses(workTree, targetJavaVersion)
            return
        }

        val commandResolution = resolveJvmBuildCommand(workTree)
        val command = commandResolution.command
            ?: error(commandResolution.failureReason ?: "No supported JVM build tool found in ${workTree.absolutePath}")

        log.info(
            "Compiling JVM artifacts in {} with command={}, detectedFrom={}, targetJavaVersion={}, buildJavaVersion={}, javaHome={}",
            workTree.absolutePath,
            command.joinToString(" "),
            javaSpec.detectedFrom,
            targetJavaVersion,
            buildJavaVersion,
            javaHome
        )
        val process = ProcessBuilder(command)
            .directory(workTree)
            .redirectErrorStream(true)
        val env = process.environment()
        env["JAVA_HOME"] = javaHome.toString()
        env["PATH"] = javaHome.resolve("bin").toString() + File.pathSeparator + env["PATH"].orEmpty()
        val started = process.start()

        val output = StringBuilder()
        BufferedReader(InputStreamReader(started.inputStream)).useLines { lines ->
            lines.forEach { line ->
                output.appendLine(line)
                log.info("[build] {}", line)
            }
        }

        val exitCode = started.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException(
                "Failed to compile JVM artifacts for ${workTree.absolutePath} (exit=$exitCode)\n$output"
            )
        }

        verifyCompiledClasses(workTree, targetJavaVersion)
    }

    private fun compileJvmProjectWithDirectJdk(
        workTree: File,
        javaHome: Path,
        targetJavaVersion: Int,
        detectedFrom: String
    ) {
        val actualJavaVersion = readJavaMajorVersion(javaHome)
            ?: error("Unable to determine Java version from configured JDK: $javaHome")
        require(actualJavaVersion >= targetJavaVersion) {
            "Configured JDK $actualJavaVersion is lower than target Java $targetJavaVersion " +
                "detected from $detectedFrom for ${workTree.absolutePath}"
        }

        require(!containsKotlinProductionSources(workTree.toPath())) {
            "Configured JDK path can directly compile Java sources only. " +
                "Kotlin sources were found in ${workTree.absolutePath}, so Maven/Gradle is still required."
        }

        val javaSources = collectDirectJavaSources(workTree.toPath())
        require(javaSources.isNotEmpty()) {
            "No production Java source files were found in ${workTree.absolutePath}"
        }

        val outputDir = determineDirectCompileOutputDir(workTree.toPath())
        resetDirectory(outputDir)
        val classpathEntries = collectLocalCompileClasspath(workTree.toPath(), outputDir)
        val javac = javaHome.resolve("bin").resolve(javacExecutableName())
        require(Files.isRegularFile(javac)) { "Configured JDK does not contain javac: $javac" }

        val sourcesArgFile = createJavacSourcesArgFile(workTree.toPath(), javaSources)
        try {
            val command = mutableListOf(
                javac.toString(),
                // Preserve local variable metadata so decompiled classes stay readable
                // and remain closer to the artifacts produced by normal Maven/Gradle builds.
                "-g",
                "-encoding",
                "UTF-8",
                "-d",
                outputDir.toString()
            )

            if (actualJavaVersion >= 9) {
                command += listOf("--release", targetJavaVersion.toString())
            } else {
                command += listOf("-source", targetJavaVersion.toString(), "-target", targetJavaVersion.toString())
            }

            if (classpathEntries.isNotEmpty()) {
                command += listOf("-classpath", classpathEntries.joinToString(File.pathSeparator))
            }

            command += "@${sourcesArgFile.toAbsolutePath()}"

            log.info(
                "Compiling JVM artifacts in {} with direct javac, detectedFrom={}, targetJavaVersion={}, javaHome={}, sources={}, classpathEntries={}",
                workTree.absolutePath,
                detectedFrom,
                targetJavaVersion,
                javaHome,
                javaSources.size,
                classpathEntries.size
            )

            val process = ProcessBuilder(command)
                .directory(workTree)
                .redirectErrorStream(true)
                .start()

            val output = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                lines.forEach { line ->
                    output.appendLine(line)
                    log.info("[javac] {}", line)
                }
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw IllegalStateException(
                    "Failed to compile JVM artifacts with configured JDK for ${workTree.absolutePath} (exit=$exitCode)\n" +
                        output.appendLine()
                            .append("Direct JDK compilation works for Java sources that can be resolved from the local project tree. ")
                            .append("Projects that depend on external libraries, Kotlin, annotation processors, or generated sources still need Maven/Gradle or a checked-in local lib directory.")
                )
            }
        } finally {
            Files.deleteIfExists(sourcesArgFile)
        }
    }

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

        val candidateRoots = candidateClassRoots(basePath).ifEmpty { listOf(basePath) }

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
        title: String = "배포 파일 생성",
        initialMessage: String = "작업을 진행하고 있습니다.",
        detailMessage: String = "변경 파일 정리, 산출물 생성, 패치 스크립트 생성을 진행합니다.",
        task: () -> Unit
    ) {
        if (GraphicsEnvironment.isHeadless()) {
            task()
            return
        }

        var failure: Throwable? = null
        val startedAt = System.currentTimeMillis()
        val progressBar = JProgressBar().apply {
            isIndeterminate = true
            isStringPainted = true
            string = "실행 중"
        }
        val elapsedLabel = JLabel("경과 시간: 0초").apply {
            horizontalAlignment = SwingConstants.LEFT
        }
        val footerLabel = JLabel("작업이 끝나면 결과 폴더를 자동으로 엽니다.").apply {
            horizontalAlignment = SwingConstants.LEFT
        }
        val timer = Timer(1000) {
            val elapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
            elapsedLabel.text = "경과 시간: ${elapsedSeconds}초"
        }

        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = EmptyBorder(16, 16, 16, 16)
            add(
                JLabel(
                    """
                    <html>
                      <div style='font-size:14px; font-weight:bold;'>$initialMessage</div>
                    </html>
                    """.trimIndent()
                )
            )
            add(Box.createVerticalStrut(8))
            add(
                JLabel(
                    """
                    <html>
                      <div style='width:360px;'>$detailMessage</div>
                    </html>
                    """.trimIndent()
                )
            )
            add(Box.createVerticalStrut(14))
            add(progressBar)
            add(Box.createVerticalStrut(8))
            add(elapsedLabel)
            add(Box.createVerticalStrut(4))
            add(footerLabel)
        }

        val dialog = JDialog(null as JFrame?, title, true).apply {
            defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            isResizable = false
            layout = BorderLayout()
            add(contentPanel, BorderLayout.CENTER)
            pack()
            setSize(maxOf(width, 430), height)
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
                    timer.stop()
                    dialog.dispose()
                }
            }
        }.execute()

        timer.start()
        dialog.isVisible = true
        failure?.let { throw RuntimeException("Extraction task failed", it) }
    }

    fun notifyCompletionAndOpenDirectory(
        outputDir: File,
        completionMessage: String = "배포 파일 생성이 완료되었습니다."
    ) {
        val targetDir = outputDir.canonicalFile
        val opened = openDirectoryInFileManager(targetDir)
        val message = if (opened) {
            "$completionMessage\n결과 폴더를 열었습니다."
        } else {
            "$completionMessage\n결과 폴더를 자동으로 열지 못했습니다.\n경로: ${targetDir.absolutePath}"
        }

        if (GraphicsEnvironment.isHeadless()) {
            println(message)
            return
        }

        JOptionPane.showMessageDialog(
            null,
            message,
            "완료",
            JOptionPane.INFORMATION_MESSAGE
        )
    }

    private fun openDirectoryInFileManager(directory: File): Boolean {
        if (!directory.exists()) return false

        runCatching {
            if (Desktop.isDesktopSupported()) {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.OPEN)) {
                    desktop.open(directory)
                    return true
                }
            }
        }

        val osName = System.getProperty("os.name").lowercase()
        val command = when {
            osName.contains("win") -> listOf("explorer", directory.absolutePath)
            osName.contains("mac") -> listOf("open", directory.absolutePath)
            else -> listOf("xdg-open", directory.absolutePath)
        }

        return runCatching {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            true
        }.getOrDefault(false)
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

    private fun resolveJvmBuildCommand(workTree: File): BuildCommandResolution {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val hasGradleMarker = listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
            .any { File(workTree, it).exists() }
        val hasMavenMarker = File(workTree, "pom.xml").exists()

        val gradleWrapper = if (isWindows) File(workTree, "gradlew.bat") else File(workTree, "gradlew")
        if (gradleWrapper.isFile) {
            return BuildCommandResolution(
                command = buildScriptCommand(
                    script = gradleWrapper,
                    args = listOf("--no-daemon", "classes", "-x", "test"),
                    isWindows = isWindows
                )
            )
        }

        val mavenWrapper = when {
            isWindows && File(workTree, "mvnw.cmd").isFile -> File(workTree, "mvnw.cmd")
            isWindows && File(workTree, "mvnw.bat").isFile -> File(workTree, "mvnw.bat")
            !isWindows && File(workTree, "mvnw").isFile -> File(workTree, "mvnw")
            else -> null
        }
        if (mavenWrapper != null) {
            return BuildCommandResolution(
                command = buildScriptCommand(
                    script = mavenWrapper,
                    args = listOf("-q", "-DskipTests", "compile"),
                    isWindows = isWindows
                )
            )
        }

        if (hasGradleMarker) {
            resolveGradleExecutable(isWindows)?.let { executable ->
                return BuildCommandResolution(
                    command = buildScriptCommand(
                        script = executable,
                        args = listOf("--no-daemon", "classes", "-x", "test"),
                        isWindows = isWindows
                    )
                )
            }
            return BuildCommandResolution(
                command = null,
                failureReason = "Gradle project detected in ${workTree.absolutePath}, but no Gradle executable was found. " +
                    "Add Gradle Wrapper or install Gradle."
            )
        }

        if (hasMavenMarker) {
            resolveMavenExecutable(isWindows)?.let { executable ->
                return BuildCommandResolution(
                    command = buildScriptCommand(
                        script = executable,
                        args = listOf("-q", "-DskipTests", "compile"),
                        isWindows = isWindows
                    )
                )
            }
            return BuildCommandResolution(
                command = null,
                failureReason = "Maven project detected in ${workTree.absolutePath}, but no Maven executable was found. " +
                    "Add Maven Wrapper or install Maven."
            )
        }

        return BuildCommandResolution(
            command = null,
            failureReason = "No supported JVM build tool markers found in ${workTree.absolutePath}"
        )
    }

    private fun buildScriptCommand(script: File, args: List<String>, isWindows: Boolean): List<String> {
        val path = script.absolutePath
        return if (isWindows) {
            listOf("cmd", "/c", path) + args
        } else {
            listOf("sh", path) + args
        }
    }

    private fun containsKotlinProductionSources(basePath: Path): Boolean {
        return Files.walk(basePath).use { stream ->
            stream.filter(Files::isRegularFile)
                .filter { !shouldIgnore(basePath, it, allowBuildArtifacts = true) }
                .map { basePath.relativize(it.normalize()).toString().replace(File.separatorChar, '/') }
                .anyMatch { relative ->
                    relative.endsWith(".kt", ignoreCase = true) && !isExcludedDirectCompileSource(relative)
                }
        }
    }

    private fun collectDirectJavaSources(basePath: Path): List<Path> {
        val preferredRoots = listOf(
            basePath.resolve("src").resolve("main").resolve("java"),
            basePath.resolve("src").resolve("java"),
            basePath.resolve("main").resolve("java")
        ).filter { Files.isDirectory(it) }

        val candidateRoots = if (preferredRoots.isNotEmpty()) preferredRoots else listOf(basePath)

        return candidateRoots.flatMap { root ->
            Files.walk(root).use { stream ->
                stream.filter(Files::isRegularFile)
                    .filter { it.toString().endsWith(".java", ignoreCase = true) }
                    .filter { !shouldIgnore(basePath, it, allowBuildArtifacts = true) }
                    .filter {
                        val relative = basePath.relativize(it.normalize()).toString().replace(File.separatorChar, '/')
                        !isExcludedDirectCompileSource(relative)
                    }
                    .toList()
            }
        }.distinct()
    }

    private fun isExcludedDirectCompileSource(relativePath: String): Boolean {
        val normalized = relativePath.replace('\\', '/').trim('/').lowercase()
        val segments = normalized.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return false
        if (segments.first() == "test") return true

        return segments.windowed(2).any { pair ->
            pair[0] == "src" && pair[1] == "test"
        }
    }

    private fun determineDirectCompileOutputDir(basePath: Path): Path {
        return when {
            Files.isRegularFile(basePath.resolve("pom.xml")) -> basePath.resolve("target").resolve("classes")
            listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
                .any { Files.isRegularFile(basePath.resolve(it)) } ->
                basePath.resolve("build").resolve("classes").resolve("java").resolve("main")
            else -> basePath.resolve("build").resolve("classes")
        }
    }

    private fun collectLocalCompileClasspath(basePath: Path, outputDir: Path): List<Path> {
        val jarRoots = listOf(
            basePath.resolve("src").resolve("main").resolve("webapp").resolve("WEB-INF").resolve("lib"),
            basePath.resolve("WebContent").resolve("WEB-INF").resolve("lib"),
            basePath.resolve("webapp").resolve("WEB-INF").resolve("lib"),
            basePath.resolve("WEB-INF").resolve("lib"),
            basePath.resolve("lib"),
            basePath.resolve("libs"),
            basePath.resolve("target").resolve("dependency")
        )

        val jars = jarRoots
            .filter { Files.isDirectory(it) }
            .flatMap { root ->
                Files.walk(root).use { stream ->
                    stream.filter(Files::isRegularFile)
                        .filter { it.toString().endsWith(".jar", ignoreCase = true) }
                        .toList()
                }
            }
            .distinct()

        return buildList {
            add(outputDir)
            addAll(jars)
        }.distinct()
    }

    private fun createJavacSourcesArgFile(basePath: Path, javaSources: List<Path>): Path {
        val argFile = Files.createTempFile("deploy-javac-sources-", ".args")
        val content = javaSources.joinToString(System.lineSeparator()) { path ->
            val relative = basePath.relativize(path.toAbsolutePath().normalize())
                .toString()
                .replace(File.separatorChar, '/')
            "\"$relative\""
        }
        Files.writeString(
            argFile,
            content,
            Charsets.UTF_8,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING
        )
        return argFile
    }

    private fun resetDirectory(path: Path) {
        if (Files.isDirectory(path)) {
            Files.walk(path).use { stream ->
                stream.sorted(Comparator.reverseOrder())
                    .forEach { current ->
                        if (current != path) {
                            Files.deleteIfExists(current)
                        }
                    }
            }
        }
        Files.createDirectories(path)
    }

    private fun candidateClassRoots(basePath: Path): List<Path> {
        return listOf(
            basePath.resolve("build").resolve("classes"),
            basePath.resolve("out").resolve("production"),
            basePath.resolve("target").resolve("classes")
        ).filter { Files.isDirectory(it) }
    }

    private fun verifyCompiledClasses(workTree: File, targetJavaVersion: Int) {
        val basePath = workTree.toPath()
        val classRoots = candidateClassRoots(basePath)
        if (classRoots.isEmpty()) {
            log.warn("No class output directories found after build: {}", workTree.absolutePath)
            return
        }

        val expectedMajor = targetJavaVersion + 44
        val violations = mutableListOf<String>()
        var checkedCount = 0

        classRoots.forEach { root ->
            Files.walk(root).use { stream ->
                stream.filter(Files::isRegularFile)
                    .filter { it.toString().endsWith(".class", ignoreCase = true) }
                    .forEach { classFile ->
                        checkedCount += 1
                        val majorVersion = readClassMajorVersion(classFile)
                        if (majorVersion != null && majorVersion > expectedMajor && violations.size < 10) {
                            val actualJavaVersion = majorVersion - 44
                            val relative = runCatching { basePath.relativize(classFile).toString() }
                                .getOrDefault(classFile.toString())
                                .replace(File.separatorChar, '/')
                            violations.add("$relative -> Java $actualJavaVersion")
                        }
                    }
            }
        }

        if (checkedCount == 0) {
            log.warn("No compiled class files found after build: {}", workTree.absolutePath)
            return
        }

        if (violations.isNotEmpty()) {
            throw IllegalStateException(
                "Compiled classes exceed target Java $targetJavaVersion: ${violations.joinToString(", ")}"
            )
        }
    }

    private fun readClassMajorVersion(classFile: Path): Int? {
        return runCatching {
            Files.newInputStream(classFile).use { input ->
                val header = ByteArray(8)
                val read = input.read(header)
                if (read < 8) return@use null
                ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
            }
        }.getOrNull()
    }

    private fun resolveLocalJavaHome(requiredVersion: Int): Path? {
        val candidates = linkedSetOf<Path>()

        sequenceOf(
            "JAVA_HOME_$requiredVersion",
            "JAVA${requiredVersion}_HOME",
            "JDK_HOME_$requiredVersion",
            "JDK${requiredVersion}_HOME",
            "JDK_${requiredVersion}_HOME"
        ).mapNotNull { System.getenv(it) }
            .map { Paths.get(it) }
            .forEach { candidates.add(it) }

        System.getenv("JAVA_HOME")?.let { candidates.add(Paths.get(it)) }
        collectCommonJavaHomes().forEach { candidates.add(it) }

        return candidates
            .mapNotNull(::normalizeJavaHome)
            .distinct()
            .mapNotNull { javaHome ->
                readJavaMajorVersion(javaHome)?.let { majorVersion -> majorVersion to javaHome }
            }
            .filter { (majorVersion, _) -> majorVersion == requiredVersion }
            .map { it.second }
            .firstOrNull()
    }

    private fun collectCommonJavaHomes(): List<Path> {
        val osName = System.getProperty("os.name").lowercase()
        val candidateBases = when {
            osName.contains("win") -> listOf(
                Paths.get("C:/Program Files/Java"),
                Paths.get("C:/Program Files/Eclipse Adoptium"),
                Paths.get("C:/Program Files/AdoptOpenJDK"),
                Paths.get("C:/Program Files/Amazon Corretto"),
                Paths.get("C:/Program Files/Zulu"),
                Paths.get("C:/Program Files/Microsoft"),
                Paths.get("C:/Program Files (x86)/Java")
            )
            osName.contains("mac") -> listOf(
                Paths.get("/Library/Java/JavaVirtualMachines")
            )
            else -> listOf(
                Paths.get("/usr/lib/jvm"),
                Paths.get("/usr/java")
            )
        }

        return candidateBases
            .filter { Files.isDirectory(it) }
            .flatMap { base ->
                runCatching {
                    Files.list(base).use { stream ->
                        stream.toList()
                    }
                }.getOrDefault(emptyList())
            }
    }

    private fun normalizeJavaHome(candidate: Path): Path? {
        val normalized = candidate.toAbsolutePath().normalize()
        val nestedMacHome = normalized.resolve("Contents").resolve("Home")
        return when {
            Files.isRegularFile(normalized.resolve("bin").resolve(javaExecutableName())) -> normalized
            Files.isRegularFile(nestedMacHome.resolve("bin").resolve(javaExecutableName())) -> nestedMacHome
            else -> null
        }
    }

    private fun resolveConfiguredJavaHome(javaHomeOverride: String?): Path? {
        val configured = javaHomeOverride?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = normalizeJavaHome(Paths.get(configured))
        require(normalized != null) { "Configured JDK path is invalid: $configured" }
        return normalized
    }

    private fun readJavaMajorVersion(javaHome: Path): Int? {
        val releaseFile = javaHome.resolve("release")
        if (Files.isRegularFile(releaseFile)) {
            val versionLine = runCatching {
                Files.readAllLines(releaseFile).firstOrNull { it.startsWith("JAVA_VERSION=") }
            }.getOrNull()
            ProjectJavaInspector.inspectVersionToken(versionLine?.substringAfter("=")?.trim('"'))?.let { return it }
        }

        return runCatching {
            val process = ProcessBuilder(javaHome.resolve("bin").resolve(javaExecutableName()).toString(), "-version")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            ProjectJavaInspector.inspectVersionToken(output)
        }.getOrNull()
    }

    private fun javaExecutableName(): String =
        if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"

    private fun javacExecutableName(): String =
        if (System.getProperty("os.name").lowercase().contains("win")) "javac.exe" else "javac"

    private fun resolveGradleExecutable(isWindows: Boolean): File? {
        val executableNames = if (isWindows) listOf("gradle.bat", "gradle.cmd") else listOf("gradle")
        val candidates = linkedSetOf<File>()

        sequenceOf("GRADLE_HOME", "GRADLE_USER_HOME")
            .mapNotNull { System.getenv(it) }
            .flatMap { home -> executableNames.asSequence().map { File(home, "bin/$it") } }
            .forEach { candidates.add(it) }

        collectCommonGradleHomes()
            .flatMap { home -> executableNames.map { File(home, "bin/$it") } }
            .forEach { candidates.add(it) }

        resolveExecutableFromPath(executableNames).forEach { candidates.add(it) }

        return candidates.firstOrNull { it.isFile }
    }

    private fun resolveMavenExecutable(isWindows: Boolean): File? {
        val executableNames = if (isWindows) listOf("mvn.cmd", "mvn.bat") else listOf("mvn")
        val candidates = linkedSetOf<File>()

        sequenceOf("MAVEN_HOME", "M2_HOME")
            .mapNotNull { System.getenv(it) }
            .flatMap { home -> executableNames.asSequence().map { File(home, "bin/$it") } }
            .forEach { candidates.add(it) }

        collectCommonMavenHomes()
            .flatMap { home -> executableNames.map { File(home, "bin/$it") } }
            .forEach { candidates.add(it) }

        resolveExecutableFromPath(executableNames).forEach { candidates.add(it) }

        return candidates.firstOrNull { it.isFile }
    }

    private fun resolveExecutableFromPath(names: List<String>): List<File> {
        val pathEntries = (System.getenv("PATH") ?: "")
            .split(File.pathSeparator)
            .map(String::trim)
            .filter { it.isNotEmpty() }

        return pathEntries.flatMap { entry ->
            names.map { name -> File(entry, name) }
        }.filter { it.isFile }
    }

    private fun collectCommonGradleHomes(): List<File> {
        val osName = System.getProperty("os.name").lowercase()
        val roots = when {
            osName.contains("win") -> listOf(
                File("C:/Program Files/Gradle"),
                File("C:/Gradle"),
                File(System.getenv("LOCALAPPDATA") ?: "", "Programs/Gradle")
            )
            osName.contains("mac") -> listOf(
                File("/opt/gradle"),
                File("/usr/local/opt/gradle"),
                File(System.getProperty("user.home"), ".sdkman/candidates/gradle")
            )
            else -> listOf(
                File("/opt/gradle"),
                File("/usr/local/gradle"),
                File(System.getProperty("user.home"), ".sdkman/candidates/gradle")
            )
        }

        return roots
            .filter { it.isDirectory }
            .flatMap { root -> root.listFiles()?.toList().orEmpty().ifEmpty { listOf(root) } }
            .filter { it.isDirectory }
    }

    private fun collectCommonMavenHomes(): List<File> {
        val osName = System.getProperty("os.name").lowercase()
        val roots = when {
            osName.contains("win") -> listOf(
                File("C:/Program Files/Apache/Maven"),
                File("C:/Program Files/Apache Maven"),
                File("C:/Program Files/JetBrains"),
                File(System.getenv("LOCALAPPDATA") ?: "", "Programs/JetBrains"),
                File(System.getenv("LOCALAPPDATA") ?: "", "JetBrains/Toolbox/apps")
            )
            osName.contains("mac") -> listOf(
                File("/opt/homebrew/Cellar/maven"),
                File("/usr/local/Cellar/maven"),
                File("/Applications"),
                File(System.getProperty("user.home"), "Applications")
            )
            else -> listOf(
                File("/opt"),
                File("/usr/share"),
                File("/usr/local/share")
            )
        }

        val discovered = mutableListOf<File>()
        if (osName.contains("win")) {
            File("C:/").listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("apache-maven", ignoreCase = true) }
                ?.forEach { discovered.add(it) }
        }
        roots.filter { it.exists() }.forEach { root ->
            if (root.isDirectory) {
                root.walkTopDown()
                    .maxDepth(4)
                    .filter { dir ->
                        dir.isDirectory && (
                            dir.name.equals("maven", ignoreCase = true) ||
                                dir.name.startsWith("apache-maven", ignoreCase = true) ||
                                dir.path.replace('\\', '/').contains("/plugins/maven/lib/maven3")
                            )
                    }
                    .forEach { discovered.add(it) }
            }
        }

        return discovered.distinctBy { it.absolutePath }
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
