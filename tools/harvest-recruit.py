#!/usr/bin/env python3
# 从搜索列表里筛「机器人赛道·招聘方」帖子(排除个人求职/资讯/测评),供定向进详情+进作者主页。
#   用法: python3 harvest-recruit.py harvest-out/notes.ndjson [-o out_dir] [--no-llm]
# 产物:
#   recruit.csv       招聘帖:公司/岗位/作者/note_id/xsec_token/命中词/标题
#   recruit_uids.txt  招聘方作者 userid(去重) -> 喂 harvest-drive.sh -u 拿简介/联系方式
#   recruit_notes.csv note_id,xsec_token       -> 喂 harvest-drive.sh -d 进帖子详情
# LLM 走本地 gateway(同 harvest-companies.py 的环境变量)。--no-llm 只用规则初筛。
import json, re, sys, csv, os, gzip, time, argparse, urllib.request

BASE  = os.environ.get('XHS_LLM_BASE',  'http://127.0.0.1:4000')
KEY   = os.environ.get('XHS_LLM_KEY',   'sk-local-claude-code-gateway')
MODEL = os.environ.get('XHS_LLM_MODEL', 'glm-5.1')

# 招聘方信号(宽松初筛,召回优先);求职/资讯方后面交给 LLM 排除
HINTS = ['招聘', '内推', '急招', '校招', '社招', '扩招', '招人', '招募', '诚聘', '求贤',
         '岗位', 'HC', 'headcount', 'base', '投递', '简历', 'offer', 'JD', '应届',
         '实习生', '实习', '岗', 'hiring', 'join us', 'career', '欢迎投递', '内推码']

SYS = (
    "你在分析小红书笔记(机器人/灵巧手/具身智能赛道的招聘信息)。对每条输出:\n"
    "- is_hiring(bool): 是否在招人(放岗位/内推/招聘/欢迎投递)。个人求职/资讯科普/测评=false。\n"
    "- poster_type: 发帖人身份,取值之一: 公司官方 | 员工 | 中介猎头 | 转发资讯 | 求职辅导 | 个人\n"
    "- company: 招聘的**真实公司名**(哪怕发帖人是中介/转发,也要抽出帖子里提到的那家公司;不确定给\"\")\n"
    "- roles: 岗位名数组\n"
    "输入多条笔记,每条前有编号。严格输出 JSON:\n"
    "{\"编号\":{\"is_hiring\":true,\"poster_type\":\"公司官方\",\"company\":\"X\",\"roles\":[\"A\"]},...},只输出JSON。"
)

# 触达路径:发帖人本人是否可作为触达对象,还是要绕到公司
def reach_path(poster_type, company):
    if poster_type in ('公司官方', '员工'):
        return '发帖人=公司方,可直接触达发帖人'
    if poster_type in ('中介猎头', '转发资讯'):
        return f'发帖人是二手,走站外联系公司:{company or "?"}'
    return '发帖人身份存疑,优先站外核实公司'

def http_json(url, payload, headers):
    req = urllib.request.Request(url, data=json.dumps(payload).encode('utf-8'), headers=headers, method='POST')
    with urllib.request.urlopen(req, timeout=120) as r:
        raw = r.read()
        if r.headers.get('Content-Encoding') == 'gzip':
            raw = gzip.decompress(raw)
    return json.loads(raw)

def call_llm(prompt):
    resp = http_json(BASE.rstrip('/') + '/v1/messages', {
        'model': MODEL, 'max_tokens': 1500, 'system': SYS,
        'messages': [{'role': 'user', 'content': prompt}],
    }, {'Authorization': 'Bearer ' + KEY, 'anthropic-version': '2023-06-01',
        'Content-Type': 'application/json'})
    txt = ''.join(b.get('text', '') for b in resp.get('content', []) if b.get('type') == 'text')
    m = re.search(r'\{.*\}', txt, re.S)
    try:
        return json.loads(m.group(0)) if m else {}
    except Exception:
        return {}

