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
        changedFiles: List<String>, deployDir: String
    ): List<Pair<String, List<String>>> {
        val deployDir = deployDir.trimEnd('/', '\\')
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


// ... 기본정보(basicInfo) 정의 직후에 추가
        val progressFunc  = listOf(
            "print_progress() {",
            "  local current=\$1 total=\$2",
            "  local percent=\$(( current * 100 / total ))",
            "  local filled=\$(( percent / 2 ))",
            "  local empty=\$(( 50 - filled ))",
            "  # # 개수만큼 채우고 나머지는 공백으로",
            "  local bar=\$(printf '%0.s#' \$(seq 1 \$filled))",
            "  printf '\\r[%-50s] %3d%%' \"\$bar\" \"\$percent\"",
            "}",
            ""
        )

        // 1) 백업 스크립트 (backup.sh)
        val backupLines = mutableListOf<String>().apply {
            addAll(basicInfo)
            addAll(progressFunc)
            add("## 백업용")
            changedFiles.forEach { rel ->
                val nRel = if(rel.contains("$"))  rel.replace("$", "\\$") else rel
                val dir = nRel.substringBeforeLast('/')
                val file = nRel.substringAfterLast('/')
                add("cd \"$deployDir/$dir\" && cp \"$file\" \"${file}\$DATE\"")
            }

            add("echo \"\\n백업 완료\"")
        }
        scripts += "backup.sh" to backupLines

        // 2) 배포 스크립트 (deploy.sh)
        val deployLines = mutableListOf<String>().apply {
            addAll(basicInfo)
            addAll(progressFunc)
            add("## 배포용")
            changedFiles.forEach { rel ->
                val nRel = if(rel.contains("$"))  rel.replace("$", "\\$") else rel
                add("cp \"\$STAGING_DIR/${nRel}\" \"$deployDir/${nRel}\"")
            }
            add("echo \"\\n배포 완료\"")
        }
        scripts += "deploy.sh" to deployLines

        // 3) 원복 스크립트 (recover.sh)
        val recoverLines = mutableListOf<String>().apply {
            addAll(basicInfo)
            addAll(progressFunc)
            add("## 원복용")
            changedFiles.forEach { rel ->
                val nRel = if(rel.contains("$"))  rel.replace("$", "\\$") else rel
                add("cd \"$deployDir/${nRel}\" && cp \"\$STAGING_DIR/${nRel}\"")
            }
            add("echo \"\\n원복 완료\"")
        }
        scripts += "recover.sh" to recoverLines

        // 4) 스크립트 권한 스크립트
        val chmodLines = mutableListOf<String>().apply {
            addAll(basicInfo)
            addAll(progressFunc)
            add("## 권한 부여용")
            add("chmod +x backup.sh")
            add("chmod +x deploy.sh")
            add("chmod +x recover.sh")
            add("echo \"\\n권한 부여 완료\"")
        }

        scripts += "chmod.sh" to chmodLines

        return scripts
    }
}
