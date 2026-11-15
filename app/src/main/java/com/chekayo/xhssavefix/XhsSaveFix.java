package com.chekayo.xhssavefix;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 小红书增强模块(com.xingin.xhs),三件事:
 *
 * 1) 解除「作者关闭下载/保存」(原 SaveFix 功能),两条门都拆:
 *    - 旧门:笔记数据带 com.xingin.entities.MediaSaveConfig,JSON "disable_save" -> getter b()。
 *      下载路径 DownloadController.onDownloadClick 里 if(b()){toast;return;}。hook 成恒 false。
 *    - 新门(v9.33.x 服务端灰度,issue #2):笔记详情下发权限数组
 *      [{"reason":"作者已关闭下载权限，无法保存","type":"image_download","enable":false},...],
 *      模型 com.xingin.entities.notedetail.FunctionSwitch(enable/reason/type, getter a/b/c)。
 *      长按面板(ap8/e)与分享面板(sv8/u)里「保存图片」按 switch==null||!a() 置灰。
 *      hook a():image_download/video_download 时强制 true。
 *      同结构的旧字段 ShareInfoDetail$Operate(type/.../disable/disableToast) 一并处理。
 *
 * 2) 去水印(保存到相册的图不叠水印):
 *    保存合成器 jm7.j.a(原图, 作者水印, 滤镜, AI标签, disableWaterMark, disableAiWaterMark):
 *      if(!disableWaterMark && 作者水印!=null) 画右下角作者昵称+水印图;
 *      if(!disableAiWaterMark && AI标签!=null) 画左下角「AI生成」标签。
 *    两个开关都是服务端字段:
 *      disableWaterMark  <- MediaSaveConfig.c() (JSON "disable_watermark")
 *      disableAiWaterMark<- ImageBean.u()       (JSON "disable_ai",仅 AIGC 帖有)
 *    注意方向:disable_watermark=true 才是不加水印。旧版模块把 c() 强制成 false,
 *    反而把作者水印强制画上了;现改为恒 true。AI 标签把 ImageBean.u() 恒 true,
 *    另加兜底 hook 混淆类 jm7.j.b()(AI 标签位图构造)返回 null(绑定 9.33.4,失败不致命)。
 *
 * 2.5) 视频保存(第三道门):长按面板通过后,点保存还会请求服务端
 *      GET /api/sns/v10/note/video/save?note_id=X,作者关闭时服务端回
 *      {"data":{"msg":"作者已关闭下载功能，可尝试其他分享方式","disable":true}},无下载地址。
 *      模块平时扫 /api/sns/ 响应缓存 noteId -> 播放用 h264 mp4 直链,
 *      拦到该接口 disable:true 时改写为 disable:false 并注入 download_url,让客户端正常落盘。
 *
 * 3) 采集(Harvest):在 app 进程内挂 okhttp interceptor,抓「搜索结果 / 笔记详情」响应体。
 *    小红书 okhttp3 未混淆,x-s/x-t 签名、登录态、设备指纹都由 app 自己完成,
 *    interceptor 拿到的是解好的明文响应,直接落盘 ndjson,供站外 pipeline 抽公司名/账号/方案。
 *    开关:创建文件 /sdcard/xhs-harvest/OFF 即停止采集(当纯 SaveFix 用),删掉恢复。
 */
public class XhsSaveFix implements IXposedHookLoadPackage {

    private static final String XHS = "com.xingin.xhs";
    private static final String CFG = "com.xingin.entities.MediaSaveConfig";
    private static final String FUNCTION_SWITCH = "com.xingin.entities.notedetail.FunctionSwitch";

    // 只落盘这些接口的响应(含公司名/账号/方案正文),避免图片流等噪声塞满磁盘。
    private static final String[] HARVEST_KEYS = {
        "/search/notes",     // 关键词搜索结果列表(标题+作者+红书号)
        "/search/videos",
        "/search/onebox",    // 企业号/品牌聚合卡
        "/search/user",      // 用户维度搜索结果(直接搜出相关账号)
        "/note/imagefeed",   // 图文笔记正文
        "/note/comment/list",      // 评论区(求内推/简历发我/楼主回复的微信邮箱)
        "/comment/l1/sub/page",    // 楼中楼:父评论+子评论(v1/interaction/comment/l1/sub/page)
        "/note/comment/sub_comments", // 楼中楼:子评论分页(v3)
        "/v10/note",         // 笔记详情 feed
        "/user/info",        // 用户主页详情(v3/user/info):昵称/简介/官网/联系方式 -> 公司归属+邮箱主力
        "/note/user/posted", // 用户发布的笔记(账号滚雪球:看这个号在发什么、@了谁)
    };

    private static final File OUT_DIR_PRIMARY = new File("/sdcard/xhs-harvest");
    // fallback:app 内部目录,一定可写,root adb pull 得到。
    private static final File OUT_DIR_FALLBACK =
        new File("/data/data/com.xingin.xhs/files/xhs-harvest");

