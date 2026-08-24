#!/usr/bin/env python3
# 把招聘线索整理成带小红书链接的可读名单(markdown)。
#   用法: python3 harvest-people.py -o harvest-out [--md ../leads/browsed_people.md]
# 读 harvest-out/recruit.csv(拿 userid/token) + leads.csv(拿合并后的联系方式),
# 输出按"有无联系方式"分组、带主页链接(稳定)+笔记链接的名单。
import csv, os, argparse

def load(p):
    return list(csv.DictReader(open(p, encoding='utf-8-sig'))) if os.path.exists(p) else []

def profile_url(uid):
    return f"https://www.xiaohongshu.com/user/profile/{uid}" if uid else ''

def note_url(nid, tok):
    if not nid:
        return ''
    u = f"https://www.xiaohongshu.com/explore/{nid}"
    if tok:
        u += f"?xsec_token={tok}&xsec_source=pc_search"
    return u

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('-o', '--out', default='harvest-out')
    ap.add_argument('--md', default='../leads/browsed_people.md')
    a = ap.parse_args()

    recruit = load(os.path.join(a.out, 'recruit.csv'))
    leads = {r['note_id']: r for r in load(os.path.join(a.out, 'leads.csv')) if r.get('note_id')}

    items = []
    for r in recruit:
        nid = r.get('id', '')
        lead = leads.get(nid, {})
        contacts = {
            'email_image': lead.get('email_image', ''),
            'email_text': lead.get('email_text', ''),
            'email_guess': lead.get('email_guess', ''),
            'wechat': lead.get('wechat', ''),
            'qr': lead.get('qr', ''),
        }
        has = bool(contacts['email_image'] or contacts['email_text'] or contacts['wechat'] or contacts['qr'])
        items.append({
            'company': r.get('company', '') or '(未定)',
            'poster_type': r.get('poster_type', ''),
            'roles': r.get('roles', ''),
            'nick': r.get('nick', ''),
            'red_id': r.get('red_id', ''),
            'userid': r.get('userid', ''),
            'title': r.get('title', ''),
            'note_id': nid,
            'token': r.get('xsec_token', ''),
            'contacts': contacts, 'has': has,
        })
    items.sort(key=lambda x: (not x['has'], x['company']))

    os.makedirs(os.path.dirname(os.path.abspath(a.md)), exist_ok=True)
    L = []
    n_has = sum(1 for x in items if x['has'])
    L.append("# 灵巧手 / 具身智能 · 招聘线索名单")
    L.append("")
    L.append(f"> 数据来自小红书**公开招聘帖**,用于求职/商务拓展调研。共 {len(items)} 条,其中 {n_has} 条带直接联系方式。")
    L.append("> 主页链接稳定;笔记链接的 token 会过期,过期用 `tools/open-note.sh` 打开。")
    L.append("")

    def render(x):
        s = []
        head = f"- **{x['nick']}**"
        if x['userid']:
            head += f" ([主页]({profile_url(x['userid'])}))"
        if x['red_id']:
            head += f" · 小红书号 `{x['red_id']}`"
        head += f" · {x['poster_type']}"
        s.append(head)
        if x['roles']:
            s.append(f"  - 岗位:{x['roles']}")
        nu = note_url(x['note_id'], x['token'])
        if x['title']:
            s.append(f"  - 帖子:[{x['title']}]({nu})" if nu else f"  - 帖子:{x['title']}")
        c = x['contacts']
        parts = []
        if c['email_image']: parts.append(f"📧图OCR:`{c['email_image']}`")
        if c['email_text']:  parts.append(f"📧文/简介/评论:`{c['email_text']}`")
        if c['wechat']:      parts.append(f"💬微信:`{c['wechat']}`")
        if c['qr']:          parts.append(f"🔗二维码:{c['qr']}")
        if c['email_guess']: parts.append(f"📮站外候选(待核):`{c['email_guess']}`")
        if parts:
            s.append("  - " + " · ".join(parts))
        return "\n".join(s)

    L.append(f"## ✅ 有直接联系方式（{n_has}）")
    L.append("")
    for x in items:
        if x['has']:
            L.append(render(x)); L.append("")
    L.append(f"## 其余招聘帖（{len(items)-n_has}，联系方式需进主页/评论进一步找）")
    L.append("")
    for x in items:
        if not x['has']:
            L.append(render(x)); L.append("")

    open(a.md, 'w', encoding='utf-8').write("\n".join(L))
    print(f"名单 {len(items)} 条(带联系方式 {n_has}) -> {os.path.abspath(a.md)}")

if __name__ == '__main__':
    main()
