#!/usr/bin/env bash
# 驱动小红书采集(需 root + 已装本模块并启用采集)。
#   关键词轮: 每个词持续拟人滑动直到 -n 目标页数 或 到底(连续无新增),再换词
#   主页轮  : -u userid文件, 逐个 deeplink 打开主页 -> user/info(简介/邮箱)+posted
# 用法:
#   ./harvest-drive.sh -s <serial> [-n 100] 关键词文件|关键词...
#   ./harvest-drive.sh -s <serial> -u uids.txt
# 产物: ./harvest-out/notes.ndjson
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$HERE/harvest-out"; mkdir -p "$OUT"

SERIAL=""; TARGET=100; UFILE=""; DFILE=""; MAXSW=0
while getopts "s:n:u:d:m:" o; do case "$o" in
  s) SERIAL="$OPTARG";; n) TARGET="$OPTARG";; u) UFILE="$OPTARG";;
  d) DFILE="$OPTARG";; m) MAXSW="$OPTARG";; esac; done
shift $((OPTIND-1))
[[ $MAXSW -gt 0 ]] || MAXSW=$((TARGET*4 + 20))   # 滑动次数硬上限,防到底后空转

ADB=(adb); [[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")
DIR=/data/data/com.xingin.xhs/files/xhs-harvest
NDJSON="$DIR/notes.ndjson"
urlenc() { python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$1"; }
rnd() { echo $(( RANDOM % ($2 - $1 + 1) + $1 )); }              # 整数 [$1,$2]
naprnd() { awk -v a="$1" -v b="$2" 'BEGIN{srand();printf "%.2f", a+rand()*(b-a)}'; }  # 浮点

SIZE=$("${ADB[@]}" shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}

# 拟人单次滑动:起止点抖动、速度抖动;偶尔两段带惯性、偶尔回看
human_swipe() {
  local x1 x2 y1 y2 dur
  x1=$(rnd $((W*40/100)) $((W*60/100)))
  x2=$(rnd $((W*40/100)) $((W*60/100)))
  y1=$(rnd $((H*68/100)) $((H*82/100)))
  y2=$(rnd $((H*18/100)) $((H*32/100)))
  if (( RANDOM % 8 == 0 )); then                 # ~12% 回看一小段
    "${ADB[@]}" shell input swipe "$x1" "$y2" "$x2" "$((y2+H*20/100))" "$(rnd 400 700)" >/dev/null 2>&1 || true
    sleep "$(naprnd 0.6 1.4)"; return
  fi
  if (( RANDOM % 5 == 0 )); then dur=$(rnd 200 340); else dur=$(rnd 450 1050); fi  # 偶尔快速 flick
  "${ADB[@]}" shell input swipe "$x1" "$y1" "$x2" "$y2" "$dur" >/dev/null 2>&1 || true
}

# 拟人停顿:多数 1.5-4s,~20% 长停(看内容)
human_pause() {
  if (( RANDOM % 10 < 2 )); then sleep "$(naprnd 5.5 11)"; else sleep "$(naprnd 1.4 3.8)"; fi
}

kw_pages() {  # 该关键词已落盘的 search/notes 响应数(≈页数)
  "${ADB[@]}" shell su -c "grep -c '$1' $NDJSON 2>/dev/null" 2>/dev/null | tr -dc 0-9 || echo 0
}

KWS=()
if [[ $# -ge 1 ]]; then
  if [[ -f "$1" ]]; then mapfile -t KWS < <(grep -vE '^\s*(#|$)' "$1"); else KWS=("$@"); fi
fi
[[ ${#KWS[@]} -gt 0 || -n "$UFILE" || -n "$DFILE" ]] || { echo "需要关键词 或 -u uids.txt 或 -d notes.csv" >&2; exit 1; }

"${ADB[@]}" shell su -c "rm -f /sdcard/xhs-harvest/OFF $DIR/OFF $DIR/PROBE" 2>/dev/null || true

# ===== 关键词轮 =====
for kw in "${KWS[@]}"; do
  enc=$(urlenc "$kw")
  echo "== [$kw] 目标 $TARGET 页,上限 $MAXSW 滑 =="
  "${ADB[@]}" shell am start -a android.intent.action.VIEW \
    -d "xhsdiscover://search/result?keyword=$enc" com.xingin.xhs >/dev/null 2>&1 || true
  sleep "$(naprnd 3.5 5.5)"
  base=$(kw_pages "keyword=$enc"); : "${base:=0}"
  sw=0; stall=0; last=$base
  while (( sw < MAXSW )); do
    human_swipe; human_pause; sw=$((sw+1))
    if (( sw % 5 == 0 )); then
      cur=$(kw_pages "keyword=$enc"); : "${cur:=0}"
      got=$((cur - base))
      printf "\r   滑 %3d 次 / 已 %3d 页" "$sw" "$got"
      (( got >= TARGET )) && { echo "  -> 达标"; break; }
      if (( cur == last )); then
        stall=$((stall+1))
        (( stall >= 4 )) && { echo "  -> 到底(连续无新增)"; break; }
      else stall=0; last=$cur; fi
    fi
  done
  echo ""
  # 每词结束增量导出一次,中断也不丢
  "${ADB[@]}" shell su -c "cat $NDJSON" > "$OUT/notes.ndjson" 2>/dev/null || true
  sleep "$(naprnd 8 22)"   # 词间长休息,拟人
done

# ===== 帖子详情轮(定向:只进筛出的招聘帖,拿全文+@提及) =====
# DFILE 为 recruit_notes.csv: 首行表头 note_id,xsec_token
if [[ -n "$DFILE" ]]; then
  n=$(( $(wc -l < "$DFILE") - 1 )); echo "== 进 $n 个帖子详情 =="
  tail -n +2 "$DFILE" | while IFS=, read -r nid tok; do
    [[ -n "$nid" ]] || continue
    "${ADB[@]}" shell am start -a android.intent.action.VIEW \
      -d "xhsdiscover://item/$nid?xsec_token=$tok&xsec_source=app_search&source=search_result" \
      com.xingin.xhs >/dev/null 2>&1 || true
    sleep "$(naprnd 3 4.5)"
    # 下滑触发评论加载(v5/note/comment/list),拟人
    for _ in 1 2 3 4; do human_swipe; sleep "$(naprnd 1.2 2.6)"; done
  done
  "${ADB[@]}" shell su -c "cat $NDJSON" > "$OUT/notes.ndjson" 2>/dev/null || true
fi

# ===== 主页轮 =====
if [[ -n "$UFILE" ]]; then
  mapfile -t UIDS < <(grep -vE '^\s*(#|$)' "$UFILE")
  echo "== 遍历 ${#UIDS[@]} 个主页 =="
  for uid in "${UIDS[@]}"; do
    "${ADB[@]}" shell am start -a android.intent.action.VIEW \
      -d "xhsdiscover://user/$uid" com.xingin.xhs >/dev/null 2>&1 || true
    sleep "$(naprnd 2.5 4.5)"
  done
fi

"${ADB[@]}" shell su -c "cat $NDJSON" > "$OUT/notes.ndjson" 2>/dev/null || true
echo "OK -> $OUT/notes.ndjson  ($(wc -l < "$OUT/notes.ndjson") 行)"
