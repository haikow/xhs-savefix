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
 * 小红书增强模块(com.xingin.xhs),两件事:
 *
 * 1) 解除「作者关闭下载/保存」(原 SaveFix 功能)
 *    笔记数据带 com.xingin.entities.MediaSaveConfig,JSON 字段 "disable_save" -> disableSaveMedia,
 *    getter b()。下载路径 DownloadController.onDownloadClick 里 if(b()){toast;return;} 拦截。
 *    把 b()(以及水印 c()) 强制返回 false,受限笔记也能保存。
 *
 * 2) 采集(Harvest):在 app 进程内挂 okhttp interceptor,抓「搜索结果 / 笔记详情」响应体。
 *    小红书 okhttp3 未混淆,x-s/x-t 签名、登录态、设备指纹都由 app 自己完成,
 *    interceptor 拿到的是解好的明文响应,直接落盘 ndjson,供站外 pipeline 抽公司名/账号/方案。
 *    开关:创建文件 /sdcard/xhs-harvest/OFF 即停止采集(当纯 SaveFix 用),删掉恢复。
 */
public class XhsSaveFix implements IXposedHookLoadPackage {

    private static final String XHS = "com.xingin.xhs";
    private static final String CFG = "com.xingin.entities.MediaSaveConfig";

    // 只落盘这些接口的响应(含公司名/账号/方案正文),避免图片流等噪声塞满磁盘。
    private static final String[] HARVEST_KEYS = {
        "/search/notes",     // 关键词搜索结果列表(标题+作者+红书号)
        "/search/videos",
        "/search/onebox",    // 企业号/品牌聚合卡
        "/search/user",      // 用户维度搜索结果(直接搜出相关账号)
        "/note/imagefeed",   // 图文笔记正文
        "/note/comment/list",// 评论区(求内推/简历发我/楼主回复的微信邮箱)
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
        hookHarvest(lpparam);
    }

    // ---------- 1. SaveFix ----------

    private void hookSaveFix(XC_LoadPackage.LoadPackageParam lpparam) {
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

        try {
            XposedHelpers.findAndHookMethod(CFG, lpparam.classLoader, "c", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(param.getResult())) param.setResult(Boolean.FALSE);
                }
            });
            XposedBridge.log("[xhs-savefix] hooked MediaSaveConfig.c() (disableWaterMark) -> false");
        } catch (Throwable t) {
            XposedBridge.log("[xhs-savefix] hook c() failed: " + t);
        }
    }

    // ---------- 2. Harvest ----------

    private void hookHarvest(final XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;
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
                    Object request = XposedHelpers.callMethod(chain, "request");
                    Object response = XposedHelpers.callMethod(chain, "proceed", request);
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
                });
            XposedBridge.log("[xhs-harvest] armed: hooked OkHttpClient$Builder.build()");
        } catch (Throwable t) {
            XposedBridge.log("[xhs-harvest] hook build() failed: " + t);
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
