#!/usr/bin/env python3
# 识别笔记图片里的邮箱/微信(招聘帖常把联系方式放图里躲平台检测)。
#   流程: 从 ndjson 取图片URL -> 在手机上下高清原图(手机有CDN会话) -> tesseract OCR -> 抗变形抽联系方式
#   用法: python3 harvest-ocr.py harvest-out/notes.ndjson -s <serial> [--notes recruit_notes.csv] [--max 40]
#   产物: image_contacts.csv  note_id | image_url | emails | wechat | ocr摘要
# 依赖: tesseract(chi_sim+eng)、手机端 curl、adb。
import json, re, sys, csv, os, subprocess, argparse, tempfile

# ---- 抗变形:把 OCR/防爬变形还原成正常邮箱 ----
def norm(text):
    t = text
    for a, b in [('＠', '@'), ('．', '.'), ('。', '.'), ('（', '('), ('）', ')')]:
        t = t.replace(a, b)
    # xx at qq dot com / xx(at)qq(dot)com / xx#qq.com
    t = re.sub(r'\s*[\(\[]?\s*(?:at|AT|艾特|@)\s*[\)\]]?\s*', '@', t)
    t = re.sub(r'\s*[\(\[]?\s*(?:dot|DOT|点)\s*[\)\]]?\s*', '.', t)
    t = t.replace('#', '@')  # 有人用 # 代 @(谨慎:也可能是话题,但图里少见)
    # "@domain com" -> "@domain.com"(OCR 常丢点)
    t = re.sub(r'(@[\w\-]+)\s+(com|cn|net|org|io|ai|co)\b', r'\1.\2', t)
    return t

EMAIL = re.compile(r'[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}')
WECHAT = re.compile(r'(?:微信|weixin|vx|VX|wx|WX|加V|v信|V:|扣扣|QQ)[号:：\s]*([A-Za-z0-9_\-]{5,25})')

def extract_contacts(raw):
    t = norm(raw)
    emails = set(m.lower() for m in EMAIL.findall(t))
    wechat = set(WECHAT.findall(raw) + WECHAT.findall(t))
    return emails, wechat

def img_urls_from_ndjson(path):
    m = {}
    for ln in open(path, encoding='utf-8'):
        ln = ln.strip()
        if not ln:
            continue
        try:
            rec = json.loads(ln); body = json.loads(rec['body'])
        except Exception:
            continue
        u = rec.get('url', '')
        items = []
        if 'search/notes' in u:
            items = [(it.get('note') or {}) for it in (body.get('data') or {}).get('items') or []]
        elif 'imagefeed' in u:
            for it in body.get('data', []):
                items += it.get('note_list') or []
        for n in items:
            nid = n.get('id')
            il = n.get('images_list') or n.get('images') or []
            urls = []
            for im in il:
                url = im.get('url') or im.get('url_size_large') or ''
                if url:
                    base = url.split('?')[0]
                else:
                    # 列表里后续图常只给 fileid/trace_id,自己拼 CDN 路径
                    fid = im.get('fileid') or im.get('trace_id') or ''
                    base = 'https://sns-na-i6.xhscdn.com/' + fid if fid else ''
                if base:
                    urls.append(base + '?imageView2/2/w/1080/format/jpg/q/90')
            if nid and urls:
                m.setdefault(nid, [])
                for x in urls:
                    if x not in m[nid]:
                        m[nid].append(x)
    return m

def phone_fetch(serial, url, localpath):
    dev = ['adb'] + (['-s', serial] if serial else [])
    subprocess.run(dev + ['shell', "curl -s -o /sdcard/_ocrimg.jpg '%s'" % url],
                   capture_output=True, timeout=40)
    subprocess.run(dev + ['pull', '/sdcard/_ocrimg.jpg', localpath],
                   capture_output=True, timeout=40)
    return os.path.getsize(localpath) if os.path.exists(localpath) else 0

def ocr(path):
    try:
        r = subprocess.run(['tesseract', path, 'stdout', '-l', 'chi_sim+eng'],
                           capture_output=True, text=True, timeout=60)
        return r.stdout
    except Exception:
        return ''

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('ndjson'); ap.add_argument('-s', '--serial', default='')
    ap.add_argument('--notes', help='只OCR这些note的图(recruit_notes.csv,首行表头)')
    ap.add_argument('-o', '--out', default='.'); ap.add_argument('--max', type=int, default=40)
    ap.add_argument('--per-note', type=int, default=3, help='每帖最多OCR几张图')
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    urlmap = img_urls_from_ndjson(a.ndjson)
    if a.notes:
        want = set()
        for row in csv.reader(open(a.notes, encoding='utf-8')):
            if row and row[0] != 'note_id':
                want.add(row[0])
        urlmap = {k: v for k, v in urlmap.items() if k in want}
    targets = list(urlmap.items())[:a.max]
    print(f"待OCR {len(targets)} 帖(每帖≤{a.per_note}张)")

    rows = []; tmp = tempfile.gettempdir() + '/_ocrimg.jpg'
    for nid, urls in targets:
        allmail = set(); allwx = set(); snip = ''
        for url in urls[:a.per_note]:
            if phone_fetch(a.serial, url, tmp) < 2000:
                continue
            text = ocr(tmp)
            em, wx = extract_contacts(text)
            allmail |= em; allwx |= wx
            if (em or wx) and not snip:
                snip = ' '.join(text.split())[:80]
        if allmail or allwx:
            rows.append({'note_id': nid, 'image_url': urls[0],
                         'emails': ' '.join(sorted(allmail)),
                         'wechat': ' '.join(sorted(allwx)), 'ocr': snip})
            print(f"  ★ {nid[:20]} 邮箱:{sorted(allmail)} 微信:{sorted(allwx)}")
        else:
            print(f"  · {nid[:20]} 无")

    outp = os.path.join(a.out, 'image_contacts.csv')
    with open(outp, 'w', newline='', encoding='utf-8-sig') as f:
        w = csv.DictWriter(f, fieldnames=['note_id', 'image_url', 'emails', 'wechat', 'ocr'])
        w.writeheader(); w.writerows(rows)
    print(f"\n{len(rows)} 帖图里挖到联系方式 -> {outp}")

if __name__ == '__main__':
    main()
