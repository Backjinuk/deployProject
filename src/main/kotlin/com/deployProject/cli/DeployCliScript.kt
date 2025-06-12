package com.deployProject.cli

import java.nio.file.Paths

/**
 * DeployCliScript: 배포·백업·레거시 패치 스크립트 생성기
 */
class DeployCliScript {

    /**
     * ASCII 아트 배너
     */
    private val asciiBanner = """
██████╗ ███████╗██████╗ ██╗      ██████╗ ██╗   ██╗███╗   ███╗ █████╗ ███╗   ██╗
██╔══██╗██╔════╝██╔══██╗██║     ██╔═══██╗╚██╗ ██╔╝████╗ ████║██╔══██╗████╗  ██║
██║  ██║█████╗  ██████╔╝██║     ██║   ██║ ╚████╔╝ ██╔████╔██║███████║██╔██╗ ██║
██║  ██║██╔══╝  ██╔═══╝ ██║     ██║   ██║  ╚██╔╝  ██║╚██╔╝██║██╔══██║██║╚██╗██║
██████╔╝███████╗██║     ███████╗╚██████╔╝   ██║   ██║ ╚═╝ ██║██║  ██║██║ ╚████║
╚═════╝ ╚══════╝╚═╝     ╚══════╝ ╚═════╝    ╚═╝   ╚═╝     ╚═╝╚═╝  ╚═╝╚═╝  ╚═══╝
""".trimIndent()

