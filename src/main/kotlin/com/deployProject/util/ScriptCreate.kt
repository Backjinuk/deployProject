package com.deployProject.util


/**
 * ScriptCreate: 배포·백업·레거시 패치 스크립트 생성기
 */
class ScriptCreate {
    /**
     * 변경된 파일 리스트와 배포 디렉터리만으로
     * deploy.sh, backup.sh, recover.sh 세 가지 스크립트를 반환합니다.
     * @param changedFiles 프로젝트 내 상대 경로 리스트
     * @param deployDir 운영 서버 배포 루트 경로
     * @return List<Pair<스크립트명, 스크립트 라인 리스트>>
     */
    fun getLegacyPatchScripts(
        changedFiles: List<String>,
        deployDir: String
    ): List<Pair<String, List<String>>> {
        val dateExpr = "$(date +'%Y%m%d')"
        val scripts = mutableListOf<Pair<String, List<String>>>()
        val basicInfo = listOf(
            "#!/usr/bin/env bash",
            "set -euo pipefail",
            "DATE=\"$dateExpr\"",
            "# 스테이징 디렉터리 (사용자 홈/DATE)",
            "STAGING_DIR=\"\$HOME/$dateExpr\"",
            "DEPLOY_DIR=\"$deployDir\"",
            "\n"
        )


        // 1) 백업 스크립트 (backup.sh)
        val backupLines = mutableListOf<String>().apply {
            addAll(basicInfo)
            add("## 백업용")
            changedFiles.forEach { rel ->
                val dir = rel.substringBeforeLast('/')
                val file = rel.substringAfterLast('/')
                add("cd \"$deployDir/$dir\" && cp \"$file\" \"${file}\$DATE\"")
            }
        }
        scripts += "backup.sh" to backupLines

        // 2) 배포 스크립트 (deploy.sh)
        val deployLines = mutableListOf<String>().apply {
            addAll(basicInfo)
            add("## 배포용")
            changedFiles.forEach { rel ->
                add("cp \"\$STAGING_DIR/\$DATE/${rel}\" \"$deployDir/${rel}\"")
            }
        }
        scripts += "deploy.sh" to deployLines

        // 3) 원복 스크립트 (recover.sh)
        val recoverLines = mutableListOf<String>().apply {
            addAll(basicInfo)
            add("## 원복용")
            changedFiles.forEach { rel ->
                add("cd \"$deployDir/${rel}\" && cp \"\$STAGING_DIR/\$DATE/${rel}\"")
            }
        }
        scripts += "recover.sh" to recoverLines

        return scripts
    }
}