    private static final Object WRITE_LOCK = new Object();
    private static volatile File resolvedOutFile = null;
    private static final long MAX_BODY = 512 * 1024; // 单条响应最多记 512KB

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!XHS.equals(lpparam.packageName)) return;

        hookSaveFix(lpparam);
        hookNoWatermark(lpparam);
        hookVideoDownloadBranch(lpparam);
        UpdatePrompt.install(lpparam);
        hookHarvest(lpparam);
    }

    // ---------- 1. SaveFix ----------

    private void hookSaveFix(XC_LoadPackage.LoadPackageParam lpparam) {
        // 旧门:disable_save -> getter b() 恒 false,DownloadController.onDownloadClick 放行。
        try {
            XposedHelpers.findAndHookMethod(CFG, lpparam.classLoader, "b", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(param.getResult())) param.setResult(Boolean.FALSE);
                }
            });
            XposedBridge.log("[xhs-savefix] hooked MediaSaveConfig.b() (disableSaveMedia) -> false");
        } catch (Throwable t) {
            XposedBridge.log("[xhs-savefix] hook b() failed: " + t);
        }

        // 新门(issue #2):服务端权限数组 FunctionSwitch[type/enable/reason]。
        // enable getter a(),type getter c()。仅对图片/视频下载类型强制 enable=true,
        // 不动 edit/privacy 等作者自己的管理开关。
        try {
            XposedHelpers.findAndHookMethod(FUNCTION_SWITCH, lpparam.classLoader, "a",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (Boolean.TRUE.equals(param.getResult())) return;
                            Object type = XposedHelpers.callMethod(param.thisObject, "c");
                            if ("image_download".equals(type) || "video_download".equals(type)) {
                                param.setResult(Boolean.TRUE);
                            }
                        } catch (Throwable t) { // 宿主改混淆名时不许异常穿透进保存菜单
                            XposedBridge.log("[xhs-savefix] FunctionSwitch.a() cb err: " + t);
                        }
                    }
                });
            XposedBridge.log("[xhs-savefix] hooked FunctionSwitch.a() (image/video_download enable) -> true");
        } catch (Throwable t) {
            XposedBridge.log("[xhs-savefix] hook FunctionSwitch.a() failed: " + t);
        }

        // 兼容旧字段:shareInfo.function_entries 的 Operate.disable getter a(),
        // type getter g()。e3b/a 会把 disable/disableToast 塞进「保存图片」菜单项。
        try {
            XposedHelpers.findAndHookMethod("com.xingin.entities.ShareInfoDetail$Operate",
                lpparam.classLoader, "a", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!Boolean.TRUE.equals(param.getResult())) return;
                            Object type = XposedHelpers.callMethod(param.thisObject, "g");
                            if ("image_download".equals(type) || "video_download".equals(type)) {
                                param.setResult(Boolean.FALSE);
                            }
                        } catch (Throwable t) { // 宿主改混淆名时不许异常穿透进保存菜单
                            XposedBridge.log("[xhs-savefix] Operate.a() cb err: " + t);
                        }
                    }
                });
            XposedBridge.log("[xhs-savefix] hooked ShareInfoDetail$Operate.a() (image/video_download disable) -> false");
        } catch (Throwable t) {
            XposedBridge.log("[xhs-savefix] hook Operate.a() failed: " + t);
        }
    }

    // ---------- 1.5 去水印 ----------

    private void hookNoWatermark(XC_LoadPackage.LoadPackageParam lpparam) {
        // 作者水印:disable_watermark -> getter c() 恒 true(=不画水印)。
        // 合成器判定 if(!disableWaterMark && wm!=null) draw,见 jm7.j.a()。
        try {
            XposedHelpers.findAndHookMethod(CFG, lpparam.classLoader, "c", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(Boolean.TRUE);
                }
            });
            XposedBridge.log("[xhs-savefix] hooked MediaSaveConfig.c() (disableWaterMark) -> true");
        } catch (Throwable t) {
            XposedBridge.log("[xhs-savefix] hook c() failed: " + t);
        }

        // AI生成标签:disable_ai -> ImageBean.u() 恒 true。仅 AIGC 帖的图带此字段,
        // 非 AI 帖无影响;EXIF 里的 AIGC 元数据仍会写入,只去掉视觉标签。
        try {
            XposedHelpers.findAndHookMethod("com.xingin.entities.ImageBean", lpparam.classLoader, "u",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!Boolean.TRUE.equals(param.getResult())) param.setResult(Boolean.TRUE);
                    }
                });
            XposedBridge.log("[xhs-savefix] hooked ImageBean.u() (disable_ai) -> true");
        } catch (Throwable t) {
            XposedBridge.log("[xhs-savefix] hook ImageBean.u() failed: " + t);
        }

        // 兜底(9.33.x 的 jm7.j.b)已移除:合成器条件是 !disableAi && 标签!=null,
        // 上面的 u() 恒 true 后永不绘制,b() 兜底冗余;且 jm7 是混淆名每版轮换
        // (9.54.1 已变),留着只会刷启动失败日志。若服务端不再下发 disable_ai 再重启此兜底。
    }

    // ---------- 2. Harvest ----------

    private void hookHarvest(final XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;
        appClassLoader = cl;
        final Class<?> interceptorCls;
        try {
            interceptorCls = cl.loadClass("okhttp3.Interceptor");
        } catch (Throwable t) {
            XposedBridge.log("[xhs-harvest] okhttp3.Interceptor not found, skip: " + t);
            return;
        }

        // 反射代理实现 okhttp3.Interceptor,插进每个 OkHttpClient 的 application interceptors。
        final Object interceptorProxy = Proxy.newProxyInstance(cl,
            new Class<?>[]{interceptorCls}, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    if (!"intercept".equals(method.getName()) || args == null || args.length != 1) {
                        // equals/hashCode/toString 等默认行为
                        if ("toString".equals(method.getName())) return "xhs-harvest-interceptor";
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == args[0];
                        return null;
                    }
                    Object chain = args[0];
                    Object request;
                    try {
                        request = XposedHelpers.callMethod(chain, "request");
                    } catch (XposedHelpers.InvocationTargetError e) {
                        throw e.getCause() == null ? e : e.getCause(); // 还原原始异常
                    }
                    if (sApiClient == null) {
                        try { // 记下真正服务 API 请求的 client(带鉴权链),楼中楼自动展开要用
                            Object callObj = XposedHelpers.callMethod(chain, "call");
                            Object cl = XposedHelpers.getObjectField(callObj, "client");
                            if (cl != null) sApiClient = cl;
                        } catch (Throwable ignored) {}
                    }
                    Object response;
                    try {
                        response = XposedHelpers.callMethod(chain, "proceed", request);
                    } catch (XposedHelpers.InvocationTargetError e) {
                        // 11-12 崩溃日志实锤:IOException(Canceled/超时/断网等网络抖动)被
                        // callMethod 包成 InvocationTargetError(Error 子类)穿透 okhttp——
                        // App 同步调用只 catch IOException,协程/RxJava2 把 Error 视为 fatal
                        // 直接闪退。这里还原底层异常原样抛出,与无模块时语义一致。
                        Throwable c = e.getCause() == null ? e : e.getCause();
                        if (c instanceof java.io.IOException || c instanceof RuntimeException
                                || c instanceof Error) throw c;
                        throw new RuntimeException(c); // 少见受检异常兜底,避免 UndeclaredThrowable
                    }
                    try {
                        response = maybeVideoSaveFix(request, response);
                    } catch (Throwable t) {
                        XposedBridge.log("[xhs-savefix] video fix err: " + t);
                    }
                    try {
                        maybeCapture(request, response);
                    } catch (Throwable t) {
                        XposedBridge.log("[xhs-harvest] capture err: " + t);
                    }
                    return response;
                }
            });

        try {
            XposedHelpers.findAndHookMethod("okhttp3.OkHttpClient$Builder", cl, "build",
                new XC_MethodHook() {
                    @Override
                    @SuppressWarnings("unchecked")
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            List<Object> its = (List<Object>) XposedHelpers.getObjectField(
                                param.thisObject, "interceptors");
                            if (its != null && !its.contains(interceptorProxy)) {
                                its.add(interceptorProxy);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("[xhs-harvest] inject interceptor failed: " + t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // 记录宿主构建的 client(楼中楼自动展开要借它的签名链)
                        try {
                            Object built = param.getResult();
                            if (built != null) rememberClient(built);
                        } catch (Throwable ignored) {}
                    }
                });
            XposedBridge.log("[xhs-harvest] armed: hooked OkHttpClient$Builder.build()");
        } catch (Throwable t) {
            XposedBridge.log("[xhs-harvest] hook build() failed: " + t);
        }
    }

    // ---------- 1.7 楼中楼自动展开(借宿主 OkHttpClient 主动发子评论请求) ----------

    // okhttp interceptor 本身是被动的;但模块跑在宿主进程里,可以拿着宿主构建的
    // OkHttpClient 主动 enqueue 请求——x-s/x-t 签名由宿主自己的 interceptor 链完成,
    // 等于"借宿主的手"发请求。响应天然流经我们注入的 interceptor,自动落盘。
    // 场景:抓评论区时发现 sub_has_more 的楼,自动把每个楼的子评论拉全,不用人点"展开回复"。
    private static final java.util.List<Object> HOST_CLIENTS =
        java.util.Collections.synchronizedList(new java.util.ArrayList<Object>());
    private static volatile Object sApiClient = null;   // 已验证能用的 client
    private static final java.util.Set<String> EXPANDED_ROOTS =
        java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static final java.util.Set<String> SEEN_SUB_IDS =
        java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    // 主动发起的子评论请求登记(noteId|commentId|start原值),响应回流时消费标记。
    // 只有带标记的响应才触发续抓链;宿主 UI 自己翻页的响应只落盘不触发。
    // 根因修复:宿主分页响应也流经我们注入的 interceptor,此前对每个 sub_comments
    // 响应都无条件 queueNextSubPage,造成"宿主翻页 × 模块续抓"乘法放大
    // (上游反馈用户翻 3 次评论产生 2317 次请求的失控根因)。
    // 不碰撞保证(2026-11-15 实测宿主 URL):宿主 start 传 JSON 游标对象
    // (%7B%22cursor%22...),模块只传纯子评 id 或空串,同一楼同游标不会串。
    private static final java.util.Set<String> SELF_PENDING =
        java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static void registerSelf(String noteId, String commentId, String start) {
        if (SELF_PENDING.size() > 200) SELF_PENDING.clear(); // 泄漏兜底(请求失败标记残留)
        SELF_PENDING.add(noteId + "|" + commentId + "|" + (start == null ? "" : start));
    }

    private static boolean consumeSelf(String url) {
        String nid = queryParam(url, "note_id");
        String cid = queryParam(url, "comment_id");
        if (nid == null || cid == null) return false;
        String st = queryParam(url, "start");
        return SELF_PENDING.remove(nid + "|" + cid + "|" + (st == null ? "" : st));
    }
    private static final int MAX_PAGES_PER_FLOOR = 3;   // 单楼子页上限(每页5条,覆盖15条子评足够)
    private static final int MAX_FLOORS_PER_NOTE = 40;  // 单帖排队楼层数上限
    private static final java.util.concurrent.ExecutorService EXPAND_POOL =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    private static volatile long sLastExpandAt = 0L;
    private static volatile int sExpandCount = 0;
    private static final int EXPAND_MAX_PER_RUN = 60;   // 单进程会话上限,风控友好
    // 人像节奏规则(见 tools/README.md):默认 stealth 档像人;harvest 目录建 FAST 文件切 verify 快档
    private static volatile int sPauseCounter = 0;
    private static volatile int sPauseAt = 5 + (int) (Math.random() * 5); // 每 5-9 个请求插一次阅读停顿

    private static boolean fastMode() {
        return new File(OUT_DIR_PRIMARY, "FAST").exists()
            || new File(OUT_DIR_FALLBACK, "FAST").exists();
    }

    /** 下一个请求前要等的毫秒数:verify 0.8-1.5s;stealth 2-6s,每 5-9 个请求加 8-20s 阅读停顿 */
    private static long nextGap() {
        long g = fastMode() ? 800 + (long) (Math.random() * 700)
                            : 2000 + (long) (Math.random() * 4000);
        if (!fastMode() && ++sPauseCounter % sPauseAt == 0) {
            g += 8000 + (long) (Math.random() * 12000);
            sPauseAt = 5 + (int) (Math.random() * 5); // 重新随机下一个停顿点
        }
        return g;
    }

    // 已展开楼层持久化(宿主 filesDir),重装/重启不重复请求
    private static volatile boolean sRootsLoaded = false;
    private static File rootsFile() {
        return new File(OUT_DIR_FALLBACK, "expanded_roots.txt");
    }
    private static void loadExpandedRoots() {
        if (sRootsLoaded) return;
        synchronized (EXPANDED_ROOTS) {
            if (sRootsLoaded) return;
            try {
                java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader(rootsFile()));
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) EXPANDED_ROOTS.add(line);
                }
                r.close();
            } catch (Throwable ignored) {}
            sRootsLoaded = true;
        }
    }
    private static void persistRoot(String key) {
        try {
            java.io.FileWriter w = new java.io.FileWriter(rootsFile(), true);
            w.write(key + "\n");
            w.close();
        } catch (Throwable ignored) {}
    }

    private static final java.util.Map<String, Integer> PAGE_COUNTERS =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static int pageCounter(String key) {
        Integer v = PAGE_COUNTERS.get(key);
        return v == null ? 0 : v;
    }
    private static void bumpCounter(String key) {
        PAGE_COUNTERS.put(key, pageCounter(key) + 1);
    }

    private static void rememberClient(Object client) {
        synchronized (HOST_CLIENTS) {
            if (HOST_CLIENTS.size() >= 8) HOST_CLIENTS.remove(0);
            HOST_CLIENTS.add(client);
        }
    }

    /** 捕获到主评论分页后,把其中 sub_has_more 的楼排队自动展开 */
    private static void autoExpandSubComments(String url, String body) {
        if (sExpandCount >= EXPAND_MAX_PER_RUN) return;
        loadExpandedRoots();
        final String noteId = queryParam(url, "note_id");
        if (noteId == null) return;
        try {
            org.json.JSONObject o = new org.json.JSONObject(body);
            org.json.JSONObject d = o.optJSONObject("data");
            org.json.JSONArray cs = d == null ? null : d.optJSONArray("comments");
            if (cs == null) return;
            final java.util.List<String> roots = new java.util.ArrayList<>();
            for (int i = 0; i < cs.length(); i++) {
                org.json.JSONObject c = cs.optJSONObject(i);
                if (c == null) continue;
                loadExpandedRoots();
                org.json.JSONArray pre = c.optJSONArray("sub_comments");
                if (pre != null) {
                    for (int j = 0; j < pre.length(); j++) {
                        org.json.JSONObject p0 = pre.optJSONObject(j);
                        if (p0 != null) SEEN_SUB_IDS.add(p0.optString("id", ""));
                    }
                }
                org.json.JSONArray inl = c.optJSONArray("sub_comments");
                int inline = inl == null ? 0 : inl.length();
                boolean more = c.optBoolean("sub_has_more", false);
                int cnt = c.optInt("sub_comment_count", 0);
                // 注意:服务端对部分楼不下发 sub_has_more,且内联条与子评列表可能不同步
                // (实测 sub_cnt=1、内联=1 但内联那条不在子评列表里),因此只要 cnt>0 就排队,
                // expanded_roots 持久化兜底保证每楼只发一轮。
                if (!more && cnt <= 0) continue;
                String rid = c.optString("id", "");
                if (rid.isEmpty()) continue;
                String key = noteId + "/" + rid;
                if (!EXPANDED_ROOTS.add(key)) continue;
                persistRoot(key);
                // 逆向结论(mr7/a + SubCommentLoadMoreBinderV2 链):宿主正常浏览时
                // startId 恒为空串(n0()/x() 都返回 "");内联预览条不在子评分页序列里,
                // 拿它当游标会取空。首采页永远 start="",续页由 queueNextSubPage 链式翻。
                roots.add(rid + "|");
            }
            if (roots.isEmpty()) return;
            // 单帖楼层数上限: 防大评论区一次性排满队列
            int queuedForNote = 0;
            for (String k : EXPANDED_ROOTS) {
                if (k.startsWith(noteId + "/")) queuedForNote++;
            }
            int overflow = roots.size() + queuedForNote - MAX_FLOORS_PER_NOTE;
            if (overflow > 0) {
                roots.subList(Math.max(0, roots.size() - overflow), roots.size()).clear();
                XposedBridge.log("[xhs-harvest] 单帖楼层数截断" + overflow
                    + " (累计" + queuedForNote + ",上限" + MAX_FLOORS_PER_NOTE + ")");
            }
            final ClassLoader cl2 = appClassLoader;
            EXPAND_POOL.execute(new Runnable() {
                @Override
                public void run() {
                    for (String pair : roots) {
                        String rid = pair;
                        String startArg = "";
                        int bar = pair.indexOf('|');
                        if (bar > 0) { rid = pair.substring(0, bar); startArg = pair.substring(bar + 1); }
                        if (sExpandCount >= EXPAND_MAX_PER_RUN) break;
                        try {
                            long gap = nextGap() - (System.currentTimeMillis() - sLastExpandAt);
                            if (gap > 0) Thread.sleep(gap);
                            sLastExpandAt = System.currentTimeMillis();
                            sExpandCount++;
                            persistRoot(noteId + "/" + rid);
                            // 逆向确认的精确路径(与宿主 UI 完全同参):kr9.c 单例 → CommentService(edith)
                            //   → getSubCommentsV3(noteId, rid, start="", num=5, source="", filter="",
                            //     top="", top_v2="", page_context="", scene="", re_expand=false)
                            // 签名/shield/公共参数/sid 全由宿主栈补;响应流经我们的 interceptor 落盘。
                            Class<?> svcCls = cl2.loadClass(
                                "com.xingin.matrix.comment.model.service.CommentService");
                            Object registry = serviceRegistry(cl2);
                            Object svc = XposedHelpers.callMethod(registry, "b", svcCls);
                            registerSelf(noteId, rid, startArg); // 首页响应回流时凭此标记续抓
                            Object obs = callSubComments(svc, cl2, noteId, rid, startArg);
                            XposedHelpers.callMethod(obs, "subscribe");
                            XposedBridge.log("[xhs-harvest] auto-expand fired " + noteId + "/" + rid);
                        } catch (Throwable t) {
                            XposedBridge.log("[xhs-harvest] auto-expand err: " + t);
                        }
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    private static Object noopConsumer(ClassLoader cl) {
        try {
            Class<?> c = cl.loadClass("io.reactivex.functions.Consumer");
            return java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{c},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] a) {
                        return null;
                    }
                });
        } catch (Throwable t) {
            return null;
        }
    }

    // 子评论每页服务端上限 5 条;页间隔与楼层间隔同规则(人像节奏)
    // 防失控: 页数上限/楼层数上限/新增性校验(0新增立即停链)/指数退避
    private static void queueNextSubPage(String url, String body) {
        try {
            if (sExpandCount >= EXPAND_MAX_PER_RUN) return;
            org.json.JSONObject o = new org.json.JSONObject(body);
            org.json.JSONObject d = o.optJSONObject("data");
            if (d == null || !d.optBoolean("has_more", false)) return;
            org.json.JSONArray cs = d.optJSONArray("comments");
            if (cs == null || cs.length() == 0) return;
            // 新增性校验: 本页所有子评都已见过 → 游标未前进,立即终止(防服务器游标循环)
            int fresh = 0;
            for (int i = 0; i < cs.length(); i++) {
                org.json.JSONObject c = cs.optJSONObject(i);
                if (c != null && SEEN_SUB_IDS.add(c.optString("id", ""))) fresh++;
            }
            if (fresh == 0) {
                XposedBridge.log("[xhs-harvest] 子页0新增,终止该楼续抓(防游标循环)");
                return;
            }
            org.json.JSONObject last = cs.optJSONObject(cs.length() - 1);
            String startId = last == null ? null : last.optString("id", "");
            if (startId == null || startId.isEmpty()) return;
            final String noteId = queryParam(url, "note_id");
            final String commentId = queryParam(url, "comment_id");
            if (noteId == null || commentId == null) return;
            // 页数上限: 同一楼的页计数器(noteId/commentId/#pages)。
            // 计数器只数续页(首页在 autoExpandSubComments 发,不进这里),
            // 含首页总共 MAX_PAGES_PER_FLOOR 页 → 续页数到 MAX-1 就停(实测曾 off-by-one 到 4 页)。
            String pageKey = noteId + "/" + commentId + "#pages";
            int pages = pageCounter(pageKey);
            if (pages >= MAX_PAGES_PER_FLOOR - 1) {
                XposedBridge.log("[xhs-harvest] 该楼已达页数上限(" + MAX_PAGES_PER_FLOOR + "),停止续抓");
                return;
            }
            bumpCounter(pageKey);
            final String start = startId;
            final ClassLoader cl2 = appClassLoader;
            if (!EXPANDED_ROOTS.add(noteId + "/" + commentId + "#p" + startId)) return;
            EXPAND_POOL.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        long gap = nextGap() - (System.currentTimeMillis() - sLastExpandAt);
                        if (gap > 0) Thread.sleep(gap);
                        sLastExpandAt = System.currentTimeMillis();
                        sExpandCount++;
                        Object registry = serviceRegistry(cl2);
                        Object svc = XposedHelpers.callMethod(registry, "b",
                                cl2.loadClass("com.xingin.matrix.comment.model.service.CommentService"));
                        registerSelf(noteId, commentId, start); // 续页响应回流时凭此标记再续
                        Object obs = callSubComments(svc, cl2, noteId, commentId, start);
                        XposedHelpers.callMethod(obs, "subscribe");
                        XposedBridge.log("[xhs-harvest] auto-expand next-page " + noteId + "/"
                                + commentId + " start=" + start);
                    } catch (Throwable t) {
                        XposedBridge.log("[xhs-harvest] next-page err: " + t);
                    }
                }
            });
        } catch (Throwable ignored) {}
    }


    /** Retrofit 服务注册表单例。9.33.x=kr9.c,9.54.x=i0b.c(逆向确认,za9/e0 等调用点);
     *  静态字段名随混淆轮换(f236068a/a),一律用"静态自类型字段"扫描兜底。 */
    private static Object serviceRegistry(ClassLoader cl) throws Throwable {
        for (String name : new String[]{"i0b.c", "kr9.c"}) {
            try {
                Class<?> kls = cl.loadClass(name);
                for (Class<?> c = kls; c != null; c = c.getSuperclass()) {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                                && f.getType().getName().equals(c.getName())) {
                            f.setAccessible(true);
                            return f.get(null);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        throw new IllegalStateException("comment service registry not found (i0b.c/kr9.c)");
    }

    /** getSubCommentsV3 兼容调用:9.33.x 11 参,9.54.x 末尾新增 @t("container")(宿主
     *  AB 关闭时也传空串,ya9/h.h() 逆向确认) → 先试 12 参,回退 11 参。 */
    private static Object callSubComments(Object svc, ClassLoader cl,
            Object noteId, Object commentId, String start) throws Throwable {
        try {
            return XposedHelpers.callMethod(svc, "getSubCommentsV3",
                    noteId, commentId, start, 5, "", "", "", "", "", "", Boolean.FALSE, "");
        } catch (Throwable t12) {
            return XposedHelpers.callMethod(svc, "getSubCommentsV3",
                    noteId, commentId, start, 5, "", "", "", "", "", "", Boolean.FALSE);
        }
    }

    private static String uriHost(String url) {
        try {
            java.net.URI u = java.net.URI.create(url);
            return u.getScheme() + "://" + u.getHost();
        } catch (Throwable t) {
            return "https://edith.xiaohongshu.com";
        }
    }

    /** 用宿主 client 发 GET;逐个尝试记住的 client,成功者记为 sApiClient。响应由我们自己的 interceptor 落盘。 */
    private static String fetchViaHost(String urlStr) throws Throwable {
        List<Object> tries = new java.util.ArrayList<>();
        if (sApiClient != null) tries.add(sApiClient);
        synchronized (HOST_CLIENTS) { tries.addAll(HOST_CLIENTS); }
        Throwable last = null;
        for (Object client : tries) {
            try {
                Class<?> rbCls = appClassLoader.loadClass("okhttp3.Request$Builder");
                Object rb = rbCls.getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(rb, "url", urlStr);
                Object req = XposedHelpers.callMethod(rb, "build");
                Object call = XposedHelpers.callMethod(client, "newCall", req);
                Object resp = XposedHelpers.callMethod(call, "execute");
                try {
                    int code = (int) XposedHelpers.callMethod(resp, "code");
                    Object body = XposedHelpers.callMethod(resp, "body");
                    String text = body == null ? "" : (String) XposedHelpers.callMethod(body, "string");
                    // HTTP 200 不够:业务码 -100(登录已过期)说明这个 client 没挂鉴权链,换下一个
                    XposedBridge.log("[xhs-harvest] client try: code=" + code
                            + " head=" + text.substring(0, Math.min(80, text.length())).replace('\n',' '));
                    if (code == 200 && text.startsWith("{") && !text.contains("登录已过期")
                            && !text.contains("\"success\":false")) {
                        sApiClient = client;
                        return text;
                    }
                } finally {
                    try { XposedHelpers.callMethod(resp, "close"); } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                XposedBridge.log("[xhs-harvest] client try err: " + t);
                last = t;
            }
        }
        if (last != null) throw last;
        return null;
    }

    // ---------- 1.8 视频保存(第三道门) ----------

    // 视频保存除了 FunctionSwitch 菜单门,还有服务端接口门:
    //   GET /api/sns/v10/note/video/save?note_id=X
    // 作者关闭时返回 {"result":0,"success":true,"data":{"msg":"作者已关闭下载功能，可尝试其他分享方式",
    //   "note_id":"X","disable":true}},没有 download_url;模型 wj6.f(disable/status/note_id/download_url/msg)。
    // 策略:平时扫 /api/sns/ 响应,缓存 noteId -> 播放用 h264 mp4 直链(video_info_v2.stream.h264[0].master_url);
    // 拦到 video/save 的 disable:true 时,改写为 disable:false 并注入 download_url,让客户端走正常下载落盘。
    private static final java.util.Map<String, String> VIDEO_URLS =
        new java.util.concurrent.ConcurrentHashMap<String, String>();
    private static final java.util.regex.Pattern P_MASTER_MP4 = java.util.regex.Pattern.compile(
        "\"h264\"\\s*:\\s*\\[.{0,4000}?\"master_url\"\\s*:\\s*\"(http[^\"]+?\\.mp4[^\"]*?)\"");
    private static final java.util.regex.Pattern P_NOTE_ID = java.util.regex.Pattern.compile(
        "\"(?:id|note_id)\"\\s*:\\s*\"([0-9a-f]{16,32})\"");

    private Object maybeVideoSaveFix(Object request, Object response) throws Throwable {
        if (request == null || response == null) return response;
        String url = String.valueOf(XposedHelpers.callMethod(request, "url"));
        // 只看 API 响应,CDN 媒体流(*.xhscdn.com)不进这里
        if (!url.contains("/api/sns/")) return response;

        Object peek = XposedHelpers.callMethod(response, "peekBody", MAX_BODY);
        String body = (String) XposedHelpers.callMethod(peek, "string");
        if (body == null || body.isEmpty()) return response;

        if (url.contains("/note/video/save")) {
            return patchVideoSaveResponse(url, body, response);
        }

        // 楼中楼子页:has_more=true 时排队下一页(start=本页最后一条子评论id)。
        // 只认自己发起的请求(消费 SELF_PENDING 标记才继续)——宿主 UI 自己翻页的
        // 响应流经 interceptor 时只落盘,不再触发续抓,根除乘法放大。
        if (url.contains("/note/comment/sub_comments") && consumeSelf(url)) {
            queueNextSubPage(url, body);
        }

        // 缓存:noteId -> h264 mp4 直链
        int idx = body.indexOf("\"video_info_v2\"");
        while (idx >= 0) {
            fillVideoUrl(body, idx);
            idx = body.indexOf("\"video_info_v2\"", idx + 16);
        }
        return response;
    }

    private void fillVideoUrl(String body, int v2Idx) {
        java.util.regex.Matcher m = P_MASTER_MP4.matcher(body);
        m.region(v2Idx, Math.min(body.length(), v2Idx + 6000));
        if (!m.find()) return;
        String mp4 = m.group(1);
        int from = Math.max(0, v2Idx - 8000);
        java.util.regex.Matcher idm = P_NOTE_ID.matcher(body);
        idm.region(from, v2Idx);
        String noteId = null;
        while (idm.find()) noteId = idm.group(1); // 取离 video_info_v2 最近的一个
        if (noteId == null || VIDEO_URLS.containsKey(noteId)) return;
        if (VIDEO_URLS.size() > 500) VIDEO_URLS.clear();
        VIDEO_URLS.put(noteId, mp4);
    }

    private Object patchVideoSaveResponse(String url, String body, Object response) throws Throwable {
        if (!body.contains("\"disable\"")) return response;
        String noteId = queryParam(url, "note_id");
        String mp4 = noteId == null ? null : VIDEO_URLS.get(noteId);
        // disable 恒 false;status 必须显式为 2(客户端轮询本接口直到 status==2 才开始下载,
        // 0/1=未就绪继续轮询,其他=失败;服务端拒绝时不带 status 字段,默认 0 -> 死循环)。
        // download_url 尽力注入(有缓存时);没有也行——客户端 z6 分支会用 VideoInfo.Z(),
        // 即内存里从 videoV2 播放流构造的 h264 master_url,始终有值。
        String patched = body.replaceAll("\"disable\"\\s*:\\s*true", "\"disable\":false");
        if (patched.contains("\"status\"")) {
            patched = patched.replaceAll("\"status\"\\s*:\\s*\\d+", "\"status\":2");
        } else {
            patched = patched.replace("\"disable\":false", "\"disable\":false,\"status\":2");
        }
        if (mp4 != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"note_id\"\\s*:\\s*\"([^\"]+)\"").matcher(patched);
            if (m.find()) {
                String ins = "\"note_id\":\"" + m.group(1) + "\",\"download_url\":\"" + mp4 + "\"";
                patched = patched.substring(0, m.start()) + ins + patched.substring(m.end());
            } else {
                patched = patched.replace("\"status\":2", "\"download_url\":\"" + mp4 + "\",\"status\":2");
            }
        }
        XposedBridge.log("[xhs-savefix] video/save rewritten (disable:false + status:2"
            + (mp4 != null ? " + download_url" : "") + "), noteId=" + noteId);
        return rebuildResponse(response, patched);
    }

    private Object rebuildResponse(Object response, String text) throws Throwable {
        Object oldBody = XposedHelpers.callMethod(response, "body");
        Object mediaType = oldBody == null ? null : XposedHelpers.callMethod(oldBody, "contentType");
        Object newBody = createResponseBody(mediaType, text);
        if (newBody == null) {
            XposedBridge.log("[xhs-savefix] ResponseBody.create failed, keep original");
            return response;
        }
        Object builder = XposedHelpers.callMethod(response, "newBuilder");
        XposedHelpers.callMethod(builder, "body", newBody);
        return XposedHelpers.callMethod(builder, "build");
    }

    // okhttp3(未混淆)三种 create 签名依次尝试:Kotlin companion、(MediaType,String)、(String,MediaType)
    private static volatile ClassLoader appClassLoader;

    private Object createResponseBody(Object mediaType, String text) {
        ClassLoader cl = appClassLoader;
        try {
            Class<?> c = XposedHelpers.findClass("okhttp3.ResponseBody", cl);
            Object companion = XposedHelpers.getStaticObjectField(c, "Companion");
            return XposedHelpers.callMethod(companion, "create", text, mediaType);
        } catch (Throwable ignored) {}
        try {
            Class<?> c = XposedHelpers.findClass("okhttp3.ResponseBody", cl);
            return XposedHelpers.callStaticMethod(c, "create", mediaType, text);
        } catch (Throwable ignored) {}
        try {
            Class<?> c = XposedHelpers.findClass("okhttp3.ResponseBody", cl);
            return XposedHelpers.callStaticMethod(c, "create", text, mediaType);
        } catch (Throwable ignored) {}
        return null;
    }

    private static String queryParam(String url, String key) {
        int i = url.indexOf('?' + key + '=');
        if (i < 0) i = url.indexOf('&' + key + '=');
        if (i < 0) return null;
        int s = i + key.length() + 2;
        int e = url.indexOf('&', s);
        if (e < 0) e = url.length();
        return url.substring(s, e);
    }

    // 视频下载分支纠偏:wh8.o1.a(activity, aVar, noteFeed, fVar, w0Var, int, z6, oVar) 里
    // if(z6) 用 noteFeed.s2().Z()(VideoInfo.url,别人的视频服务端不下发,是空的)
    // else    用 fVar.b()(wj6.f.download_url,官方/我们注入的直链)。
    // 我们把 disableWaterMark 强制 true 后 z6 恒为 true,别人的视频就拿到空 URL -> 进度卡 0。
    // 这里在 Z() 为空且 download_url 非空时把 z6 翻成 false,强制走直链。
    // (混淆类/方法名,绑定 9.33.4;失败只影响视频保存,不影响其他功能)
    private void hookVideoDownloadBranch(XC_LoadPackage.LoadPackageParam lpparam) {
        // 9.33.x=wh8.o1(s2()/Z(), 参数 jra.a/wj6.f/sv9.w0/y06.o);
        // 9.54.x=gl9.q1(y2()/f0(), 参数 m1c.a/tj7.f/c5b.z0/jp6.o),逆向 out8/gl9/q1.java:149。
        // 分支结构不变:if(z) 用 NoteFeed 视频自身 url else 用响应模型 download_url。
        String[][] targets = {
            {"wh8.o1", "jra.a", "wj6.f", "sv9.w0", "y06.o", "s2", "Z"},
            {"gl9.q1", "m1c.a", "tj7.f", "c5b.z0", "jp6.o", "y2", "f0"},
        };
        for (String[] tg : targets) {
            try {
                final String urlGetter = tg[6];
                XposedHelpers.findAndHookMethod(tg[0], lpparam.classLoader, "a",
                    "android.app.Activity", tg[1], "com.xingin.entities.notedetail.NoteFeed",
                    tg[2], tg[3], int.class, boolean.class, tg[4],
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object[] a = param.args;
                            if (a == null || a.length < 8 || !Boolean.TRUE.equals(a[6])) return;
                            String z = null;
                            try {
                                Object vi = XposedHelpers.callMethod(a[2], tg[5]);
                                if (vi != null) {
                                    Object r = XposedHelpers.callMethod(vi, urlGetter);
                                    z = r == null ? null : String.valueOf(r);
                                }
                            } catch (Throwable ignored) {}
                            if (z != null && !z.isEmpty()) return; // 自己的视频:有值,保持原分支
                            String dl = null;
                            try {
                                Object r = XposedHelpers.callMethod(a[3], "b");
                                dl = r == null ? null : String.valueOf(r);
                            } catch (Throwable ignored) {}
                            if (dl != null && !dl.isEmpty()) {
                                a[6] = Boolean.FALSE;
                                XposedBridge.log("[xhs-savefix] " + tg[0]
                                        + ".a: url empty, use download_url=" + dl);
                            }
                        }
                    });
                XposedBridge.log("[xhs-savefix] hooked " + tg[0] + ".a (video download branch)");
                return; // 命中当前版本的类即止
            } catch (Throwable t) {
                XposedBridge.log("[xhs-savefix] hook " + tg[0] + ".a failed (non-fatal): " + t);
            }
        }
    }

    private void maybeCapture(Object request, Object response) throws Throwable {
        if (isOff()) return;

        Object httpUrl = XposedHelpers.callMethod(request, "url");
        String url = String.valueOf(httpUrl);

        boolean hit = matches(url);
        if (!hit) {
            // PROBE 模式:白名单外的 /api/sns/ 请求只记 url(不含 body),用于发现新接口路径。
            if (isProbe() && url.contains("/api/sns/")) {
                int c = (int) XposedHelpers.callMethod(response, "code");
                writeUrl(url, c);
            }
            return;
        }

        int code = (int) XposedHelpers.callMethod(response, "code");

        // peekBody(long) 返回 body 的独立副本,不消费原始流,app 照常拿到数据。
        Object peek = XposedHelpers.callMethod(response, "peekBody", MAX_BODY);
        String body = (String) XposedHelpers.callMethod(peek, "string");
        if (body == null || body.isEmpty()) return;

        String method = String.valueOf(XposedHelpers.callMethod(request, "method"));
        writeLine(buildJson(method, url, code, body));
        // 楼中楼自动展开:主评论分页落盘后,把 sub_has_more 的楼排队拉全
        if (url.contains("/note/comment/list")) {
            autoExpandSubComments(url, body);
        }
    }

    private static boolean matches(String url) {
        for (String k : HARVEST_KEYS) if (url.contains(k)) return true;
        return false;
    }

    private static boolean isOff() {
        return new File(OUT_DIR_PRIMARY, "OFF").exists()
            || new File(OUT_DIR_FALLBACK, "OFF").exists();
    }

    private static boolean isProbe() {
        return new File(OUT_DIR_PRIMARY, "PROBE").exists()
            || new File(OUT_DIR_FALLBACK, "PROBE").exists();
    }

    // 探测:把去掉 query 的接口路径去重记到 urls.log(同目录),便于发现 search/user、用户简介、关注列表等 path。
    private static final java.util.Set<String> SEEN_PATHS =
        java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static void writeUrl(String url, int code) {
        int q = url.indexOf('?');
        String path = q >= 0 ? url.substring(0, q) : url;
        if (!SEEN_PATHS.add(path)) return; // 每个 path 只记一次
        File out = resolveOutFile();
        if (out == null) return;
        File log = new File(out.getParentFile(), "urls.log");
        synchronized (WRITE_LOCK) {
            try (FileOutputStream fos = new FileOutputStream(log, true);
                 Writer w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                w.write(code + " " + path + "\n");
            } catch (Throwable t) {
                XposedBridge.log("[xhs-harvest] url log failed: " + t);
            }
        }
    }

    // 一行 ndjson:{"ts","method","url","code","body":<原始响应字符串>}
    private static String buildJson(String method, String url, int code, String body) {
        StringBuilder sb = new StringBuilder(body.length() + 128);
        sb.append('{')
          .append("\"ts\":").append(System.currentTimeMillis()).append(',')
          .append("\"method\":\"").append(esc(method)).append("\",")
          .append("\"url\":\"").append(esc(url)).append("\",")
          .append("\"code\":").append(code).append(',')
          .append("\"body\":\"").append(esc(body)).append('"')
          .append('}');
        return sb.toString();
    }

    private static void writeLine(String line) {
        File out = resolveOutFile();
        if (out == null) return;
        synchronized (WRITE_LOCK) {
            try (FileOutputStream fos = new FileOutputStream(out, true);
                 Writer w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                w.write(line);
                w.write('\n');
            } catch (Throwable t) {
                XposedBridge.log("[xhs-harvest] write failed: " + t);
            }
        }
    }

    private static File resolveOutFile() {
        File f = resolvedOutFile;
        if (f != null) return f;
        synchronized (WRITE_LOCK) {
            if (resolvedOutFile != null) return resolvedOutFile;
            for (File dir : new File[]{OUT_DIR_PRIMARY, OUT_DIR_FALLBACK}) {
                try {
                    if (!dir.exists()) dir.mkdirs();
                    File cand = new File(dir, "notes.ndjson");
                    // 试写一次确认可写
                    try (FileOutputStream t = new FileOutputStream(cand, true)) {
                        // ok
                    }
                    resolvedOutFile = cand;
                    XposedBridge.log("[xhs-harvest] output -> " + cand.getAbsolutePath());
                    return cand;
                } catch (Throwable ignore) {
                    // 换下一个候选
                }
            }
            XposedBridge.log("[xhs-harvest] no writable output dir, capture disabled");
            return null;
        }
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }
}
