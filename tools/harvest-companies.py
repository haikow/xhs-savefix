#!/usr/bin/env python3
# 从采集到的笔记里用 LLM 抽「机器人/灵巧手赛道」的公司/机构名,跨笔记聚合去噪出清单。
#   用法: python3 harvest-companies.py harvest-out/notes.ndjson [-o out_dir] [--batch 10]
# 产物: companies.csv  公司名 | 提及次数 | 关联账号数 | 示例标题 | 关联红书号(供账号滚雪球)
#
# LLM 走本地 litellm gateway 的 anthropic /v1/messages(默认),可用环境变量覆盖:
#   XHS_LLM_BASE (默认 http://127.0.0.1:4000)
#   XHS_LLM_KEY  (默认 sk-local-claude-code-gateway)
#   XHS_LLM_MODEL(默认 glm-5.1)
import json, re, sys, csv, os, gzip, time, argparse, urllib.request
from collections import defaultdict

BASE  = os.environ.get('XHS_LLM_BASE',  'http://127.0.0.1:4000')
KEY   = os.environ.get('XHS_LLM_KEY',   'sk-local-claude-code-gateway')
MODEL = os.environ.get('XHS_LLM_MODEL', 'glm-5.1')

SYS = (
    "你在从小红书笔记里识别『机器人/灵巧手/具身智能赛道』的公司、初创、机构或高校实验室名称,用于商务拓展。\n"
    "规则:\n"
    "- 只抽真实的公司/品牌/机构/研究院/实验室名(含不知名初创);按原文写法给出,简称也算。\n"
    "- 排除:泛称(机器人/人形机器人/具身智能/黑科技/灵巧手)、产品型号(如 U1/G1/H1)、人名、"
    "平台名(小红书/抖音/B站)、展会与赛事名(世界机器人大会等)。\n"
    "输入是多条笔记,每条前有编号。严格输出 JSON 对象:{\"编号\":[\"公司A\",...], ...},没有就空数组,只输出JSON。"
)

def http_json(url, payload, headers):
    data = json.dumps(payload).encode('utf-8')
    req = urllib.request.Request(url, data=data, headers=headers, method='POST')
    with urllib.request.urlopen(req, timeout=120) as r:
        raw = r.read()
        if r.headers.get('Content-Encoding') == 'gzip':
            raw = gzip.decompress(raw)
    return json.loads(raw)

def call_llm(prompt):
    resp = http_json(BASE.rstrip('/') + '/v1/messages', {
        'model': MODEL, 'max_tokens': 1024, 'system': SYS,
        'messages': [{'role': 'user', 'content': prompt}],
    }, {
        'Authorization': 'Bearer ' + KEY,
        'anthropic-version': '2023-06-01',
        'Content-Type': 'application/json',
    })
    # 取 type==text 的块(跳过 thinking)
    txt = ''.join(b.get('text', '') for b in resp.get('content', []) if b.get('type') == 'text')
    m = re.search(r'\{.*\}', txt, re.S)
    if not m:
        return {}
    try:
        return json.loads(m.group(0))
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
            title = (note.get('title') or '').strip()
            desc = (note.get('desc') or '').strip()
            if not (title or desc):
                continue
            u = note.get('user') or {}
            yield {'id': nid, 'title': title, 'desc': desc,
                   'red_id': u.get('red_id', ''), 'nick': u.get('nickname', '')}

def merge_alias(counts):
    # 互为子串的公司名归并到最长者(宇树/宇树科技 -> 宇树科技)。
    names = sorted(counts, key=len, reverse=True)
    canon = {}
    for n in names:
        hit = next((c for c in canon if n in c), None)
        canon.setdefault(hit or n, [])
        canon[hit or n].append(n)
    return canon

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('ndjson')
    ap.add_argument('-o', '--out', default='.')
    ap.add_argument('--batch', type=int, default=10)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    notes = list(iter_notes(a.ndjson))
    print(f"待抽取笔记 {len(notes)} 条,batch={a.batch},model={MODEL}")

    comp_notes = defaultdict(list)   # company -> [note...]
    for i in range(0, len(notes), a.batch):
        batch = notes[i:i + a.batch]
        lines = [f"[{j}] {n['title']} || {n['desc'][:180]}" for j, n in enumerate(batch)]
        try:
            res = call_llm('\n'.join(lines))
        except Exception as e:
            print(f"  batch {i} LLM 失败: {e}", file=sys.stderr)
            res = {}
        for j, n in enumerate(batch):
            for c in res.get(str(j), []) or []:
                c = str(c).strip()
                if c:
                    comp_notes[c].append(n)
        print(f"  {i+len(batch)}/{len(notes)} 已处理,累计公司 {len(comp_notes)}")
        time.sleep(0.3)

    # 别名归并
    raw_counts = {c: len(v) for c, v in comp_notes.items()}
    canon = merge_alias(raw_counts)

    rows = []
    for c, aliases in canon.items():
        allnotes = []
        for al in aliases:
            allnotes += comp_notes[al]
        red_ids = sorted({n['red_id'] for n in allnotes if n['red_id']})
        titles = [n['title'] for n in allnotes if n['title']][:2]
        rows.append({
            'company': c,
            'aliases': ' / '.join(sorted(set(aliases) - {c})),
            'mentions': len(allnotes),
            'accounts': len(red_ids),
            'example_titles': ' ｜ '.join(titles),
            'sample_red_ids': ' '.join(red_ids[:8]),
        })
    rows.sort(key=lambda r: (-r['mentions'], -r['accounts']))

    outp = os.path.join(a.out, 'companies.csv')
    with open(outp, 'w', newline='', encoding='utf-8-sig') as f:
        w = csv.DictWriter(f, fieldnames=['company', 'aliases', 'mentions', 'accounts',
                                          'example_titles', 'sample_red_ids'])
        w.writeheader(); w.writerows(rows)
    print(f"公司 {len(rows)} 家 -> {outp}")
    for r in rows[:15]:
        print(f"  {r['mentions']:>2}× {r['company']}"
              + (f"  (别名 {r['aliases']})" if r['aliases'] else ''))

if __name__ == '__main__':
    main()
