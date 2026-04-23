package com.deployProject.cli

import java.nio.file.Paths

class DeployCliScript {

    private val asciiBanner = """
============================================================
 DEPLOY PROJECT PATCH SCRIPT
============================================================
""".trimIndent()

    fun createDeployScript(
        changedFiles: List<String>,
        deployDir: String
    ): List<Pair<String, List<String>>> {
        val rawRoot = Paths.get(deployDir).toAbsolutePath().normalize().toString()
        val root = rawRoot.replace('\\', '/').replaceFirst(Regex("^[A-Za-z]:"), "")
        val dateExpr = "\$(date +'%Y%m%d')"

        val stagingRelPaths = changedFiles
            .map(::normalizeRelativePath)
            .distinct()

        // 수정 이유: 추출본(STAGING_DIR) 경로와 운영(ROOT) 경로는 구조가 다를 수 있어
        // src/main/webapp/*, build/classes/* 같은 경로가 섞이면 매칭이 깨진다.
        // 그래서 스테이징 경로/배포 경로를 분리해 1:1 매핑으로 유지한다.
        val pathMappings = stagingRelPaths.map { src -> src to mapToDeployRelativePath(src) }.distinct()

        val scriptLines = mutableListOf<String>().apply {
            add("#!/usr/bin/env bash")
            add("set -euo pipefail")
            add("")
            add("ROOT=\"$root\"")
            add("DATE=\"$dateExpr\"")
            add("STAGING_DIR=\"\$HOME/\$DATE\"")
            add("")
            add("cat << 'BANNER'")
            asciiBanner.lines().forEach { add(it) }
            add("BANNER")
            add("")
            add("[ -d \"\$ROOT\" ] || { echo \"[ERR] ROOT not found: \$ROOT\" >&2; exit 1; }")
            add("if [ ! -d \"\$STAGING_DIR\" ]; then")
            add("  echo \"[WARN] STAGING_DIR not found: \$STAGING_DIR (skip staging scan)\"")
            add("fi")
            add("")

            add("staging_rel_paths=(")
            pathMappings.forEach { (stagingRel, _) -> add("  \"$stagingRel\"") }
            add(")")
            add("")

            add("deploy_rel_paths=(")
            pathMappings.forEach { (_, deployRel) -> add("  \"$deployRel\"") }
            add(")")
            add("")

            add("changed_files=()")
            add("for rel in \"\${staging_rel_paths[@]}\"; do")
            add("  found=\$(find \"\$STAGING_DIR\" -type f -path \"*/\$rel\" 2>/dev/null | head -n1 || true)")
            add("  if [ -z \"\$found\" ]; then")
            add("    name=\$(basename \"\$rel\")")
            add("    found=\$(find \"\$STAGING_DIR\" -type f -name \"\$name\" 2>/dev/null | head -n1 || true)")
            add("  fi")
            add("  [ -n \"\$found\" ] && changed_files+=(\"\$found\")")
            add("done")
            add("")

            add("raw_changed_files=()")
            add("for rel in \"\${deploy_rel_paths[@]}\"; do")
            add("  found=\$(find \"\$ROOT\" -type f -path \"*/\$rel\" 2>/dev/null | head -n1 || true)")
            add("  if [ -z \"\$found\" ]; then")
            add("    name=\$(basename \"\$rel\")")
            add("    found=\$(find \"\$ROOT\" -type f -name \"\$name\" 2>/dev/null | head -n1 || true)")
            add("  fi")
            add("  [ -n \"\$found\" ] && raw_changed_files+=(\"\$found\")")
            add("done")
            add("")

            add("while true; do")
            add("  echo")
            add("  echo 'Select mode:'")
            add("  echo '  [b] Backup only'")
            add("  echo '  [d] Deploy (Backup + Deploy)'")
            add("  echo '  [r] Recover (restore from backups)'")
            add("  echo '  [c] Cancel'")
            add("  read -p 'Enter choice (b/d/r/c): ' mode")
            add("  case \"\${mode,,}\" in")

            add("    b)")
            add("      echo '[INFO] Pending backup operations:'")
            add("      for file in \"\${raw_changed_files[@]}\"; do")
            add("        if [ ! -f \"\$file\" ]; then")
            add("          echo \"[WARN] File not found: \$file\" >&2; continue")
            add("        fi")
            add("        echo \"  \$(basename \"\$file\") -> \$file\"")
            add("      done")
            add("      echo")
            add("      echo '[INFO] Top-level dirs by extension:'")
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
            add("      case \"\${ans,,}\" in y|yes) ;; *) echo '[CANCEL] Backup canceled.'; exit 1;; esac")
            add("      echo '[INFO] Backup start'")
            add("      for file in \"\${raw_changed_files[@]}\"; do")
            add("        if [ -f \"\$file\" ]; then")
            add("          base=\$(basename \"\$file\"); dst_dir=\$(dirname \"\$file\")")
            add("          cp \"\$file\" \"\$dst_dir/\${base}\$DATE\"")
            add("          echo \"  backed up: \$base -> \$dst_dir/\${base}\$DATE\"")
            add("        fi")
            add("      done")
            add("      echo '[OK] Backup complete.'")
            add("      ;;")

            add("    d)")
            add("      echo '[INFO] Pending deploy operations:'")
            add("      for i in \"\${!deploy_rel_paths[@]}\"; do")
            add("        src_rel=\"\${staging_rel_paths[\$i]}\"")
            add("        dst_rel=\"\${deploy_rel_paths[\$i]}\"")
            add("        src=\$(find \"\$STAGING_DIR\" -type f -path \"*/\$src_rel\" 2>/dev/null | head -n1 || true)")
            add("        [ -z \"\$src\" ] && src=\$(find \"\$STAGING_DIR\" -type f -name \"\$(basename \"\$src_rel\")\" 2>/dev/null | head -n1 || true)")
            add("        dst=\$(find \"\$ROOT\" -type f -path \"*/\$dst_rel\" 2>/dev/null | head -n1 || true)")
            add("        [ -z \"\$dst\" ] && dst=\$(find \"\$ROOT\" -type f -name \"\$(basename \"\$dst_rel\")\" 2>/dev/null | head -n1 || true)")
            add("        name=\$(basename \"\$dst_rel\")")
            add("        if [ -z \"\$src\" ]; then echo \"[WARN] Staging file not found: \$name\" >&2; continue; fi")
            add("        if [ -z \"\$dst\" ]; then echo \"[WARN] Target file not found under ROOT: \$name\" >&2; continue; fi")
            add("        echo \"  \$name: \$src -> \$dst\"")
            add("      done")
            add("      echo")
            add("      echo '[INFO] Top-level dirs by extension:'")
            add("      declare -A extDirs=()")
            add("      for f in \"\${raw_changed_files[@]}\"; do")
            add("        ext=\${f##*.}; dir=\$(dirname \"\$f\")")
            add("        if [ -z \"\${extDirs[\$ext]+x}\" ]; then extDirs[\"\$ext\"]=\"\$dir\"; fi")
            add("      done")
            add("      for e in \"\${!extDirs[@]}\"; do echo \"  .\$e -> \${extDirs[\$e]}\"; done")
            add("      echo")
            add("      read -p 'Proceed with Deploy? [y/N]: ' ans")
            add("      case \"\${ans,,}\" in y|yes) ;; *) echo '[CANCEL] Deploy canceled.'; exit 1;; esac")
            add("      echo '[INFO] Deploying...'")
            add("      for i in \"\${!deploy_rel_paths[@]}\"; do")
            add("        src_rel=\"\${staging_rel_paths[\$i]}\"")
            add("        dst_rel=\"\${deploy_rel_paths[\$i]}\"")
            add("        src=\$(find \"\$STAGING_DIR\" -type f -path \"*/\$src_rel\" 2>/dev/null | head -n1 || true)")
            add("        [ -z \"\$src\" ] && src=\$(find \"\$STAGING_DIR\" -type f -name \"\$(basename \"\$src_rel\")\" 2>/dev/null | head -n1 || true)")
            add("        dst=\$(find \"\$ROOT\" -type f -path \"*/\$dst_rel\" 2>/dev/null | head -n1 || true)")
            add("        [ -z \"\$dst\" ] && dst=\$(find \"\$ROOT\" -type f -name \"\$(basename \"\$dst_rel\")\" 2>/dev/null | head -n1 || true)")
            add("        if [ -n \"\$src\" ] && [ -n \"\$dst\" ]; then")
            add("          cp \"\$src\" \"\$dst\"")
            add("          echo \"  deployed: \$(basename \"\$dst_rel\") -> \$dst\"")
            add("        fi")
            add("      done")
            add("      echo '[OK] Deploy complete.'")
            add("      ;;")

            add("    r)")
            add("      echo '[INFO] Pending recover operations:'")
            add("      for file in \"\${raw_changed_files[@]}\"; do")
            add("        backup=\"\${file}\$DATE\"")
            add("        if [ ! -f \"\$backup\" ]; then echo \"[WARN] Backup not found: \$backup\" >&2; continue; fi")
            add("        echo \"  \$backup -> \$(basename \"\$file\")\"")
            add("      done")
            add("      echo")
            add("      read -p 'Proceed with Recover? [y/N]: ' ans")
            add("      case \"\${ans,,}\" in y|yes) ;; *) echo '[CANCEL] Recover canceled.'; exit 1;; esac")
            add("      echo '[INFO] Recovering...'")
            add("      for file in \"\${raw_changed_files[@]}\"; do")
            add("        backup=\"\${file}\$DATE\"")
            add("        if [ -f \"\$backup\" ]; then")
            add("          cp \"\$backup\" \"\$file\"")
            add("          echo \"  recovered: \$(basename \"\$file\") <- \$backup\"")
            add("        fi")
            add("      done")
            add("      echo '[OK] Recover complete.'")
            add("      ;;")

            add("    c)")
            add("      echo '[CANCEL] Operation canceled by user, exiting.'")
            add("      break")
            add("      ;;")

            add("    *)")
            add("      echo \"[WARN] Invalid choice: \$mode\" >&2")
            add("      ;;")

            add("  esac")
            add("done")
            add("echo '[DONE] Script finished.'")
        }

        return listOf("patch.sh" to scriptLines)
    }

