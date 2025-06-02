package com.deployProject.cli

import java.io.File


/**
 * ScriptCreate: 배포·백업·레거시 패치 스크립트 생성기
 */
class DeployCliScript {


    val asciiBanner = """
    ####################################################################################################################################################
    ##                                                ,--,                                                                                            ##
    ##                                  ,-.----.    ,---.'|'        ,----..                            ____                            ,--.           ##
    ##             ,---,         ,---,. \    /  \   |   | :       /   /   \                         ,'  , `.    ,---,               ,--.'|            ##
    ##           .'  .' `\     ,'  .' | |   :    \  :   : |      /   .     :          ,---,      ,-+-,.' _ |   '  .' \          ,--,:  : |            ##
    ##         ,---.'     \  ,---.'   | |   |  .\ : |   ' :     .   /   ;.  \        /_ ./|   ,-+-. ;   , ||  /  ;    '.     ,`--.'`|  ' :            ##
    ##         |   |  .`\  | |   |   .' .   :  |: | ;   ; '    .   ;   /  ` ;  ,---, |  ' :  ,--.'|'   |  ;| :  :       \    |   :  :  | |            ##
    ##         :   : |  '  | :   :  |-, |   |   \ : '   | |__  ;   |  ; \ ; | /___/ \.  : | |   |  ,', |  ': :  |   /\   \   :   |   \ | :            ##
    ##         |   ' '  ;  : :   |  ;/| |   : .   / |   | :.'| |   :  | ; | '  .  \  \,', ' |   | /  | |  || |  :  ' ;.   :  |   : '  '; |            ##
    ##         '   | ;  .  | |   :   .' ;   | |`-'  '   :    ; .   |  ' ' ' :   \  ;  `  ,' '   | :  | :  |, |  |  ;/  \   \ '   ' ;.    ;            ##
    ##         |   | :  |  ' |   |  |-, |   | ;     |   |  ./  '   ;  \; /  |    \  \    '  ;   . |  ; |--'  '  :  | \  \,', |   | | \   |            ##
    ##         '   : | /  ;  '   :  ;/| :   ' |     ;   : ;     \   \,  ',  /      '  \   |  |   : |  | ,     |  |  '  '--'   '   : |  ; .'           ##
    ##         |   | '` ,/   |   |    \ :   : :     |   ,/       ;   :    /        \  ;  ;  |   : '  |/      |  :  :         |   | '`--'              ##
    ##         ;   :  .'     |   :   .' |   | :     '---'         \   \ .'          :  \  \ ;   | |`-'       |  | ,'         '   : |                  ##
    ##         |   ,.'       |   | ,'   `---'.|                    `---`             \  ' ; |   ;/           `--''           ;   |.'                  ##
    ##         '---'         `----'       `---`                                       `--`  '---'                            '---'                    ##
    ####################################################################################################################################################
""".trimIndent()

