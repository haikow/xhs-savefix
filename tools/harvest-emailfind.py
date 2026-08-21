#!/usr/bin/env python3
# 站外公司邮箱补全:公司名 -> LLM 提议官网域名 -> DNS/MX 验证 -> hr@/recruit@ 候选。
#   用法: python3 harvest-emailfind.py companies_to_enrich.txt [-o out_dir]
# 产物: emails_candidates.csv  公司|域名|MX有效|候选邮箱|说明
# 诚实边界:
#   - 域名是 LLM 提议(可能错),但过不了 MX 的会被丢弃;MX 有效只代表该域名收邮件,不代表邮箱存在。
#   - 候选邮箱是"按惯例猜的",能不能投递要发信时看退信;不做 SMTP 探活(易被反垃圾封 IP)。
import json, re, sys, csv, os, gzip, time, argparse, subprocess, urllib.request

BASE  = os.environ.get('XHS_LLM_BASE',  'http://127.0.0.1:4000')
KEY   = os.environ.get('XHS_LLM_KEY',   'sk-local-claude-code-gateway')
MODEL = os.environ.get('XHS_LLM_MODEL', 'glm-5.1')

# HR/招聘常用前缀(优先级从高到低)
LOCALPARTS = ['hr', 'recruit', 'recruitment', 'jobs', 'job', 'career', 'careers',
              'campus', 'talent', 'contact', 'bd', 'info']

SYS = (
    "给你若干机器人/具身智能/灵巧手赛道的公司名。请为每家给出**最可能的官网主域名**(1-3 个候选,"
    "只要主域名如 example.com,不要 http/路径/子域)。不知道就给空数组,**不要编造**。\n"
    "严格输出 JSON:{\"公司名\":[\"a.com\",\"b.cn\"],...},只输出JSON。"
)

def call_llm(prompt):
    req = urllib.request.Request(BASE.rstrip('/') + '/v1/messages',
        data=json.dumps({'model': MODEL, 'max_tokens': 1500, 'system': SYS,
                         'messages': [{'role': 'user', 'content': prompt}]}).encode(),
        headers={'Authorization': 'Bearer ' + KEY, 'anthropic-version': '2023-06-01',
                 'Content-Type': 'application/json'}, method='POST')
    with urllib.request.urlopen(req, timeout=120) as r:
        raw = r.read()
        if r.headers.get('Content-Encoding') == 'gzip':
            raw = gzip.decompress(raw)
    resp = json.loads(raw)
    txt = ''.join(b.get('text', '') for b in resp.get('content', []) if b.get('type') == 'text')
    m = re.search(r'\{.*\}', txt, re.S)
    return json.loads(m.group(0)) if m else {}

def mx_of(domain):
    try:
        out = subprocess.run(['dig', '+short', 'MX', domain], capture_output=True,
                             text=True, timeout=10).stdout.strip()
        if out:
            return [l.split()[-1].rstrip('.') for l in out.splitlines() if l.split()]
    except Exception:
        pass
    return []

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('companies'); ap.add_argument('-o', '--out', default='.')
    ap.add_argument('--batch', type=int, default=15)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    names = [l.strip() for l in open(a.companies, encoding='utf-8') if l.strip()]
    print(f"公司 {len(names)} 家,LLM 提议域名...")

    dom = {}
    for i in range(0, len(names), a.batch):
        batch = names[i:i + a.batch]
        try:
            res = call_llm('\n'.join(batch))
        except Exception as e:
            print(f"  LLM 失败: {e}", file=sys.stderr); res = {}
        for n in batch:
            dom[n] = res.get(n, []) or []
        time.sleep(0.3)

    rows = []
    for n in names:
        picked = None; mx = []
        for d in dom[n]:
            d = d.strip().lower().lstrip('.')
            if not re.match(r'^[a-z0-9.\-]+\.[a-z]{2,}$', d):
                continue
            mx = mx_of(d)
            if mx:
                picked = d; break
        if picked:
            cands = [f"{lp}@{picked}" for lp in LOCALPARTS[:6]]
            rows.append({'company': n, 'domain': picked, 'mx_ok': 'yes',
                         'candidates': ' '.join(cands), 'note': 'MX有效;邮箱按惯例猜,投递看退信'})
        else:
            guessed = ' / '.join(dom[n]) if dom[n] else ''
            rows.append({'company': n, 'domain': guessed, 'mx_ok': 'no',
                         'candidates': '', 'note': '域名未验证/需人工找官网'})
        print(f"  {n} -> {picked or (guessed or '?')} [{'MX' if picked else '未验'}]")

    outp = os.path.join(a.out, 'emails_candidates.csv')
    with open(outp, 'w', newline='', encoding='utf-8-sig') as f:
        w = csv.DictWriter(f, fieldnames=['company', 'domain', 'mx_ok', 'candidates', 'note'])
        w.writeheader(); w.writerows(rows)
    ok = sum(1 for r in rows if r['mx_ok'] == 'yes')
    print(f"\n{len(rows)} 家 / MX 验证通过 {ok} 家 -> {outp}")

if __name__ == '__main__':
    main()
