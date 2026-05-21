package com.deployProject.cli.infoCli

import com.deployProject.cli.DeployCliScript
import com.deployProject.cli.utilCli.GitUtil
import com.deployProject.cli.utilCli.GitUtil.allowsStatus
import com.deployProject.deploy.domain.site.FileStatusType
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDate

class LocalInfoCli {
    private val log = LoggerFactory.getLogger(LocalInfoCli::class.java)

    fun localCliExecution(
        repoPath: String,
        since: LocalDate,
        until: LocalDate,
        fileStatusType: FileStatusType,
        deployServerDir: String,
        jdkPath: String?,
        selectedFiles: List<String>,
        requestedOutputDir: File? = null
    ): File {
        val workTree = File(repoPath).canonicalFile.let { if (it.isDirectory) it else it.parentFile }
            ?: error("Local path is invalid: $repoPath")
        val resolvedOutputDir = requestedOutputDir ?: GitUtil.determineDesktopOutputDir()
        val outputDir = resolvedOutputDir
        val artifactProfile = GitUtil.resolveArtifactProfile(workTree)
        val allowBuildArtifacts = GitUtil.profileUsesBuildArtifacts(artifactProfile)

        GitUtil.showProgressAndRun(
            title = "배포 파일 생성",
            initialMessage = "로컬 변경 파일을 정리하고 있습니다.",
            detailMessage = "수정일 기준 파일 수집, 클래스 생성, 패치 스크립트 생성을 진행합니다."
        ) {
            val targetPaths = GitUtil.logExtractionPhase(log, "local.collect-target-paths") {
                val selectedPathSet = selectedFiles
                    .map { it.trim().replace("\\", "/").removePrefix("/") }
                    .filter { it.isNotEmpty() }
                    .toSet()

                when {
                    selectedPathSet.isNotEmpty() -> selectedPathSet.toList()
                    !fileStatusType.allowsStatus() -> emptyList()
                    else -> GitUtil.collectModifiedFilesByDate(workTree, since, until)
                }
            }

            val entries = if (targetPaths.isEmpty()) {
                emptyList()
            } else {
                if (artifactProfile == GitUtil.ArtifactProfile.JVM_CLASS_ONLY && targetPaths.any(::isJvmSourcePath)) {
                    GitUtil.logExtractionPhase(log, "local.compile-jvm") {
                        GitUtil.compileJvmProject(workTree, jdkPath, targetPaths)
                    }
                }
                GitUtil.logExtractionPhase(log, "local.build-class-map") {
                    GitUtil.buildLatestClassMap(workTree, targetPaths)
                }
                GitUtil.logExtractionPhase(log, "local.map-extraction-paths") {
                    GitUtil.normalizeExtractionPaths(
                        workTree,
                        GitUtil.mapPathsForExtraction(targetPaths, artifactProfile)
                    )
                }
            }

            if (artifactProfile == GitUtil.ArtifactProfile.JVM_CLASS_ONLY) {
                val changedSourceCount = targetPaths.count(::isJvmSourcePath)
                val classEntryCount = entries.count { it.endsWith(".class", ignoreCase = true) }
                if (changedSourceCount > 0 && classEntryCount == 0) {
                    log.warn("No class artifacts found for selected local files.")
                    System.err.println("[WARN] No class artifacts found for selected local files.")
                }
            }

            GitUtil.logExtractionPhase(log, "local.copy-output-files") {
                GitUtil.addDirectoryEntry(resolvedOutputDir, workTree, entries, allowBuildArtifacts)
            }

            GitUtil.logExtractionPhase(log, "local.write-deploy-scripts") {
                DeployCliScript().createDeployScript(entries, deployServerDir).forEach { (name, lines) ->
                    GitUtil.writeTextOutputFile(resolvedOutputDir, name, lines.joinToString("\n"))
                }
            }

            log.info("Created output directory: {}", resolvedOutputDir.absolutePath)
            println("LOCAL artifact profile: ${artifactProfile.name}")
            println("LOCAL extraction summary: files=${entries.size}, selected=${targetPaths.size}")
            println("Extraction directory created: ${resolvedOutputDir.absolutePath}")
        }
        GitUtil.notifyCompletionAndOpenDirectory(outputDir, "배포 파일 생성이 완료되었습니다.")
        return resolvedOutputDir
    }

    private fun isJvmSourcePath(path: String): Boolean =
        path.endsWith(".java", ignoreCase = true) || path.endsWith(".kt", ignoreCase = true)
}
