# 小红书赛道公司发现 · 采集工作流

在 `XhsSaveFix` 模块(保存解限 + okhttp 采集)基础上,发现「灵巧手/具身智能」赛道的公司、招聘、账号与联系方式。
**仅供个人研究与商务拓展调研,遵守平台规则,勿高频滥采、勿骚扰投递。**

## 组成
| 文件 | 作用 |
|---|---|
| `../app/.../XhsSaveFix.java` | LSPosed 模块:保存解限 + hook okhttp 落盘白名单接口响应(ndjson) |
| `keywords.txt` | 关键词矩阵(品类/事件/中英) |
| `harvest-drive.sh` | 驱动:关键词搜索(`-n` 拟人翻页到目标页数)/ 主页(`-u`)/ 帖子详情(`-d`) |
| `harvest-extract.py` | 结构化:notes / accounts / hashtags / profiles / **comments** / emails |
| `harvest-companies.py` | LLM 从正文抽公司实体 + 跨笔记聚合去噪 |
| `harvest-recruit.py` | 筛「招聘方」帖:标发帖人类型(公司官方/员工/中介/转发)+抽真公司名+触达路径 |
| `harvest-emailfind.py` | 站外邮箱补全:公司名→LLM 提议域名→MX 验证→hr@/recruit@ 候选 |
| `harvest-ocr.py` | 图片里的邮箱/微信:手机下高清原图→tesseract OCR→抗变形抽联系方式(招聘帖常把邮箱放图里躲检测) |

## 采集接口(白名单,均真机验证)
- `search/notes` `search/videos` `search/onebox` `search/user` —— 搜索结果
- `note/imagefeed` `v10/note` —— 笔记**详情全文**(比列表完整)
- `note/comment/list` —— **评论区**(网友点名公司 + 求内推/简历发我/楼主回复联系方式)
- `user/info` —— 用户主页详情(简介 = 公司归属 + 官网/邮箱/微信)
- `note/user/posted` —— 用户发布笔记(账号滚雪球)

## 工作流(三阶段)
```bash
cd tools
S=d0a7f5cb        # adb devices

# ── 阶段1:广度发现 ──────────────────────────────
# 关键词搜索,每词拟人滑到 30 页(约 20s/页);已采词记在 done_keywords.txt,续跑时先剔除
./harvest-drive.sh -s $S -n 30 keywords.txt
python3 harvest-extract.py   harvest-out/notes.ndjson -o harvest-out
python3 harvest-companies.py harvest-out/notes.ndjson -o harvest-out
#   -> companies.csv  accounts.csv  hashtags.csv

# ── 阶段2:招聘定向(针对性,不必对每条帖进详情) ──
python3 harvest-recruit.py harvest-out/notes.ndjson -o harvest-out
#   -> recruit.csv(公司|发帖人类型|触达路径|岗位|note_id)
#      recruit_uids.txt  recruit_notes.csv  companies_to_enrich.txt
./harvest-drive.sh -s $S -d harvest-out/recruit_notes.csv   # 进招聘帖详情:全文+评论
./harvest-drive.sh -s $S -u harvest-out/recruit_uids.txt    # 进招聘方主页:简介/邮箱
python3 harvest-extract.py harvest-out/notes.ndjson -o harvest-out
#   -> profiles.csv(联系方式)  comments.csv(评论里的公司/联系方式)

# ── 阶段3:邮箱落地 ─────────────────────────────
# 3a. 图片里的邮箱(招聘帖常把 HR 邮箱放图里躲文字检测;需 tesseract chi_sim+eng)
python3 harvest-ocr.py harvest-out/notes.ndjson -s $S --notes harvest-out/recruit_notes.csv -o harvest-out
#   -> image_contacts.csv(note|图里的邮箱/微信);OCR 有误差,是候选,投递前肉眼核对
# 3b. 站外按公司名补全
python3 harvest-emailfind.py harvest-out/companies_to_enrich.txt -o harvest-out
#   -> emails_candidates.csv(公司|域名|MX有效|hr@/recruit@候选)
```

依赖:`sudo apt-get install tesseract-ocr tesseract-ocr-chi-sim`(图片 OCR 用)。

## 触达路径(实测:发帖人≠公司,分两条路)
`recruit.csv` 的 `poster_type` + `reach` 已分好:
- **公司官方/员工** → 发帖人即公司方,可直接触达(主页简介/详情正文里的联系方式)
- **中介猎头/转发资讯** → 发帖人是二手,`company` 字段给了真公司名,走**站外**联系公司
- 联系方式实测覆盖率低(采样 25 主页仅 8% 在 bio 留邮箱,且多为个人 QQ/163),**主力靠**:①评论区 ②详情正文内推码 ③站外补公司域名邮箱

## 开关(文件在 app 私有目录,需 root)
```bash
D=/data/data/com.xingin.xhs/files/xhs-harvest
adb -s $S shell su -c "touch $D/OFF"    # 停采集(当纯 SaveFix 用),删掉恢复
adb -s $S shell su -c "touch $D/PROBE"  # 探接口:白名单外 /api/sns/ 路径记到 urls.log
adb -s $S shell su -c "cat  $D/urls.log"
```

## LLM 配置(harvest-companies/recruit/emailfind 用)
默认走本地 gateway,可用环境变量覆盖:
`XHS_LLM_BASE`(默认 http://127.0.0.1:4000)/ `XHS_LLM_KEY` / `XHS_LLM_MODEL`(默认 glm-5.1,anthropic `/v1/messages` 端点)

## 风险
公开内容采集,但高频主动请求会掉号:用小号 + 限速 + 拟人节奏(脚本已内置随机滑动/停顿/词间长休息)。投递侧**小批量定向 + 每封个性化** 远优于海投。

## 进阶 TODO
- `search/user` 用户 tab / 搜索联想 `sug`:需 `input tap` UI 自动化触发
- 站内私信触达发帖人:触达最可靠但需账号操作、易风控,**暂不做**
- 小初创域名 LLM 不识别:需接 ICP 反查 / 企查查补 `emails_candidates.csv` 里 MX=no 的公司