    /**
     * changedFiles: 절대 또는 프로젝트 기준 상대 경로 리스트
     * deployDir: 배포 루트 디렉터리
     */
    fun createDeployScript(
        changedFiles: List<String>,
        deployDir: String
    ): List<Pair<String, List<String>>> {
        // 프로젝트 루트
        val rawRoot = Paths.get(deployDir)
            .toAbsolutePath()
            .normalize()
            .toString()

        val root = rawRoot
            .replace('\\', '/')
            .replaceFirst(Regex("^[A-Za-z]:"), "")

        // 날짜 suffix
        val dateExpr = "\$(date +'%Y%m%d')"

        // relPaths 계산 (../, leading slash 제거)
        val relPaths = changedFiles.map { raw ->
            raw.replace('\\','/')
                .trimStart('/')
                .replace(Regex("^(?:\\.\\./)+"), "")
        }

        val scriptLines = mutableListOf<String>().apply {
            add("#!/usr/bin/env bash")
            add("set -euo pipefail")
            add("")
            add("ROOT=\"$root\"")
            add("DATE=\"$dateExpr\"")
            add("STAGING_DIR=\"\$HOME/\$DATE\"")
            add("")
            // ASCII banner
            add("cat << 'BANNER'")
            asciiBanner.lines().forEach { add(it) }
            add("BANNER")
            add("")

            // deploy 상대경로 리스트
            add("rel_paths=(")
            relPaths.forEach { rel ->
                add("  \"$rel\"")
            }
            add(")")
            add("")

// 1) Staging 에서 찾은 파일들로 changed_files 구성
            add("changed_files=()")
            add("for rel in \"\${rel_paths[@]}\"; do")
            add("  name=\$(basename \"\$rel\")")
            add("  found=\$(find \"\$STAGING_DIR\" -type f -name \"\$name\" | head -n1)")
            add("  [ -n \"\$found\" ] && changed_files+=(\"\$found\")")
            add("done")
            add("")

// 2) 운영 서버 ROOT 에서 찾은 파일들로 raw_changed_files 구성
            add("raw_changed_files=()")
            add("for rel in \"\${rel_paths[@]}\"; do")
            add("  name=\$(basename \"\$rel\")")
            add("  found=\$(find \"\$ROOT\" -type f -name \"\$name\" | head -n1)")
            add("  [ -n \"\$found\" ] && raw_changed_files+=(\"\$found\")")
            add("done")
            add("")

            // 모드 선택
            add("while true; do")
            add("  echo")
            add("echo 'Select mode:'")
            add("echo '  [b] Backup only'")
            add("echo '  [d] Deploy (Backup + Deploy)'")
            add("echo '  [r] Recursive'")
            add("read -p 'Enter choice (b/d/r): ' mode")
            add("case \"\${mode,,}\" in")

            // Backup only
            add("  b)")

            // 1) Pending backup operations
            add("    echo '▶️  Pending backup operations:'")
            add("    for file in \"\${raw_changed_files[@]}\"; do")
            add("      if [ ! -f \"\$file\" ]; then")
            add("        echo \"[WARN] File not found: \$file\" >&2")
            add("        continue")
            add("      fi")
            add("      name=\$(basename \"\$file\")")
            add("      echo \"  \$name -> \$file\"")
            add("    done")
            add("    echo")

            // 2) Top-level dirs by extension
            add("    echo '▶️  Top-level dirs by extension:'")
            add("    declare -A extDirs=()")
            add("    for file in \"\${raw_changed_files[@]}\"; do")
            add("      ext=\${file##*.}")
            add("      # get directory up to two levels below WEB-INF if present")
            add("      dir=\$(dirname \"\$file\")")
            add("      # strip down to WEB-INF/.../subdir")
            add("      rel=\${dir#\"\$ROOT/\"}")
            add("      # take WEB-INF plus next two segments")
            add("      prefix=\$(echo \"\$rel\" | awk -F/ '{")
            add("        for(i=1;i<=NF;i++){ if(\$i==\"WEB-INF\"){")
            add("          print \$i \"/\" \$(i+1) \"/\" \$(i+2); exit")
            add("        }}")
            add("      }')")
            add("      extDirs[\"\$ext\"]=\"\$ROOT/\$prefix\"")
            add("    done")
            add("    for e in \"\${!extDirs[@]}\"; do")
            add("      echo \"  .\$e -> \${extDirs[\$e]}\"")
            add("    done")
            add("    echo")

            // 3) 사용자 확인
            add("    read -p 'Proceed with Backup? [y/N]: ' ans")
            add("    case \"\${ans,,}\" in y|yes) ;; *) echo '❌  Backup canceled.'; exit 1;; esac")
            add("    echo '▶️  Backup start'")

            // 4) 실제 백업 실행
            add("    for file in \"\${raw_changed_files[@]}\"; do")
            add("      if [ -f \"\$file\" ]; then")
            add("        base=\$(basename \"\$file\")")
            add("        dst_dir=\$(dirname \"\$file\")")
            add("        cp \"\$file\" \"\$dst_dir/\${base}\$DATE\"")
            add("        echo \"  backed up: \$base -> \$dst_dir/\${base}\$DATE\"")
            add("      fi")
            add("    done")
            add("    echo '✅  Backup complete.'")
            add("    ;;")


            add("  d)")
            // 1) Pending deploy operations
            add("    echo '▶️  Pending deploy operations:'")
            add("    for f in \"\${raw_changed_files[@]}\"; do")
            add("      name=\$(basename \"\$f\")")
            add("      found=\$(find \"\$ROOT\" -type f -name \"\$name\" | head -n1)")
            add("      if [ -z \"\$found\" ]; then")
            add("        echo \"[WARN] Staging file not found: \$name\" >&2")
            add("        continue")
            add("      fi")
            add("      echo \"  \$name -> \$found\"")
            add("    done")
            add("    echo")
            add("")

            // 2) Top-level dirs by extension (운영 서버 기준 raw_changed_files 사용)
            add("    echo '▶️  Top-level dirs by extension:'")
            add("    declare -A extDirs=()")
            add("    for f in \"\${raw_changed_files[@]}\"; do")
            add("      ext=\${f##*.}")
            add("      dir=\$(dirname \"\$f\")")
            add("      # 첫 번째 ext 만 저장")
            add("      if [ -z \"\${extDirs[\$ext]+x}\" ]; then")
            add("        extDirs[\"\$ext\"]=\"\$dir\"")
            add("      fi")
            add("    done")
            add("    for e in \"\${!extDirs[@]}\"; do")
            add("      echo \"  .\$e -> \${extDirs[\$e]}\"")
            add("    done")
            add("    echo")

//            add("    echo '▶️  Commands to be executed:'")
//            add("    for f in \"\${changed_files[@]}\"; do")
//            add("      name=\$(basename \"\$f\")")
//            add("      src=\$(find \"\$STAGING_DIR\" -type f -name \"\$name\" | head -n1)")
//            add("      dst=\$(find \"\$ROOT\" -type f -name \"\$name\" | head -n1)")
//            add("      if [ -n \"\$src\" ] && [ -n \"\$dst\" ]; then")
//            add("        echo \"cp \\\"\$src\\\" \\\"\$dst\\\"\"")
//            add("      fi")
//            add("    done")
//            add("")

            // 3) 사용자 확인
            add("    read -p 'Proceed with Deploy? [y/N]: ' ans")
            add("    case \"\${ans,,}\" in y|yes) ;; *) echo '❌  Deploy canceled.'; exit 1;; esac")
            // 4) 실제 배포 실행
            add("    echo '▶️  Deploying...'")
            add("    for f in \"\${changed_files[@]}\"; do")
            add("      name=\$(basename \"\$f\")")
            add("      src=\$(find \"\$STAGING_DIR\" -type f -name \"\$name\" | head -n1)")
            add("      dst=\$(find \"\$ROOT\" -type f -name \"\$name\" | head -n1)")
            add("      cp \"\$src\" \"\$dst\"")
            add("      echo \"  deployed: \$name -> \$dst\"")
            add("    done")
            add("    echo '✅  Deploy complete.'")
            add("    ;;")

            // Recursive stub
            add("  r)")
// 1) Pending recover operations
            add("    echo '▶️  Pending recover operations:'")
            add("    for file in \"\${raw_changed_files[@]}\"; do")
            add("      backup=\"\${file}\$DATE\"")
            add("      if [ ! -f \"\$backup\" ]; then")
            add("        echo \"[WARN] Backup not found: \$backup\" >&2")
            add("        continue")
            add("      fi")
            add("      echo \" \$backup\" -> \$(basename \"\$file\") ")
            add("    done")
            add("    echo")

// 2) 사용자 확인
            add("    read -p 'Proceed with Recover? [y/N]: ' ans")
            add("    case \"\${ans,,}\" in y|yes) ;; *) echo '❌  Recover canceled.'; exit 1;; esac")
            add("    echo '▶️  Recovering...'")

// 3) 실제 복구 실행
            add("    for file in \"\${raw_changed_files[@]}\"; do")
            add("      backup=\"\${file}\$DATE\"")
            add("      cp \"\$backup\" \"\$file\"")
            add("      echo \"  recovered: \$(basename \"\$file\") -> \$file\"")
            add("    done")
            add("    echo '✅  Recover complete.'")
            add("    ;;")

            // Cancel
            add("    c)")
            add("      echo '❌  Operation canceled by user, exiting.'")
            add("      break")
            add("      ;;")

            // 잘못된 입력
            add("    *)")
            add("      echo \"⚠️  Invalid choice: \$mode\" >&2")
            add("      ;;")

            add("  esac")
            add("done")
            add("echo '👋  Script finished.'")
        }

        return listOf("patch.sh" to scriptLines)
    }
}
