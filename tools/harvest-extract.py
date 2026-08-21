#!/usr/bin/env python3
# 把采集到的 ndjson 解析成公司/账号候选。
#   用法: python3 harvest-extract.py harvest-out/notes.ndjson [-o out_dir]
# 产物:
#   notes.csv    每条笔记(标题/正文/作者/红书号/企业号标记/话题标签)
#   accounts.csv 按红书号去重的账号(标企业号 + 出现次数),疑似公司官号排前
#   hashtags.csv 话题标签词频(品牌/公司名线索),高频在前
#   emails.txt   从标题/正文正则抽到的邮箱(聊胜于无,主力靠站外补全)
import json, re, sys, csv, os, argparse
from collections import defaultdict, Counter

# 小红书正文话题多为开放式 "#优必选 "(空格结尾、不闭合),也有 "#xxx[话题]#"。
HASHTAG = re.compile(r'#([^#\[\]\s]{1,30})')
EMAIL   = re.compile(r'[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}')
# 简介里常见联系方式:微信 / 官网 / 商务
WECHAT  = re.compile(r'(?:微信|vx|VX|wx|WX|weixin|WeChat|v:|V:)[\s:：]*([A-Za-z0-9_\-]{5,25})')
URLPAT  = re.compile(r'(?:https?://|www\.)[A-Za-z0-9.\-/]+')

def iter_notes(path):
    for ln in open(path, encoding='utf-8'):
        ln = ln.strip()
        if not ln:
            continue
        try:
            rec = json.loads(ln)
        except Exception:
            continue
        if '/search/notes' not in rec.get('url', ''):
            continue
        try:
            body = json.loads(rec['body'])
        except Exception:
            continue
        items = (body.get('data') or {}).get('items') or []
        for it in items:
            note = it.get('note') or it
            if isinstance(note, dict) and (note.get('title') or note.get('desc')):
                yield note

def iter_profiles(path):
    seen = set()
    for ln in open(path, encoding='utf-8'):
        ln = ln.strip()
        if not ln:
            continue
        try:
            rec = json.loads(ln)
            if '/user/info' not in rec.get('url', ''):
                continue
            body = json.loads(rec['body'])
        except Exception:
            continue
        d = body.get('data') or body
        uid = d.get('userid')
        if not uid or uid in seen:
            continue
        seen.add(uid)
        desc = d.get('desc') or (d.get('user_desc_info') or {}).get('desc') or ''
        yield {
            'red_id': d.get('red_id', ''), 'nickname': d.get('nickname', ''),
            'userid': uid, 'desc': desc.strip(),
            'ip_location': d.get('ip_location', ''),
            'fans': d.get('fans', ''), 'posted': (d.get('note_num_stat') or {}).get('posted', ''),
            'verified': bool(d.get('red_official_verified')),
            'verify_type': d.get('red_official_verify_type', 0),
            'role_type': d.get('user_role_type', ''),
        }


