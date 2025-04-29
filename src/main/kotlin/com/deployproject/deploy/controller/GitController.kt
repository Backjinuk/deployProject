package com.deployproject.deploy.controller

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.Status
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.CommitTimeRevFilter
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.File
import java.text.SimpleDateFormat

@RestController
@RequestMapping("/api/git")
class GitController {

    fun openRepository(path: String): Git {
        return Git.open(File(path))
    }

    @RequestMapping("status")
    fun getRepository(@RequestParam path: String): Status {
        try {
            val git = openRepository(path)
            return git.status().call()
        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException("Failed to open repository at $path: ${e.message}")
        }
    }

    @RequestMapping("/log")
    fun getLog() {
        val path = "D:/DevSpace/deployProject/.git"
        val git = openRepository(path)
        val repo = git.repository

        val fmt = SimpleDateFormat("yyyy-MM-dd")
        val since = fmt.parse("2025-04-01")
        // 'until' 은 JGit에서 exclusive(<) 동작하므로,
        // 마지막 날짜를 포함하려면 다음날을 지정하거나 직접 테스트하세요.
        val until = fmt.parse("2025-04-02")

        repo.newObjectReader().use { reader ->
            RevWalk(repo).use { revWalk ->
                revWalk.markStart(revWalk.parseCommit(repo.resolve("HEAD")))

                val dataFilter: RevFilter = CommitTimeRevFilter.between(since, until)

                revWalk.revFilter = dataFilter

                println("==================== GIT LOG ====================")

                for (commit in revWalk) {
                    println("Commit: ${commit.name}")
                    println("Author: ${commit.authorIdent.name}")
                    println("Date:   ${commit.authorIdent.`when`}")

                    val parentCommit = commit.parents.firstOrNull()?.let {
                        revWalk.parseCommit(it.id)
                    }

                    val oldTree = CanonicalTreeParser().apply {
                        if (parentCommit != null) {
                            reset(reader, parentCommit.tree)
                        }
                    }

                    val newTree = CanonicalTreeParser().apply {
                        reset(reader, repo.resolve("${commit.name}^{tree}"))
                    }


                    val diffs: List<DiffEntry> = git.diff().setOldTree(oldTree).setNewTree(newTree).call()

                    for (diff in diffs) {
                        println("Diff: ${diff.changeType} ${diff.oldPath} -> ${diff.newPath}")
                    }

                    print("Message : ${commit.fullMessage}")
                }

                println("=================================================")
            }
        }
    }
}