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

        // 프로젝트 루트(배포 루트로 사용)
        val rawRoot = Paths.get(deployDir).toAbsolutePath().normalize().toString()
        val root = rawRoot.replace('\\', '/').replaceFirst(Regex("^[A-Za-z]:"), "")

        // 날짜 suffix
        val dateExpr = "\$(date +'%Y%m%d')"

        // 1) 원본 경로 정규화
        val relPaths = changedFiles.map { raw ->
            raw.replace('\\', '/')
                .trimStart('/')
                .replace(Regex("^(?:\\.\\./)+"), "")
        }

        // 2) ✅ Kotlin에서 배포 경로로 미리 매핑 (배포 루트부터 쭉 밑으로 찾기)
        // - src/main/webapp/*     -> (접두어 제거)          ex) WEB-INF/jsp/..., css/..., js/...
        // - target/classes/*      -> WEB-INF/classes/*
        // - target/*/WEB-INF/*    -> WEB-INF/*
        // - target/*/css/*        -> css/*
        // - target/*/js/*         -> js/*
        val mappedRelPaths = relPaths.map { p ->
            when {
                p.startsWith("src/main/webapp/") ->
                    p.removePrefix("src/main/webapp/")
                p.startsWith("target/classes/") ->
                    "WEB-INF/classes/" + p.removePrefix("target/classes/")
                Regex("^target/[^/]+/WEB-INF/").containsMatchIn(p) ->
                    p.replaceFirst(Regex("^target/[^/]+/"), "")
                Regex("^target/[^/]+/css/").containsMatchIn(p) ->
                    "css/" + p.replaceFirst(Regex("^target/[^/]+/css/"), "")
                Regex("^target/[^/]+/js/").containsMatchIn(p) ->
                    "js/" + p.replaceFirst(Regex("^target/[^/]+/js/"), "")
                else -> p
            }
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
            // 기본 검증
            add("[ -d \"\$ROOT\" ] || { echo \"[ERR] ROOT not found: \$ROOT\" >&2; exit 1; }")
            add("if [ ! -d \"\$STAGING_DIR\" ]; then")
            add("  echo \"[WARN] STAGING_DIR not found: \$STAGING_DIR (skip staging scan)\"")
            add("fi")
            add("")

            // 3) ✅ 스크립트에는 매핑된 경로만 내려보냄
            add("rel_paths=(")
            mappedRelPaths.forEach { rel -> add("  \"$rel\"") }
            add(")")
            add("")

            // STAGING -> changed_files
            add("changed_files=()")
            add("for rel in \"\${rel_paths[@]}\"; do")
            add("  found=\$(find \"\$STAGING_DIR\" -type f -path \"*/\$rel\" 2>/dev/null | head -n1 || true)")
            add("  # (선택) 파일명 폴백: 경로 매칭 실패 시 파일명으로 한 번 더 시도")
            add("  if [ -z \"\$found\" ]; then")
            add("    name=\$(basename \"\$rel\")")
            add("    found=\$(find \"\$STAGING_DIR\" -type f -name \"\$name\" 2>/dev/null | head -n1 || true)")
            add("  fi")
            add("  [ -n \"\$found\" ] && changed_files+=(\"\$found\")")
            add("done")
            add("")

            // ROOT -> raw_changed_files
            add("raw_changed_files=()")
            add("for rel in \"\${rel_paths[@]}\"; do")
            add("  found=\$(find \"\$ROOT\" -type f -path \"*/\$rel\" 2>/dev/null | head -n1 || true)")
            add("  if [ -z \"\$found\" ]; then")
            add("    name=\$(basename \"\$rel\")")
            add("    found=\$(find \"\$ROOT\" -type f -name \"\$name\" 2>/dev/null | head -n1 || true)")
            add("  fi")
            add("  [ -n \"\$found\" ] && raw_changed_files+=(\"\$found\")")
            add("done")
            add("")

            // 모드 선택
            add("while true; do")
            add("  echo")
            add("  echo 'Select mode:'")
            add("  echo '  [b] Backup only'")
            add("  echo '  [d] Deploy (Backup + Deploy)'")
            add("  echo '  [r] Recover (restore from backups)'")
            add("  echo '  [c] Cancel'")
            add("  read -p 'Enter choice (b/d/r/c): ' mode")
            add("  case \"\${mode,,}\" in")

            // Backup only
            add("    b)")
            add("      echo '▶️  Pending backup operations:'")
            add("      for file in \"\${raw_changed_files[@]}\"; do")
            add("        if [ ! -f \"\$file\" ]; then")
            add("          echo \"[WARN] File not found: \$file\" >&2; continue")
            add("        fi")
            add("        echo \"  \$(basename \"\$file\") -> \$file\"")
            add("      done")
            add("      echo")
            add("      echo '▶️  Top-level dirs by extension:'")
            add("      declare -A extDirs=()")
            add("      for file in \"\${raw_changed_files[@]}\"; do")
            add("        ext=\${file##*.}; dir=\$(dirname \"\$file\")")
            add("        rel=\${dir#\"\$ROOT/\"}")
            add("        prefix=\$(echo \"\$rel\" | awk -F/ '{ for(i=1;i<=NF;i++){ if(\$i==\"WEB-INF\"){ print \$i \"/\" \$(i+1) \"/\" \$(i+2); exit }} }')")
            add("        [ -n \"\$prefix\" ] && extDirs[\"\$ext\"]=\"\$ROOT/\$prefix\" || extDirs[\"\$ext\"]=\"\$dir\"")
            add("      done")
            add("      for e in \"\${!extDirs[@]}\"; do echo \"  .\$e -> \${extDirs[\$e]}\"; done")
            add("      echo")
            add("      read -p 'Proceed with Backup? [y/N]: ' ans")
            add("      case \"\${ans,,}\" in y|yes) ;; *) echo '❌  Backup canceled.'; exit 1;; esac")
            add("      echo '▶️  Backup start'")
            add("      for file in \"\${raw_changed_files[@]}\"; do")
            add("        if [ -f \"\$file\" ]; then")
            add("          base=\$(basename \"\$file\"); dst_dir=\$(dirname \"\$file\")")
            add("          cp \"\$file\" \"\$dst_dir/\${base}\$DATE\"")
            add("          echo \"  backed up: \$base -> \$dst_dir/\${base}\$DATE\"")
            add("        fi")
            add("      done")
            add("      echo '✅  Backup complete.'")
            add("      ;;")

            // Deploy
            add("    d)")
            add("      echo '▶️  Pending deploy operations:'")
            add("      for rel in \"\${rel_paths[@]}\"; do")
            add("        src=\$(find \"\$STAGING_DIR\" -type f -path \"*/\$rel\" 2>/dev/null | head -n1 || true)")
            add("        [ -z \"\$src\" ] && src=\$(find \"\$STAGING_DIR\" -type f -name \"\$(basename \"\$rel\")\" 2>/dev/null | head -n1 || true)")
            add("        dst=\$(find \"\$ROOT\" -type f -path \"*/\$rel\" 2>/dev/null | head -n1 || true)")
            add("        [ -z \"\$dst\" ] && dst=\$(find \"\$ROOT\" -type f -name \"\$(basename \"\$rel\")\" 2>/dev/null | head -n1 || true)")
            add("        name=\$(basename \"\$rel\")")
            add("        if [ -z \"\$src\" ]; then echo \"[WARN] Staging file not found: \$name\" >&2; continue; fi")
            add("        if [ -z \"\$dst\" ]; then echo \"[WARN] Target file not found under ROOT: \$name\" >&2; continue; fi")
            add("        echo \"  \$name: \$src -> \$dst\"")
            add("      done")
            add("      echo")
            add("      echo '▶️  Top-level dirs by extension:'")
            add("      declare -A extDirs=()")
            add("      for f in \"\${raw_changed_files[@]}\"; do")
            add("        ext=\${f##*.}; dir=\$(dirname \"\$f\")")
            add("        if [ -z \"\${extDirs[\$ext]+x}\" ]; then extDirs[\"\$ext\"]=\"\$dir\"; fi")
            add("      done")
            add("      for e in \"\${!extDirs[@]}\"; do echo \"  .\$e -> \${extDirs[\$e]}\"; done")
            add("      echo")
            add("      read -p 'Proceed with Deploy? [y/N]: ' ans")
            add("      case \"\${ans,,}\" in y|yes) ;; *) echo '❌  Deploy canceled.'; exit 1;; esac")
            add("      echo '▶️  Deploying...'")
            add("      for rel in \"\${rel_paths[@]}\"; do")
            add("        src=\$(find \"\$STAGING_DIR\" -type f -path \"*/\$rel\" 2>/dev/null | head -n1 || true)")
            add("        [ -z \"\$src\" ] && src=\$(find \"\$STAGING_DIR\" -type f -name \"\$(basename \"\$rel\")\" 2>/dev/null | head -n1 || true)")
            add("        dst=\$(find \"\$ROOT\" -type f -path \"*/\$rel\" 2>/dev/null | head -n1 || true)")
            add("        [ -z \"\$dst\" ] && dst=\$(find \"\$ROOT\" -type f -name \"\$(basename \"\$rel\")\" 2>/dev/null | head -n1 || true)")
            add("        if [ -n \"\$src\" ] && [ -n \"\$dst\" ]; then")
            add("          cp \"\$src\" \"\$dst\"")
            add("          echo \"  deployed: \$(basename \"\$rel\") -> \$dst\"")
            add("        fi")
            add("      done")
            add("      echo '✅  Deploy complete.'")
            add("      ;;")

            // Recover
            add("    r)")
            add("      echo '▶️  Pending recover operations:'")
            add("      for file in \"\${raw_changed_files[@]}\"; do")
            add("        backup=\"\${file}\$DATE\"")
            add("        if [ ! -f \"\$backup\" ]; then echo \"[WARN] Backup not found: \$backup\" >&2; continue; fi")
            add("        echo \"  \$backup -> \$(basename \"\$file\")\"")
            add("      done")
            add("      echo")
            add("      read -p 'Proceed with Recover? [y/N]: ' ans")
            add("      case \"\${ans,,}\" in y|yes) ;; *) echo '❌  Recover canceled.'; exit 1;; esac")
            add("      echo '▶️  Recovering...'")
            add("      for file in \"\${raw_changed_files[@]}\"; do")
            add("        backup=\"\${file}\$DATE\"")
            add("        if [ -f \"\$backup\" ]; then")
            add("          cp \"\$backup\" \"\$file\"")
            add("          echo \"  recovered: \$(basename \"\$file\") <- \$backup\"")
            add("        fi")
            add("      done")
            add("      echo '✅  Recover complete.'")
            add("      ;;")

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