    private fun normalizeRelativePath(raw: String): String {
        return raw.replace('\\', '/')
            .trimStart('/')
            .replace(Regex("^(?:\\.\\./)+"), "")
    }

    private fun mapToDeployRelativePath(path: String): String {
        return when {
            path.startsWith("src/main/webapp/") ->
                path.removePrefix("src/main/webapp/")
            Regex("^build/classes/(?:java|kotlin)/main/").containsMatchIn(path) ->
                "WEB-INF/classes/" + path.replaceFirst(Regex("^build/classes/(?:java|kotlin)/main/"), "")
            path.startsWith("build/classes/main/") ->
                "WEB-INF/classes/" + path.removePrefix("build/classes/main/")
            path.startsWith("target/classes/") ->
                "WEB-INF/classes/" + path.removePrefix("target/classes/")
            Regex("^out/production/[^/]+/").containsMatchIn(path) ->
                "WEB-INF/classes/" + path.replaceFirst(Regex("^out/production/[^/]+/"), "")
            Regex("^target/[^/]+/WEB-INF/").containsMatchIn(path) ->
                path.replaceFirst(Regex("^target/[^/]+/"), "")
            Regex("^target/[^/]+/css/").containsMatchIn(path) ->
                "css/" + path.replaceFirst(Regex("^target/[^/]+/css/"), "")
            Regex("^target/[^/]+/js/").containsMatchIn(path) ->
                "js/" + path.replaceFirst(Regex("^target/[^/]+/js/"), "")
            else -> path
        }
    }
}