def iter_comments(path):
    for ln in open(path, encoding='utf-8'):
        ln = ln.strip()
        if not ln:
            continue
        try:
            rec = json.loads(ln)
            if '/note/comment/list' not in rec.get('url', ''):
                continue
            body = json.loads(rec['body'])
        except Exception:
            continue
        m = re.search(r'note_id=([0-9a-f]+)', rec.get('url', ''))
        nid = m.group(1) if m else ''
        for c in (body.get('data') or {}).get('comments') or []:
            txt = c.get('content', '') or ''
            if not txt:
                continue
            u = c.get('user_info') or c.get('user') or {}
            yield {'note_id': nid, 'commenter': u.get('nickname', ''),
                   'commenter_id': u.get('user_id', '') or u.get('userid', ''),
                   'content': txt}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('ndjson')
    ap.add_argument('-o', '--out', default='.')
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    notes_rows = []
    acc = {}                       # red_id -> aggregate
    tags = Counter()
    emails = set()
    seen = set()                   # 笔记去重(翻页会重复)

    for n in iter_notes(a.ndjson):
        nid = n.get('id')
        if nid in seen:
            continue
        seen.add(nid)
        title = (n.get('title') or '').strip()
        desc  = (n.get('desc') or '').strip()
        u = n.get('user') or {}
        red_id = u.get('red_id', '')
        nick = u.get('nickname', '')
        vtype = u.get('red_official_verify_type', 0)
        verified = bool(u.get('red_official_verified'))
        htags = HASHTAG.findall(title + ' ' + desc)
        for t in htags:
            tags[t.strip()] += 1
        for e in EMAIL.findall(title + ' ' + desc):
            emails.add(e)

        notes_rows.append({
            'title': title, 'desc': desc[:300], 'nickname': nick,
            'red_id': red_id, 'verified': verified, 'verify_type': vtype,
            'hashtags': ' | '.join(htags),
            'note_id': nid, 'xsec_token': n.get('xsec_token', ''),
        })
        if red_id:
            e = acc.setdefault(red_id, {
                'red_id': red_id, 'nickname': nick, 'userid': u.get('userid', ''),
                'verified': verified, 'verify_type': vtype, 'notes': 0})
            e['notes'] += 1
            if verified:
                e['verified'] = True

    # notes.csv
    with open(os.path.join(a.out, 'notes.csv'), 'w', newline='', encoding='utf-8-sig') as f:
        w = csv.DictWriter(f, fieldnames=list(notes_rows[0].keys()) if notes_rows else
                           ['title','desc','nickname','red_id','verified','verify_type','hashtags','note_id','xsec_token'])
        w.writeheader(); w.writerows(notes_rows)

    # accounts.csv —— 企业号优先,再按出现次数
    accs = sorted(acc.values(), key=lambda x: (not x['verified'], -x['notes']))
    with open(os.path.join(a.out, 'accounts.csv'), 'w', newline='', encoding='utf-8-sig') as f:
        w = csv.DictWriter(f, fieldnames=['red_id','nickname','userid','verified','verify_type','notes'])
        w.writeheader(); w.writerows(accs)

    # hashtags.csv
    with open(os.path.join(a.out, 'hashtags.csv'), 'w', newline='', encoding='utf-8-sig') as f:
        w = csv.writer(f); w.writerow(['hashtag', 'count'])
        for t, c in tags.most_common():
            w.writerow([t, c])

    # profiles.csv —— 用户主页简介(公司归属 + 联系方式主力来源)
    prof_rows = []
    for p in iter_profiles(a.ndjson):
        blob = p['desc']
        pe = EMAIL.findall(blob)
        emails.update(pe)
        pw = WECHAT.findall(blob)
        pu = URLPAT.findall(blob)
        prof_rows.append({**p,
                          'emails': ' '.join(sorted(set(pe))),
                          'wechat': ' '.join(sorted(set(pw))),
                          'urls': ' '.join(sorted(set(pu)))})
    # 有联系方式/企业号的排前
    prof_rows.sort(key=lambda r: (not (r['emails'] or r['wechat'] or r['urls']),
                                  not r['verified']))
    with open(os.path.join(a.out, 'profiles.csv'), 'w', newline='', encoding='utf-8-sig') as f:
        cols = ['red_id','nickname','userid','verified','verify_type','role_type',
                'ip_location','fans','posted','emails','wechat','urls','desc']
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader(); w.writerows(prof_rows)

    # comments.csv —— 评论区(公司名讨论 + 求内推/简历发我/联系方式)
    com_rows = []
    for c in iter_comments(a.ndjson):
        ce = EMAIL.findall(c['content'])
        cw = WECHAT.findall(c['content'])
        emails.update(ce)
        com_rows.append({**c, 'emails': ' '.join(sorted(set(ce))),
                         'wechat': ' '.join(sorted(set(cw)))})
    # 有联系方式的评论排前
    com_rows.sort(key=lambda r: not (r['emails'] or r['wechat']))
    with open(os.path.join(a.out, 'comments.csv'), 'w', newline='', encoding='utf-8-sig') as f:
        w = csv.DictWriter(f, fieldnames=['note_id', 'commenter', 'commenter_id',
                                          'emails', 'wechat', 'content'])
        w.writeheader(); w.writerows(com_rows)

    with open(os.path.join(a.out, 'emails.txt'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(sorted(emails)))

    print(f"笔记 {len(notes_rows)} 条 / 账号 {len(accs)} 个 "
          f"(企业号 {sum(1 for x in accs if x['verified'])}) / "
          f"话题标签 {len(tags)} / 主页 {len(prof_rows)} / 评论 {len(com_rows)} / 邮箱 {len(emails)}")
    print("产物:", ', '.join(['notes.csv','accounts.csv','hashtags.csv','profiles.csv',
                              'comments.csv','emails.txt']), "@", a.out)

if __name__ == '__main__':
    main()