def iter_notes(path):
    seen = set()
    for ln in open(path, encoding='utf-8'):
        ln = ln.strip()
        if not ln:
            continue
        try:
            rec = json.loads(ln)
            if '/search/notes' not in rec.get('url', ''):
                continue
            body = json.loads(rec['body'])
        except Exception:
            continue
        for it in (body.get('data') or {}).get('items') or []:
            note = it.get('note') or it
            if not isinstance(note, dict):
                continue
            nid = note.get('id')
            if not nid or nid in seen:
                continue
            seen.add(nid)
            u = note.get('user') or {}
            yield {'id': nid, 'title': (note.get('title') or '').strip(),
                   'desc': (note.get('desc') or '').strip(),
                   'xsec_token': note.get('xsec_token', ''),
                   'red_id': u.get('red_id', ''), 'nick': u.get('nickname', ''),
                   'userid': u.get('userid', '')}

def hit_words(text):
    return [w for w in HINTS if w.lower() in text.lower()]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('ndjson'); ap.add_argument('-o', '--out', default='.')
    ap.add_argument('--no-llm', action='store_true'); ap.add_argument('--batch', type=int, default=12)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    notes = list(iter_notes(a.ndjson))
    cand = [(n, hit_words(n['title'] + ' ' + n['desc'])) for n in notes]
    cand = [(n, w) for n, w in cand if w]
    print(f"笔记 {len(notes)} 条,规则初筛招聘候选 {len(cand)} 条")

    rows = []
    if a.no_llm:
        for n, w in cand:
            rows.append({'is_hiring': True, 'company': '', 'roles': '', 'hit': ' '.join(w), **n})
    else:
        for i in range(0, len(cand), a.batch):
            batch = cand[i:i + a.batch]
            lines = [f"[{j}] {n['title']} || {n['desc'][:200]}" for j, (n, _) in enumerate(batch)]
            try:
                res = call_llm('\n'.join(lines))
            except Exception as e:
                print(f"  batch {i} LLM 失败: {e}", file=sys.stderr); res = {}
            for j, (n, w) in enumerate(batch):
                r = res.get(str(j)) or {}
                if r.get('is_hiring'):
                    pt = str(r.get('poster_type', '') or '')
                    co = str(r.get('company', '') or '')
                    rows.append({'is_hiring': True, 'poster_type': pt, 'company': co,
                                 'roles': ' / '.join(r.get('roles', []) or []),
                                 'reach': reach_path(pt, co),
                                 'hit': ' '.join(w), **n})
            print(f"  {min(i+a.batch,len(cand))}/{len(cand)} 判定,累计招聘帖 {len(rows)}")
            time.sleep(0.3)

    cols = ['company', 'poster_type', 'reach', 'roles', 'nick', 'red_id', 'userid', 'hit', 'title', 'desc', 'id', 'xsec_token']
    with open(os.path.join(a.out, 'recruit.csv'), 'w', newline='', encoding='utf-8-sig') as f:
        w = csv.DictWriter(f, fieldnames=cols, extrasaction='ignore')
        w.writeheader()
        for r in rows:
            w.writerow(r)
    uids = sorted({r['userid'] for r in rows if r['userid']})
    open(os.path.join(a.out, 'recruit_uids.txt'), 'w').write('\n'.join(uids))
    with open(os.path.join(a.out, 'recruit_notes.csv'), 'w', newline='', encoding='utf-8') as f:
        w = csv.writer(f); w.writerow(['note_id', 'xsec_token'])
        for r in rows:
            w.writerow([r['id'], r['xsec_token']])
    # 待站外补全的公司名单(去重)
    comps = sorted({r['company'] for r in rows if r['company']})
    open(os.path.join(a.out, 'companies_to_enrich.txt'), 'w').write('\n'.join(comps))

    from collections import Counter
    byt = Counter(r['poster_type'] or '未知' for r in rows)
    print(f"\n招聘帖 {len(rows)} 条 / 招聘方账号 {len(uids)} 个 / 待补全公司 {len(comps)} 家")
    print("发帖人类型:", dict(byt))
    for r in rows[:15]:
        print(f"  [{r['company'] or '?'}|{r['poster_type']}] {r['roles'][:24]:24} @{r['nick']}")
    print(f"\n产物: recruit.csv / recruit_uids.txt / recruit_notes.csv / companies_to_enrich.txt @ {a.out}")

if __name__ == '__main__':
    main()
