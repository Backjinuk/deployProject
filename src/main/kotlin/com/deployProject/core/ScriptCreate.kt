package com.deployProject.core

/**
 * ScriptCreate: 배포·백업·레거시 패치 스크립트 생성기
 */
class ScriptCreate {

    /**
     * 변경된 파일 리스트와 deployDir을 받아
     * 한 개의 interactive patch.sh 스크립트를 반환합니다.
     *
     * @param changedFiles 프로젝트 내 상대 경로 리스트
     * @param deployDir 운영 서버 배포 루트 경로
     * @return List<Pair<스크립트명, 스크립트 라인 리스트>>
     */
    fun getLegacyPatchScripts(
        changedFiles: List<String>,
        deployDir: String
    ): List<Pair<String, List<String>>> {

        println("Creating legacy patch script for deploy directory: $deployDir")

        val root = deployDir.trimEnd('/', '\\')
        val dateExpr = """$(date +'%Y%m%d')"""

        val script = mutableListOf<String>().apply {
            // ── 헤더
            add("#!/usr/bin/env bash")
            add("set -euo pipefail")
            add("")
            add("ROOT=\"$root\"")
            add("DATE=\"$dateExpr\"")
            add("STAGING_DIR=\"\$HOME/\$DATE\"")
            add("")

            // ── 루트 디렉터리 박스 출력
            add("echo \"####################################\"")
            add("echo \"# \$ROOT #\"")
            add("echo \"####################################\"")
            add("")

            // ── 변경된 파일 리스트
            add("changed_files=(")
            changedFiles.forEach { add("  '$it'") }
            add(")")
            add("")

            // ── pending operations 모아두기
            add("ops=()")
            add("for f in \"\${changed_files[@]}\"; do")
            add("  base=\$(basename \"\$f\")")
            add("  dst=\"\$ROOT/\$f\$DATE\"")
            add("  ops+=(\"\$base → \$dst\")")
            add("done")
            add("")
            add("echo \"Pending deploy operations:\"")
            add("for op in \"\${ops[@]}\"; do")
            add("  echo \"  \$op\"")
            add("done")
            add("")

            // ── 사용자 확인
            add("read -p \"Proceed with deploy? [y/N]: \" ans")
            add("case \"\${ans,,}\" in")
            add("  y|yes)")
            add("    echo \"▶️  Deploy confirmed, starting...\"")
            add("    ;;")
            add("  *)")
            add("    echo \"❌  Deploy canceled.\"")
            add("    exit 1")
            add("    ;;")
            add("esac")
            add("")

            // ── 실제 복사 실행
            add("for f in \"\${changed_files[@]}\"; do")
            add("  src=\"\$STAGING_DIR/\$f\"")
            add("  dst=\"\$ROOT/\$f\"")
            add("  if [ ! -f \"\$src\" ]; then")
            add("    echo \"[WARN] staging file missing: \$src\" >&2")
            add("    continue")
            add("  fi")
            add("  mkdir -p \"\$(dirname \"\$dst\")\"")
            add("  cp \"\$src\" \"\$dst\"")
            add("  echo \"  deployed: \$(basename \"\$f\") → \$dst\"")
            add("done")
            add("")
            add("echo \"✅  Deploy complete.\"")
        }

        return listOf("patch.sh" to script)
    }

}