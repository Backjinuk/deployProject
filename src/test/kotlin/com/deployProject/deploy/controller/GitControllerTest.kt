package com.deployProject.deploy.controller

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.CommitTimeRevFilter
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.junit.jupiter.api.Test
import java.io.File
import java.text.SimpleDateFormat

class GitControllerTest {

    fun openRepository(path: String): Git {
        return Git.open(File(path))
    }


    @Test
    fun getRepository() {
        val path = "D:/DevSpace/deployProject/.git"
        val git = openRepository(path)
        val status = git.status().call()
        println("==== GIT STATUS ====")
        println("isClean: ${status.isClean}")                                  // 작업 트리가 깨끗한지 여부
        println("hasUncommittedChanges: ${status.hasUncommittedChanges()}")   // 커밋되지 않은 변경이 있는지

        println("Added (staged new files): ${status.added.joinToString()}")
        println("Changed (staged modifications): ${status.changed.joinToString()}")
        println("Modified (unstaged modifications): ${status.modified.joinToString()}")
        println("Removed (staged deletions): ${status.removed.joinToString()}")
        println("Missing (indexed but missing on disk): ${status.missing.joinToString()}")
        println("Untracked (새로 생성됐지만 git add 안 된 파일): ${status.untracked.joinToString()}")
        println("Untracked Folders: ${status.untrackedFolders.joinToString()}")
        println("Conflicting (충돌 중인 파일): ${status.conflicting.joinToString()}")
    }

    @Test
    fun getLog() {
        val path = "D:/DevSpace/deployProject/.git"
        val git = openRepository(path)
        val repo = git.repository

        val fmt = SimpleDateFormat("yyyy-MM-dd")
        val since = fmt.parse("2025-04-01")
        // 'until' 은 JGit에서 exclusive(<) 동작하므로,
        // 마지막 날짜를 포함하려면 다음날을 지정하거나 직접 테스트하세요.
        val until = fmt.parse("2025-04-31")

        repo.newObjectReader().use { reader ->
            RevWalk(repo).use { revWalk ->
                revWalk.markStart(revWalk.parseCommit(repo.resolve("HEAD")))

                val dataFilter : RevFilter = CommitTimeRevFilter.between(since, until);

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
                        if(parentCommit != null){
                            reset(reader, parentCommit.tree)
                        }
                    }

                    val newTree = CanonicalTreeParser().apply {
                        reset(reader, repo.resolve("${commit.name}^{tree}"))
                    }


                   val diffs : List<DiffEntry> = git.diff()
                        .setOldTree(oldTree)
                        .setNewTree(newTree)
                        .call()

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