package com.deployProject.cli

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DeployCliScriptTest {

    @Test
    fun testCreateDeployScript() {
        val deployCliScript = DeployCliScript()
        val changedFiles = listOf(
            "/src/main/webapp/WEB-INF/jsp/admin/dbmanager/inquery/InqueryEvaList.jsp",
            "/classes/artifacts/ncrc_icarevalue_admin_war_exploded/WEB-INF/classes/admin/eva/inquery/web/InqueryEvaController.class",
            "/src/main/resources/admin/sqlmap/eva/HistoryEva_SQL.xml",
            "/target/classes/admin/eva/inquery/web/InqueryEvaController\$1.class"
        )
        val deployDir = "/webroot/admin.icarevalue.or.kr/html"

        val scripts = deployCliScript.createDeployScript(changedFiles, deployDir)

        assertEquals(1, scripts.size)
        val (fileName, lines) = scripts.single()
        val deployPathsStart = lines.indexOf("deploy_rel_paths=(")

        assertEquals("patch.sh", fileName)
        assertTrue(deployPathsStart >= 0)
        assertTrue(lines.any { it.startsWith("SCRIPT_DIR=") })
        assertTrue(lines.any { it == "STAGING_DIR=\"\$SCRIPT_DIR\"" })
        assertTrue(lines.any { it == "[ -d \"\$STAGING_DIR\" ] || { echo \"[ERR] STAGING_DIR not found: \$STAGING_DIR\" >&2; exit 1; }" })
        assertTrue(lines.any { it == "candidate_target_paths() {" })
        assertTrue(lines.any { it == "candidate_staging_paths() {" })
        assertTrue(lines.any { it == "target_search_suffixes() {" })
        assertTrue(lines.any { it == "find_target_by_suffix() {" })
        assertTrue(lines.any { it == "find_staging_by_suffix() {" })
        assertTrue(lines.any { it == "resolve_staging_path() {" })
        assertTrue(lines.any { it == "resolve_target_path() {" })
        assertTrue(lines.any { it == "latest_backup_path() {" })
        assertTrue(lines.any { it == "resolved_staging_paths=()" })
        assertTrue(lines.any { it == "resolved_target_paths=()" })
        assertTrue(lines.any { it == "target_states=()" })
        assertTrue(lines.any { it == "  src=\"\$(resolve_staging_path \"\$rel\" \"\$dst_rel\")\"" })
        assertTrue(lines.any { it == "    found=\"\$(find \"\$STAGING_DIR\" -type f -path \"*/\$suffix\" -print -quit 2>/dev/null || true)\"" })
        assertTrue(lines.any { it == "  dst=\"\$(resolve_target_path \"\$rel\" \"\$src_rel\")\"" })
        assertTrue(lines.any { it == "    found=\"\$(find \"\$ROOT\" -type f -path \"*/\$suffix\" -print -quit 2>/dev/null || true)\"" })
        assertTrue(lines.any { it == "      printf '%s\\n' \"\$class_rel\"" })
        assertTrue(lines.any { it == "      backup_stamp=\"\$(date +'%Y%m%d_%H%M%S')\"" })
        assertTrue(lines.any { it == "        cp \"\$file\" \"\$dst_dir/\${base}\$backup_stamp\"" })
        assertTrue(lines.any { it == "        backup=\"\$(latest_backup_path \"\$file\" || true)\"" })
        assertTrue(lines.any { it == "        mkdir -p \"\$(dirname \"\$dst\")\"" })
        assertTrue(lines.any { it.contains("\"src/main/resources/admin/sqlmap/eva/HistoryEva_SQL.xml\"") })
        assertTrue(lines.any { it.contains("InqueryEvaController\\\$1.class") })
        assertTrue(lines.drop(deployPathsStart).any { it.contains("\"WEB-INF/classes/admin/sqlmap/eva/HistoryEva_SQL.xml\"") })
        assertTrue(lines.drop(deployPathsStart).any { it.contains("\"WEB-INF/jsp/admin/dbmanager/inquery/InqueryEvaList.jsp\"") })
    }
}
