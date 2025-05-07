package com.deployproject.util

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object GitInfoCli {

    @JvmStatic
    fun main(args: Array<String>) {
        val gitDir = parseGitDir(args)
        val outputZip = determineOutputZip(gitDir, args)
        val git = Git.open(gitDir)
        val repo = git.repository

        val statusPaths = collectStatusPaths(git, args[0])
        val diffPaths = collectDiffPaths(repo)

        println("statusPaths}\")  = ${statusPaths}")
        
        val workTree = gitDir.parentFile
        createZip(outputZip) { zip ->
            //diffPaths
/*
            addZipFiles(zip, workTree, diffPaths)
*/
        }


        println("✅ Created ZIP: ${outputZip.absolutePath}")
    }


    // ───────────────────────────────────────────────────────────
    // 1) 인자 검증 & Git 디렉터리 파싱
    // ───────────────────────────────────────────────────────────
    private fun parseGitDir(args: Array<String>): File {
        if (args.isEmpty()) {
            errorExit("Usage: java -jar git-info-cli.jar <git-dir> [<output-zip-path>]")
        }
        val gitDir = File(args[0])
        if (!gitDir.exists() || !File(gitDir, "config").exists()) {
            errorExit("ERROR: Not a valid Git repository: ${gitDir.absolutePath}")
        }
        return gitDir
    }

    private fun determineOutputZip(gitDir: File, args: Array<String>): File {
        return if (args.size >= 2) {
            File(args[1])
        } else {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
            File(gitDir.parentFile, "git-info-$timestamp.zip")
        }
    }

    private fun errorExit(msg: String): Nothing {
        println(msg)
        kotlin.system.exitProcess(1)
    }

    // ───────────────────────────────────────────────────────────
    // 2) Git status 경로 수집
    // ───────────────────────────────────────────────────────────
    private fun collectStatusPaths(git: Git, path: String): List<String> {
       val workTree = File(path.substringBeforeLast("/.git"))

        val status =  git.status().call()
        val statusList = (status.added +
                status.changed +
                status.modified +
                status.removed +
                status.untracked).toList()

        return statusList.map { relative ->
            File(workTree, relative).absolutePath
        }
    }

    // ───────────────────────────────────────────────────────────
    // 3) HEAD~1..HEAD diff 경로 수집
    // ───────────────────────────────────────────────────────────
    private fun collectDiffPaths(repo: Repository): List<String> {
        return repo.newObjectReader().use { reader ->
            RevWalk(repo).use { walk ->
                val head = walk.parseCommit(repo.resolve("HEAD"))
                val parent = head.parents.firstOrNull()?.let { walk.parseCommit(it) }
                val oldTree = CanonicalTreeParser().apply {
                    if (parent != null) reset(reader, repo.resolve("${parent.name}^{tree}"))
                }
                val newTree = CanonicalTreeParser().apply {
                    reset(reader, repo.resolve("${head.name}^{tree}"))
                }
                Git(repo).diff()
                    .setOldTree(oldTree)
                    .setNewTree(newTree)
                    .call()
                    .map { diff ->
                        if (diff.changeType == DiffEntry.ChangeType.DELETE)
                            diff.oldPath
                        else
                            diff.newPath
                    }
            }
        }
    }

    // ───────────────────────────────────────────────────────────
    // 4) ZIP 아카이브 생성 헬퍼
    // ───────────────────────────────────────────────────────────
    private fun createZip(outputZip: File, block: (ZipOutputStream) -> Unit) {
        ZipOutputStream(Files.newOutputStream(outputZip.toPath())).use { zip ->
            block(zip)
        }
    }

    // ───────────────────────────────────────────────────────────
    // 5) 실제 파일들을 ZIP에 담기
    // ───────────────────────────────────────────────────────────
    private fun addZipFiles(zip: ZipOutputStream, workTree: File, paths: List<String>) {
        for (relative in paths) {
            val file = File(workTree, relative)
            if (!file.exists()) {
                println("WARNING: File not found: $relative")
                continue
            }
            zip.putNextEntry(ZipEntry(relative))
            FileInputStream(file).use { fis ->
                fis.copyTo(zip)
            }
            zip.closeEntry()
        }
    }



    // ───────────────────────────────────────────────────────────
    // 6) 텍스트 엔트리(status.txt 등) 추가
    // ───────────────────────────────────────────────────────────
    private fun addTextEntry(zip: ZipOutputStream, entryName: String, lines: List<String>) {
        zip.putNextEntry(ZipEntry(entryName))
        lines.joinToString("\n").byteInputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }
}