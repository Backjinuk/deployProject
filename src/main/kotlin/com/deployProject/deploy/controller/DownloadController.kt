package com.deployProject.deploy.controller

import com.deployProject.DeployProjectApplication
import org.springframework.boot.system.ApplicationHome
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.PathResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name

data class InstallerStatusResponse(
    val workingDir: String,
    val applicationDir: String,
    val configuredPath: String?,
    val configuredDir: String?,
    val selectedPath: String?,
    val candidates: List<InstallerCandidateStatus>
)

data class InstallerCandidateStatus(
    val path: String,
    val exists: Boolean,
    val regularFile: Boolean,
    val size: Long?,
    val lastModified: String?
)

@RestController
class DownloadController(
    @Value("\${deploy.download.installer-path:}")
    private val installerPath: String,
    @Value("\${deploy.download.installer-dir:}")
    private val installerDir: String,
    @Value("\${deploy.download.installer-file-name:}")
    private val installerFileName: String
) {
    private val knownInstallerFileNames = listOf(
        "DeployKit.exe",
        "deploykit.exe",
        "DeployProject.exe",
        "deploy-project.exe"
    )

    @GetMapping("/download/deploykit.exe", "/download/deploy-project.exe")
    fun downloadInstaller(): ResponseEntity<Any> {
        val path = resolveInstallerPath()
        if (path == null) {
            return ResponseEntity.notFound()
                .header("X-DeployKit-Error", "Installer file not found")
                .build()
        }

        val downloadFileName = installerFileName.trim().ifBlank { path.fileName.toString() }
        val encodedFileName = URLEncoder.encode(downloadFileName, StandardCharsets.UTF_8)
            .replace("+", "%20")

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(Files.size(path))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"$downloadFileName\"; filename*=UTF-8''$encodedFileName"
            )
            .body(PathResource(path))
    }

    @GetMapping("/download/installer-status")
    fun installerStatus(): InstallerStatusResponse {
        val candidates = installerCandidatePaths()
        val selectedPath = candidates.firstOrNull { Files.isRegularFile(it) }

        return InstallerStatusResponse(
            workingDir().toString(),
            applicationDir().toString(),
            installerPath.trim().ifBlank { null },
            installerDir.trim().ifBlank { null },
            selectedPath?.toString(),
            candidates.map { candidate ->
                val regularFile = Files.isRegularFile(candidate)
                InstallerCandidateStatus(
                    path = candidate.toString(),
                    exists = Files.exists(candidate),
                    regularFile = regularFile,
                    size = if (regularFile) Files.size(candidate) else null,
                    lastModified = if (regularFile) Files.getLastModifiedTime(candidate).toString() else null
                )
            }
        )
    }

    private fun resolveInstallerPath(): Path? {
        return installerCandidatePaths().firstOrNull { Files.isRegularFile(it) }
    }

    private fun installerCandidatePaths(): List<Path> {
        val candidates = linkedSetOf<Path>()
        configuredInstallerPaths().forEach { candidates.add(it) }
        configuredInstallerDirs().forEach { installerDir ->
            findLatestInstallerInDir(installerDir)?.let { candidates.add(it) }
            addKnownInstallerCandidates(candidates, installerDir)
        }

        baseDirs().forEach { baseDir ->
            findLatestJpackageInstaller(baseDir)?.let { candidates.add(it) }
            addKnownInstallerCandidates(candidates, baseDir.resolve("build/download"))
            findLatestInstallerInDir(baseDir.resolve("download"))?.let { candidates.add(it) }
            addKnownInstallerCandidates(candidates, baseDir.resolve("download"))
        }

        return candidates.toList()
    }

    private fun addKnownInstallerCandidates(candidates: MutableSet<Path>, dir: Path) {
        knownInstallerFileNames.forEach { fileName ->
            candidates.add(dir.resolve(fileName).normalize())
        }
    }

    private fun configuredInstallerPaths(): List<Path> {
        val configuredPath = installerPath.trim()
        if (configuredPath.isBlank()) return emptyList()

        val path = Paths.get(configuredPath)
        if (path.isAbsolute) {
            return listOf(path.normalize())
        }

        return baseDirs().map { baseDir -> baseDir.resolve(path).normalize() }
    }

    private fun configuredInstallerDirs(): List<Path> {
        val configuredDir = installerDir.trim()
        if (configuredDir.isBlank()) return emptyList()

        val path = Paths.get(configuredDir)
        if (path.isAbsolute) {
            return listOf(path.normalize())
        }

        return baseDirs().map { baseDir -> baseDir.resolve(path).normalize() }
    }

    private fun findLatestJpackageInstaller(baseDir: Path): Path? {
        val jpackageOutputDir = baseDir.resolve("build/jpackage-output").normalize()
        if (!Files.isDirectory(jpackageOutputDir)) return null

        return findLatestInstallerInDir(jpackageOutputDir)
    }

    private fun findLatestInstallerInDir(dir: Path): Path? {
        if (!Files.isDirectory(dir)) return null

        return Files.list(dir).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { isInstallerFileName(it.name) }
                .max(Comparator.comparing { Files.getLastModifiedTime(it) })
                .orElse(null)
        }
    }

    private fun isInstallerFileName(fileName: String): Boolean {
        val normalized = fileName.lowercase()
        return normalized == "deploykit.exe" ||
            normalized == "deployproject.exe" ||
            normalized == "deploy-project.exe" ||
            (normalized.startsWith("deploykit-") && normalized.endsWith(".exe")) ||
            (normalized.startsWith("deployproject-") && normalized.endsWith(".exe")) ||
            (normalized.startsWith("deploy-project-") && normalized.endsWith(".exe"))
    }

    private fun baseDirs(): List<Path> {
        val dirs = linkedSetOf<Path>()
        listOf(workingDir(), applicationDir()).forEach { dir ->
            dirs.add(dir)
            dir.parent?.let { parent ->
                dirs.add(parent)
                parent.parent?.let { projectRoot -> dirs.add(projectRoot) }
            }
        }

        return dirs.toList()
    }

    private fun workingDir(): Path = Paths.get("").toAbsolutePath().normalize()

    private fun applicationDir(): Path =
        ApplicationHome(DeployProjectApplication::class.java).dir.toPath().toAbsolutePath().normalize()
}
