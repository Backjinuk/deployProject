package com.deployproject.util

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * CLI utility that extracts Git status, log, and diff info into a ZIP archive.
 *
 * Usage:
 *   java -jar git-info-cli.jar <path-to-git-dir> [<output-zip-path>]
 *   - If only <path-to-git-dir> is provided, the ZIP will be named git-info-<timestamp>.zip
 */
object GitInfoCli {
    @JvmStatic
    fun main(args: Array<String>) {

        val flags = args.filter { it.startsWith("--") }
        val other = args.filter { it.endsWith("--") }

        val doStatus = flags.contains("--status") || flags.isEmpty()
        val doDiff = flags.contains("--diff") || flags.isEmpty()
        val doLog = flags.contains("--log") || flags.isEmpty()


        if (args.isEmpty()) {
            println("Usage: java -jar git-info-cli.jar <git-dir> [<output-zip-path>]")
            return
        }

        val gitDir = File(args[0])
        if (!gitDir.exists() || !File(gitDir, "config").exists()) {
            println("ERROR: Not a valid Git repository: ${gitDir.absolutePath}")
            return
        }

        // Determine output path
        val outputZip = if (args.size >= 2) {
            File(args[1])
        } else {
            val parent = gitDir.parentFile
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
            File(parent, "git-info-$timestamp.zip")
        }

        val git = Git.open(gitDir)
        val repo: Repository = git.repository

        // 1) Collect status paths
        val status = git.status().call()
        val statusPaths = (status.added + status.changed + status.modified + status.removed + status.untracked).toList()

        // 2) Collect last 10 commits
        val commits = git.log().setMaxCount(10).call()


        // 3) Collect diff paths HEAD~1..HEAD
        var diffPaths = emptyList<String>()
        if(doDiff) {
            diffPaths = repo.newObjectReader().use { reader ->
                RevWalk(repo).use { walk ->
                    val head = walk.parseCommit(repo.resolve("HEAD"))
                    val parent = head.parents.firstOrNull()?.let { walk.parseCommit(it.id) }

                    val oldTree = CanonicalTreeParser().apply {
                        if (parent != null) reset(reader, repo.resolve("${parent.name}^{tree}"))
                    }
                    val newTree = CanonicalTreeParser().apply {
                        reset(reader, repo.resolve("${head.name}^{tree}"))
                    }

                    git.diff().setOldTree(oldTree).setNewTree(newTree).call()
                        .map { diff -> if (diff.changeType == DiffEntry.ChangeType.DELETE) diff.oldPath else diff.newPath }
                }
            }
        }

        // 4) Write to ZIP
        ZipOutputStream(Files.newOutputStream(outputZip.toPath())).use { zip ->
            val fmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss")

            if(doStatus) {
                zip.putNextEntry(ZipEntry("status.txt"))
                statusPaths.joinToString("\n").byteInputStream().copyTo(zip)
                zip.closeEntry()
            }

            if(doLog) {
                zip.putNextEntry(ZipEntry("log.txt"))
                commits.joinToString("\n") { c ->
                    "${c.name}|${c.authorIdent.name}|${fmt.format(c.authorIdent.`when`)}|${
                        c.fullMessage.replace(
                            "\n",
                            " "
                        )
                    }"
                }.byteInputStream().copyTo(zip)
                zip.closeEntry()
            }

            if(doDiff) {
                zip.putNextEntry(ZipEntry("diff.txt"))
                diffPaths.joinToString("\n").byteInputStream().copyTo(zip)
                zip.closeEntry()
            }
        }

        println("✅ Created ZIP: ${outputZip.absolutePath}")
    }
}
