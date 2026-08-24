#!/usr/bin/env bash
# 一键编排采集。两种模式:
#   full   : 广度发现——搜索所有关键词并深翻页,出公司清单(不逐帖深挖,快)
#   intent : 带意图——搜索后先判招聘意图,只对招聘帖进详情/主页/图片OCR挖联系方式,出 leads 总表
# 用法:
#   ./harvest.sh -s <serial> -m full   [-n 30] [keywords.txt]
#   ./harvest.sh -s <serial> -m intent [-n 15] [keywords.txt]
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; cd "$HERE"
OUT="$HERE/harvest-out"; ND="$OUT/notes.ndjson"
SERIAL=""; MODE=""; PAGES=""; KW="keywords.txt"
while getopts "s:m:n:" o; do case "$o" in
  s) SERIAL="$OPTARG";; m) MODE="$OPTARG";; n) PAGES="$OPTARG";; esac; done
shift $((OPTIND-1)); [[ $# -ge 1 ]] && KW="$1"
[[ -n "$SERIAL" && -n "$MODE" ]] || { echo "用法: ./harvest.sh -s <serial> -m full|intent [-n 页数] [keywords.txt]" >&2; exit 1; }
D() { python3 harvest-extract.py "$ND" -o "$OUT" >/dev/null; }

case "$MODE" in
  full)
    N="${PAGES:-30}"
    echo "===== FULL 广度发现: $KW × ${N}页 ====="
    ./harvest-drive.sh -s "$SERIAL" -n "$N" "$KW"
    python3 harvest-extract.py   "$ND" -o "$OUT"
    python3 harvest-companies.py "$ND" -o "$OUT"
    echo "完成 -> $OUT/companies.csv  accounts.csv  hashtags.csv"
    ;;
  intent)
    N="${PAGES:-15}"
    echo "===== INTENT 带意图: $KW × ${N}页 ====="
    echo "--- 1/6 搜索列表 ---"
    ./harvest-drive.sh -s "$SERIAL" -n "$N" "$KW"
    D
    echo "--- 2/6 判招聘意图(只留招聘帖) ---"
    python3 harvest-recruit.py "$ND" -o "$OUT"
    if [[ ! -s "$OUT/recruit_notes.csv" ]]; then echo "没有招聘帖,结束"; exit 0; fi
    echo "--- 3/6 只进招聘帖详情(全文+评论) ---"
    ./harvest-drive.sh -s "$SERIAL" -d "$OUT/recruit_notes.csv"
    echo "--- 4/6 进招聘方主页(简介/邮箱) ---"
    [[ -s "$OUT/recruit_uids.txt" ]] && ./harvest-drive.sh -s "$SERIAL" -u "$OUT/recruit_uids.txt" || true
    echo "--- 5/6 只OCR招聘帖图片(邮箱+二维码) ---"
    python3 harvest-ocr.py "$ND" -s "$SERIAL" --notes "$OUT/recruit_notes.csv" -o "$OUT" || true
    echo "--- 6/6 汇总(站外补全 + 合并 leads) ---"
    D
    python3 harvest-emailfind.py "$OUT/companies_to_enrich.txt" -o "$OUT" || true
    python3 harvest-leads.py -o "$OUT" -s "$SERIAL"
    echo "完成 -> $OUT/leads.csv (每行:公司|类型|触达|岗位|三路邮箱|微信|二维码|一键打开)"
    ;;
  *) echo "未知模式: $MODE (只支持 full|intent)" >&2; exit 1;;
esac
