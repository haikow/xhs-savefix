#!/usr/bin/env bash
# 一键在手机打开某条笔记,自动解决 xsec_token 过期(跳"账号异常")问题。
# 原理:旧 token 写死在 deeplink 里会过期;改走作者主页(不需 token)拿当前有效入口再打开。
# 用法:
#   ./open-note.sh -s <serial> -n <note_id> [-u <userid>]
#     -u 省略时,自动从 harvest-out 的 recruit.csv / notes.csv 里按 note_id 反查作者
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$HERE/harvest-out"
SERIAL=""; NID=""; UID_IN=""
while getopts "s:n:u:" o; do case "$o" in
  s) SERIAL="$OPTARG";; n) NID="$OPTARG";; u) UID_IN="$OPTARG";; esac; done
[[ -n "$NID" ]] || { echo "需要 -n <note_id>" >&2; exit 1; }
ADB=(adb); [[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")
DIR=/data/data/com.xingin.xhs/files/xhs-harvest

# 1) 解析作者 userid
uid="$UID_IN"
if [[ -z "$uid" ]]; then
  pre="${NID:0:20}"
  for f in "$OUT"/recruit.csv "$OUT"/notes.csv; do
    [[ -f "$f" ]] || continue
    uid=$(python3 - "$f" "$pre" <<'PY'
import sys,csv
f,pre=sys.argv[1],sys.argv[2]
for row in csv.DictReader(open(f,encoding='utf-8-sig')):
    nid=row.get('id') or row.get('note_id') or ''
    if nid.startswith(pre) or pre.startswith(nid[:20]):
        print(row.get('userid','')); break
PY
)
    [[ -n "$uid" ]] && break
  done
fi
[[ -n "$uid" ]] || { echo "找不到作者 userid,请用 -u 指定" >&2; exit 1; }
echo "作者 userid: $uid"

# 2) 开作者主页(不需 token),触发 note/user/posted 拿新鲜入口
"${ADB[@]}" shell su -c "rm -f $DIR/OFF" 2>/dev/null || true
"${ADB[@]}" shell am start -a android.intent.action.VIEW \
  -d "xhsdiscover://user/$uid" com.xingin.xhs >/dev/null 2>&1 || true
sleep 5

# 3) 从 posted 里按前缀匹配,取完整 note_id + 新 token
"${ADB[@]}" shell su -c "cat $DIR/notes.ndjson" > /tmp/_open_posted.ndjson 2>/dev/null || true
read -r full tok < <(python3 - /tmp/_open_posted.ndjson "$NID" <<'PY'
import sys,json
path,nid=sys.argv[1],sys.argv[2]; pre=nid[:20]
best=None
for l in open(path,encoding='utf-8'):
    l=l.strip()
    if not l: continue
    try: r=json.loads(l); b=json.loads(r['body'])
    except: continue
    if 'user/posted' not in r.get('url',''): continue
    for n in (b.get('data') or {}).get('notes') or []:
        i=n.get('note_id') or n.get('id') or ''
        if i.startswith(pre) or pre.startswith(i[:20]):
            best=(i, n.get('xsec_token',''))
if best: print(best[0], best[1])
PY
)
[[ -n "${full:-}" ]] || { echo "作者主页第一页没有这条笔记(可能翻页在后);可先手动下滑主页再重试" >&2; exit 1; }
echo "完整 note_id: $full  新token: ${tok:0:16}…"

# 4) 用新入口打开笔记
"${ADB[@]}" shell am start -a android.intent.action.VIEW \
  -d "xhsdiscover://item/$full?xsec_token=$tok&xsec_source=pc_user&source=profile" com.xingin.xhs >/dev/null 2>&1 || true
sleep 4
act=$("${ADB[@]}" shell dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity | grep -oE 'com.xingin.xhs/[^ }]*' || true)
echo "前台: $act"
[[ "$act" == *NoteDetail* ]] && echo "✅ 已打开" || echo "⚠️ 未落在详情页,检查笔记是否被删/私密"