    /**
     * 변경된 파일 리스트와 deployDir을 받아
     * 한 개의 interactive patch.sh 스크립트를 반환합니다.
     *
     * @param changedFiles 프로젝트 내 상대 경로 리스트
     * @param deployDir 운영 서버 배포 루트 경로
     * @return List<Pair<스크립트명, 스크립트 라인 리스트>>
     */
    fun createDeployScript(
        changedFiles: List<String>,
        deployDir: String
    ): List<Pair<String, List<String>>> {
        // 루트 디렉터리
        val root = deployDir.trimEnd('/', '\\')
        // 스테이징 날짜
        val dateExpr = "\$(date +'%Y%m%d')"
        // 프로젝트 폴더 이름 (Windows 절대경로 포함시 제거용)
        val projectName = File(root).name
        // 상대 경로 리스트: 백슬래시 -> 슬래시, 드라이브 경로 제거, 프로젝트명 이하만 남김
        val relPaths = changedFiles.map { raw ->
            raw.replace('\\', '/')
                .substringAfter("$projectName/")
        }

        val script = mutableListOf<String>().apply {
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


            // 파일 리스트
            add("changed_files=(")
            relPaths.forEach { add("  '$it'") }
            add(")")
            add("")
            // 모드 선택
            add("echo 'Select mode:'")
            add("echo '  [b] backup only   [d] deploy (backup + deploy)     [r] recursive'")
            add("read -p 'Enter choice (b/d/r): ' mode")
            add("case \"\${mode,,}\" in")

            // Backup only
            add("  b)")
            add("    echo '▶️  Backup start'")
            add("    for f in \"\${changed_files[@]}\"; do")
            add("      src=\"\$ROOT/\$f\"")
            add("      if [ ! -f \"\$src\" ]; then echo \"[WARN] '\$f' not found under \$ROOT\" >&2; continue; fi")
            add("      dst_dir=\$(dirname \"\$src\")")
            add("      base=\$(basename \"\$src\")")
            add("      cp \"\$src\" \"\$dst_dir/\${base}\$DATE\"")
            add("      echo \"  backed up: \$base -> \$dst_dir/\${base}\$DATE\"")
            add("    done")
            add("    echo '✅  Backup complete.'")
            add("    ;;")

            // Deploy (backup + deploy)
            add("  d)")
            // 백업
            add("    echo '▶️  Backup before deploy'")
            add("    for f in \"\${changed_files[@]}\"; do")
            add("      src=\"\$ROOT/\$f\"")
            add("      if [ -f \"\$src\" ]; then")
            add("        dst_dir=\$(dirname \"\$src\")")
            add("        base=\$(basename \"\$src\")")
            add("        cp \"\$src\" \"\$dst_dir/\${base}\$DATE\"")
            add("        echo \"  backed up: \$base -> \$dst_dir/\${base}\$DATE\"")
            add("      fi")
            add("    done")
            add("    echo '✅  Backup completed.'")
            add("")
            // 예정 작업 목록
            add("    echo '▶️  Pending deploy operations:'")
            add("    for f in \"\${changed_files[@]}\"; do")
            add("      base=\$(basename \"\$f\")")
            add("      echo \"  \$base -> \$ROOT/\$f\$DATE\"")
            add("    done")
            add("")
            // 확장자별 공통 최상단 디렉터리
            add("    echo '▶️  Top-level dirs by extension:'")
            add("    declare -A extDirs=()")
            add("    for e in \$(printf '%s\\n' \"\${changed_files[@]}\" | sed -E 's/.*\\.([^.]+)\$/\\1/' | sort -u); do")
            add("      # 해당 확장자 파일들 경로만 추출")
            add("      paths=()")
            add("      for f in \"\${changed_files[@]}\"; do")
            add("        [[ \"\$f\" == *.\$e ]] && paths+=(\"\$f\")")
            add("      done")
            add("      # 공통 상위 디렉터리 계산")
            add("      common=\$(dirname \"\${paths[0]}\")")
            add("      for p in \"\${paths[@]:1}\"; do")
            add("        while [[ \"\$p\" != \"\$common/*\" ]]; do common=\${common%/*}; done")
            add("      done")
            add("      extDirs[\"\$e\"]=\"\$common\"")
            add("    done")
            add("    for e in \"\${!extDirs[@]}\"; do")
            add("      echo \"  .\$e -> \$ROOT/\${extDirs[\$e]}\"")
            add("    done")
            add("    echo")
            // 사용자 확인
            add("    read -p 'Proceed with deploy? [y/N]: ' ans")
            add("    case \"\${ans,,}\" in y|yes) ;; *) echo '❌  Deploy canceled.'; exit 1 ;; esac")
            add("")
            // 실제 복사
            add("    echo '▶️  Deploying...' ")
            add("    for f in \"\${changed_files[@]}\"; do")
            add("      src=\"\$STAGING_DIR/\$f\"")
            add("      dst=\"\$ROOT/\$f\"")
            add("      mkdir -p \"\$(dirname \"\$dst\")\"")
            add("      cp \"\$src\" \"\$dst\"")
            add("      echo \"  deployed: \$(basename \"\$f\") -> \$dst\"")
            add("    done")
            add("    echo '✅  Deploy complete.'")
            add("    ;;")

            // recursive stub
            add("  r)")
            add("    echo '▶️  Recursive mode selected.'")
            add("    # TODO: recursive 작업 추가")
            add("    ;;")

            add("  *) echo '⚠️  Invalid choice: \$mode' && exit 1 ;;")
            add("esac")
        }

        return listOf("patch.sh" to script)
    }

}