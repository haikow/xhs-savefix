#!/usr/bin/env python3
# 汇总器:把各阶段产出合并成一张可直接投递的招聘线索总表 leads.csv。
#   读 out 目录下(有哪个用哪个): recruit.csv / profiles.csv / comments.csv /
#     image_contacts.csv / emails_candidates.csv
#   用法: python3 harvest-leads.py -o harvest-out [-s <serial 用于生成打开命令>]
# 每行=一条招聘帖,把邮箱分三路(正文简介评论 / 图片OCR / 站外候选)+微信+二维码+一键打开命令。
import csv, os, argparse
from collections import defaultdict

def load(path):
    return list(csv.DictReader(open(path, encoding='utf-8-sig'))) if os.path.exists(path) else []

def key20(nid):  # 搜索给20位/主页给24位,统一按前20位对齐
    return (nid or '')[:20]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('-o', '--out', default='.')
    ap.add_argument('-s', '--serial', default='<serial>')
    a = ap.parse_args()
    o = a.out

    recruit = load(os.path.join(o, 'recruit.csv'))
    profiles = {r['userid']: r for r in load(os.path.join(o, 'profiles.csv')) if r.get('userid')}
    images = {key20(r['note_id']): r for r in load(os.path.join(o, 'image_contacts.csv'))}
    emailcand = {r['company']: r for r in load(os.path.join(o, 'emails_candidates.csv')) if r.get('company')}
    comm = defaultdict(set)
    for r in load(os.path.join(o, 'comments.csv')):
        if r.get('emails'):
            comm[key20(r['note_id'])].update(r['emails'].split())

    rows = []
    for r in recruit:
        nid = r.get('id', '')
        uid = r.get('userid', '')
        prof = profiles.get(uid, {})
        img = images.get(key20(nid), {})
        cand = emailcand.get(r.get('company', ''), {})

        email_text = set()
        for src in (prof.get('emails', ''), ' '.join(comm.get(key20(nid), []))):
            email_text.update(x for x in src.split() if x)
        email_image = set(x for x in img.get('emails', '').split() if x)
        email_guess = cand.get('candidates', '') if cand.get('mx_ok') == 'yes' else ''

        wechat = set()
        for src in (prof.get('wechat', ''), img.get('wechat', '')):
            wechat.update(x for x in src.split() if x)

        open_cmd = f"./open-note.sh -s {a.serial} -n {nid}" + (f" -u {uid}" if uid else '')
        rows.append({
            'company': r.get('company', ''),
            'poster_type': r.get('poster_type', ''),
            'reach': r.get('reach', ''),
            'roles': r.get('roles', ''),
            'nick': r.get('nick', ''),
            'red_id': r.get('red_id', ''),
            'email_text': ' '.join(sorted(email_text)),
            'email_image': ' '.join(sorted(email_image)),
            'email_guess': email_guess,
            'wechat': ' '.join(sorted(wechat)),
            'qr': img.get('qr', ''),
            'title': r.get('title', ''),
            'note_id': nid,
            'open_cmd': open_cmd,
        })

    rows.sort(key=lambda x: not (x['email_text'] or x['email_image'] or x['wechat'] or x['qr']))

    outp = os.path.join(o, 'leads.csv')
    cols = ['company', 'poster_type', 'reach', 'roles', 'nick', 'red_id',
            'email_text', 'email_image', 'email_guess', 'wechat', 'qr',
            'title', 'note_id', 'open_cmd']
    with open(outp, 'w', newline='', encoding='utf-8-sig') as f:
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader(); w.writerows(rows)

    have = sum(1 for x in rows if x['email_text'] or x['email_image'] or x['wechat'] or x['qr'])
    print(f"招聘线索 {len(rows)} 条,其中 {have} 条有直接联系方式(邮箱/微信/二维码) -> {outp}")

if __name__ == '__main__':
    main()
