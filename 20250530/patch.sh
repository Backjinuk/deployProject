#!/usr/bin/env bash
set -euo pipefail

ROOT="/home/bjw/deployProject"
DATE="$(date +'%Y%m%d')"
STAGING_DIR="$HOME/$DATE"

cat << 'BANNER'
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
BANNER
changed_files=(
  'build/classes/kotlin/main/com/deployProject/cli/infoCli/SvnInfoCli$svnCliExecution$1$1.class'
  'build/classes/kotlin/main/com/deployProject/cli/utilCli/GitUtil$addZipEntry$1$1.class'
  'build/classes/kotlin/main/com/deployProject/cli/infoCli/GitInfoCli$gitCliExecution$1$1$1.class'
  'build/classes/kotlin/main/com/deployProject/cli/DeployCliScript.class'
  'build/classes/kotlin/main/com/deployProject/deploy/repository/DeployRepository.class'
  '20250530/patch.sh'
  '20250530/deployProject/src/main/front/src/component/path/PathConverter.tsx'
  'build/classes/kotlin/main/com/deployProject/cli/ExtractionLauncher.class'
  'build/classes/kotlin/main/com/deployProject/cli/utilCli/JarCreator$createJar$1$1$1.class'
  'build/classes/kotlin/main/com/deployProject/deploy/repository/DeployRepositoryImpl.class'
  'src/main/front/src/component/path/PathConverter.tsx'
  'build/classes/kotlin/main/com/deployProject/deploy/service/ExtractionService$zipDirectory$1$1.class'
  '20250530/deployProject/build/classes/kotlin/test/com/deployProject/util/GitInfoCliTest.class'
  '20250530/deployProject/build/classes/kotlin/test/com/deployProject/util/SvnInfoCliCreateTest.class'
  'build/classes/kotlin/main/com/deployProject/deploy/service/DeployService.class'
  'src/main/front/src/component/path/PathConverter.tsx'
  'src/main/front/src/component/login/NickNameModal.tsx'
  'src/main/front/src/component/path/ConverterMain.tsx'
  'src/main/front/src/component/path/StieSelector.tsx'
  'src/main/front/src/component/pathModal/PathAddModal.tsx'
  'src/main/front/src/component/pathModal/PathModalMain.tsx'
  'src/main/front/src/component/pathModal/PathUpdateModal.tsx'
  'build/classes/kotlin/test/com/deployProject/util/SvnInfoCliTest$listFilesModifiedBetween$2.class'
)

echo 'Select mode:'
echo '  [b] backup only   [d] deploy (backup + deploy)   [r] recursive'
read -p 'Enter choice (b/d/r): ' mode
case "${mode,,}" in
  b)
    echo '▶️  Backup start'
    for f in "${changed_files[@]}"; do
      src="$ROOT/$f"
      if [ ! -f "$src" ]; then echo "[WARN] '$f' not found under $ROOT" >&2; continue; fi
      dst_dir=$(dirname "$src")
      base=$(basename "$src")
      cp "$src" "$dst_dir/${base}$DATE"
      echo "  backed up: $base -> $dst_dir/${base}$DATE"
    done
    echo '✅  Backup complete.'
    ;;
  d)
    echo '▶️  Backup before deploy'
    for f in "${changed_files[@]}"; do
      src="$ROOT/$f"
      if [ -f "$src" ]; then
        dst_dir=$(dirname "$src")
        base=$(basename "$src")
        cp "$src" "$dst_dir/${base}$DATE"
        echo "  backed up: $base -> $dst_dir/${base}$DATE"
      fi
    done
    echo '✅  Backup completed.'

    echo '▶️  Pending deploy operations:'
    for f in "${changed_files[@]}"; do
      base=$(basename "$f")
      echo "  $base -> $ROOT/$f$DATE"
    done

    echo '▶️  Top-level dirs by extension:'
    declare -A extDirs=()
    for e in $(printf '%s\n' "${changed_files[@]}" | sed -E 's/.*\.([^.]+)$/\1/' | sort -u); do
      # 해당 확장자 파일들 경로만 추출
      paths=()
      for f in "${changed_files[@]}"; do
        [[ "$f" == *.$e ]] && paths+=("$f")
      done
      # 공통 상위 디렉터리 계산
      common=$(dirname "${paths[0]}")
      for p in "${paths[@]:1}"; do
        while [[ "$p" != "$common/*" ]]; do common=${common%/*}; done
      done
      extDirs["$e"]="$common"
    done
    for e in "${!extDirs[@]}"; do
      echo "  .$e -> $ROOT/${extDirs[$e]}"
    done
    echo
    read -p 'Proceed with deploy? [y/N]: ' ans
    case "${ans,,}" in y|yes) ;; *) echo '❌  Deploy canceled.'; exit 1 ;; esac

    echo '▶️  Deploying...' 
    for f in "${changed_files[@]}"; do
      src="$STAGING_DIR/$f"
      dst="$ROOT/$f"
      mkdir -p "$(dirname "$dst")"
      cp "$src" "$dst"
      echo "  deployed: $(basename "$f") -> $dst"
    done
    echo '✅  Deploy complete.'
    ;;
  r)
    echo '▶️  Recursive mode selected.'
    # TODO: recursive 작업 추가
    ;;
  *) echo '⚠️  Invalid choice: $mode' && exit 1 ;;
esac