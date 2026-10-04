package com.chekayo.xhssavefix;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 更新提示(参照 FeishuKit/com.chekayo.feishuantirecall 的 UpdateBanner + 设置注入):
 *
 * 1) 打开小红书时:hook 主页 IndexActivityV2.onResume,一次进程内异步拉 version.json
 *    (jsDelivr/fastly/ghproxy/raw 镜像链),远端 versionCode > 本地 时在
 *    android.R.id.content 顶部插一条横幅——点击去下载,✕ 记住忽略该版本。
 * 2) 设置页入口:hook SettingActivityV2.onCreate,右下角加一枚悬浮「SaveFix」药丸,
 *    点开模块面板(当前版本/最新版本/changelog/去下载/忽略此版本)。
 *
 * 本地版本号读自身 PackageInfo(com.chekayo.xhssavefix),忽略版本持久化在宿主
 * filesDir 下的小文件(模块以宿主 uid 运行,直接可写)。
 */
public final class UpdatePrompt {

    private static final String TAG = "[xhs-savefix] ";
    private static final String SELF_PKG = "com.chekayo.xhssavefix";
    private static final String MAIN_ACTIVITY = "com.xingin.xhs.index.v2.IndexActivityV2";
    private static final String SETTING_ACTIVITY = "com.xingin.matrix.setting.SettingActivityV2";
    private static final int BANNER_ID = 0x7ACC0002;
    private static final int PILL_ID = 0x7ACC0003;
    private static final long CHECK_INTERVAL_MS = 6L * 3600 * 1000; // 面板内 6h 内复用缓存

    /** 与 AndroidManifest versionName/versionCode 同步(更新检查比对基准) */
    static final String MODULE_VERSION = "1.2.1";
    static final int MODULE_VERSION_CODE = 4;

    /** 开源地址(面板里露出) */
    private static final String REPO_URL = "https://github.com/haikow/xhs-savefix";
    private static final String REPO_URL_LSPREPO = "https://github.com/Xposed-Modules-Repo/com.chekayo.xhssavefix";

    /** version.json 镜像链,和 FeishuKit 同款 */
    private static final String[] MIRRORS = {
            "https://cdn.jsdelivr.net/gh/haikow/xhs-savefix@main/version.json",
            "https://fastly.jsdelivr.net/gh/haikow/xhs-savefix@main/version.json",
            "https://ghproxy.net/https://raw.githubusercontent.com/haikow/xhs-savefix/main/version.json",
            "https://gh-proxy.com/raw.githubusercontent.com/haikow/xhs-savefix/main/version.json",
            "https://raw.githubusercontent.com/haikow/xhs-savefix/main/version.json",
    };

    private static volatile boolean installed = false;
    private static volatile boolean checkedThisRun = false;
    private static volatile String latestJson = null;   // 最近一次拉到的 version.json 原文
    private static volatile long lastCheckAt = 0L;

    public static void install(de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam lpparam) {
        if (installed) return;
        installed = true;
        final de.robv.android.xposed.XC_MethodHook onResumeHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (checkedThisRun) return;
                    checkedThisRun = true;
                    runCheck((Activity) param.thisObject, null, false, true, null);
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(MAIN_ACTIVITY, lpparam.classLoader, "onResume", onResumeHook);
            XposedBridge.log(TAG + "UpdatePrompt: " + MAIN_ACTIVITY + ".onResume 已 hook");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "UpdatePrompt hook MainActivity failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(SETTING_ACTIVITY, lpparam.classLoader, "onCreate",
                    android.os.Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                addSettingsPill((Activity) param.thisObject);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "settings pill failed: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + "UpdatePrompt: " + SETTING_ACTIVITY + ".onCreate 已 hook");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "UpdatePrompt hook SettingActivity failed: " + t);
        }
    }

    // ---------- 检查 ----------

    private static final class Result {
        final int versionCode;
        final String versionName;
        final String changelog;
        final String downloadUrl;

        Result(int vc, String vn, String cl, String du) {
            versionCode = vc;
            versionName = vn;
            changelog = cl;
            downloadUrl = du;
        }
    }

    private static Result parse(String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            String du = "";
            org.json.JSONArray da = o.optJSONArray("downloads");
            if (da != null && da.length() > 0) du = da.optString(0, "");
            if (du.isEmpty()) du = o.optString("download", "");
            return new Result(o.optInt("versionCode", 0), o.optString("versionName", ""),
                    o.optString("changelog", ""), du);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String fetch() {
        for (String u : MIRRORS) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "xhs-savefix");
                if (c.getResponseCode() != 200) {
                    try { c.disconnect(); } catch (Throwable ignored) {}
                    continue;
                }
                InputStream is = c.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                is.close();
                try { c.disconnect(); } catch (Throwable ignored) {}
                return new String(bo.toByteArray(), "UTF-8");
            } catch (Throwable ignore) {
                // 换下一个镜像
            }
        }
        return null;
    }

    /** 后台检查更新。force=true 忽略缓存强拉;banner=true 有新版时弹主页横幅;cb 在 UI 线程回调结果(可能 null=失败)。 */
    private interface CheckCallback {
        void onResult(Result r);
    }

    private static void runCheck(final Activity act, final TextView tv, final boolean force,
                                 final boolean banner, final CheckCallback cb) {
        if (!force && latestJson != null
                && System.currentTimeMillis() - lastCheckAt < CHECK_INTERVAL_MS) {
            deliver(act, tv, parse(latestJson), banner, cb);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                String json = fetch();
                if (json != null) {
                    latestJson = json;
                    lastCheckAt = System.currentTimeMillis();
                }
                final Result r = json == null ? null : parse(json);
                if (act != null) {
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            deliver(act, tv, r, banner, cb);
                        }
                    });
                }
            }
        }, "xhs-savefix-upd").start();
    }

    private static void deliver(Activity act, TextView tv, Result r, boolean banner, CheckCallback cb) {
        if (tv != null) tv.setText(describe(r, act));
        if (r != null && banner && r.versionCode > MODULE_VERSION_CODE
                && act != null && r.versionCode != dismissed(act)) {
            showBanner(act, r);
        }
        if (cb != null) cb.onResult(r);
    }

    private static String describe(Result r, Activity act) {
        if (r == null) return "检查失败:所有镜像都不可达,稍后再试";
        if (r.versionCode <= MODULE_VERSION_CODE) return "已是最新版本 v" + MODULE_VERSION;
        StringBuilder sb = new StringBuilder("发现新版本 v").append(r.versionName)
                .append("（当前 v").append(MODULE_VERSION).append("）");
        if (r.changelog != null && !r.changelog.isEmpty()) sb.append("\n\n").append(r.changelog);
        if (act != null && r.versionCode == dismissed(act)) sb.append("\n\n（你已选择忽略此版本）");
        return sb.toString();
    }

    // ---------- 主页横幅 ----------

    private static void showBanner(Activity act, final Result r) {
        try {
            ViewGroup content = (ViewGroup) act.findViewById(android.R.id.content);
            if (content == null || content.findViewById(BANNER_ID) != null) return;

            LinearLayout bar = new LinearLayout(act);
            bar.setId(BANNER_ID);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setBackgroundColor(0xFFFF2442); // 小红书红
            bar.setPadding(dp(act, 14), statusBarH(act) + dp(act, 8), dp(act, 10), dp(act, 8));
            bar.setClickable(true);

            TextView tv = new TextView(act);
            tv.setText("🔄 XHS SaveFix 有新版 v" + r.versionName
                    + "（当前 v" + MODULE_VERSION + "）· 点击更新");
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(14f);
            bar.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView close = new TextView(act);
            close.setText("✕");
            close.setTextColor(0xFFFFE1E6);
            close.setTextSize(16f);
            close.setPadding(dp(act, 14), 0, dp(act, 6), 0);
            bar.addView(close, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            bar.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openUrl(v.getContext(), r.downloadUrl);
                }
            });
            close.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setDismissed(act, r.versionCode);
                    ViewGroup barV = (ViewGroup) v.getParent();
                    ViewGroup parent = barV != null ? (ViewGroup) barV.getParent() : null;
                    if (parent != null) parent.removeView(barV);
                }
            });

            content.addView(bar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
            XposedBridge.log(TAG + "更新横幅已展示: v" + r.versionName);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "show banner failed: " + t);
        }
    }

    // ---------- 设置页入口 ----------

    private static void addSettingsPill(final Activity act) {
        try {
            ViewGroup content = (ViewGroup) act.findViewById(android.R.id.content);
            if (content == null || content.findViewById(PILL_ID) != null) return;

            TextView pill = new TextView(act);
            pill.setId(PILL_ID);
            pill.setText("SaveFix ⚙");
            pill.setTextColor(Color.WHITE);
            pill.setTextSize(12f);
            pill.setTypeface(null, Typeface.BOLD);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(act, 14), dp(act, 8), dp(act, 14), dp(act, 8));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xCCFF2442);
            bg.setCornerRadius(dp(act, 20));
            pill.setBackground(bg);
            pill.setElevation(dp(act, 4));
            pill.setClickable(true);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.rightMargin = dp(act, 16);
            lp.bottomMargin = dp(act, 40);
            pill.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPanel(act);
                }
            });
            content.addView(pill, lp);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "add pill failed: " + t);
        }
    }

    private static void showPanel(final Activity act) {
        final TextView tv = new TextView(act);
        tv.setTextSize(14f);
        tv.setLineSpacing(dp(act, 2), 1f);
        tv.setPadding(dp(act, 20), dp(act, 8), dp(act, 20), dp(act, 4));

        // 动作行:检查更新 | GitHub 开源 | 去下载(有新版才显示) | 忽略此版本(有新版才显示)
        final LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.setPadding(dp(act, 12), dp(act, 6), dp(act, 12), dp(act, 4));

        final TextView dlBtn = chip(act, "去下载");
        final TextView ignBtn = chip(act, "忽略此版本");
        dlBtn.setVisibility(View.GONE);
        ignBtn.setVisibility(View.GONE);

        TextView checkBtn = chip(act, "检查更新");
        checkBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tv.setText("当前 v" + MODULE_VERSION + "\n正在检查更新…");
                runCheck(act, tv, true, false, new CheckCallback() {
                    @Override
                    public void onResult(Result r) {
                        boolean isNew = r != null && r.versionCode > MODULE_VERSION_CODE;
                        dlBtn.setVisibility(isNew ? View.VISIBLE : View.GONE);
                        ignBtn.setVisibility(isNew ? View.VISIBLE : View.GONE);
                    }
                });
            }
        });
        TextView ghBtn = chip(act, "GitHub 开源");
        ghBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl(act, REPO_URL);
            }
        });
        dlBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Result r = latestJson == null ? null : parse(latestJson);
                if (r != null) openUrl(act, r.downloadUrl);
            }
        });
        ignBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Result r = latestJson == null ? null : parse(latestJson);
                if (r != null) setDismissed(act, r.versionCode);
                dlBtn.setVisibility(View.GONE);
                ignBtn.setVisibility(View.GONE);
            }
        });
        row.addView(checkBtn, chipLp(act));
        row.addView(ghBtn, chipLp(act));
        row.addView(dlBtn, chipLp(act));
        row.addView(ignBtn, chipLp(act));

        final LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(act)
                .setTitle("XHS SaveFix v" + MODULE_VERSION)
                .setView(new ScrollViewWrap(act, box))
                .setNegativeButton("关闭", null)
                .show();

        runCheck(act, tv, false, false, new CheckCallback() {
            @Override
            public void onResult(Result r) {
                boolean isNew = r != null && r.versionCode > MODULE_VERSION_CODE;
                dlBtn.setVisibility(isNew ? View.VISIBLE : View.GONE);
                ignBtn.setVisibility(isNew ? View.VISIBLE : View.GONE);
            }
        });
    }

    /** 面板小按钮(圆角 chip) */
    private static TextView chip(Activity act, String text) {
        TextView c = new TextView(act);
        c.setText(text);
        c.setTextSize(13f);
        c.setTextColor(Color.WHITE);
        c.setPadding(dp(act, 14), dp(act, 7), dp(act, 14), dp(act, 7));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFF2442);
        bg.setCornerRadius(dp(act, 16));
        c.setBackground(bg);
        c.setClickable(true);
        return c;
    }

    private static LinearLayout.LayoutParams chipLp(Activity act) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(act, 8);
        return lp;
    }

    /** ScrollView 包一层,防止 changelog 过长 + AlertDialog 触摸失效问题 */
    private static final class ScrollViewWrap extends android.widget.ScrollView {
        ScrollViewWrap(Activity a, View child) {
            super(a);
            addView(child, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent ev) {
            requestDisallowInterceptTouchEvent(true);
            return super.onTouchEvent(ev);
        }
    }

    // ---------- 忽略版本持久化 / 工具 ----------

    private static File ignoreFile(Activity act) {
        return new File(act.getFilesDir(), "xhs_savefix_ignore.txt");
    }

    private static int dismissed(Activity act) {
        try {
            byte[] b = new byte[32];
            java.io.FileInputStream fis = new java.io.FileInputStream(ignoreFile(act));
            int n = fis.read(b);
            fis.close();
            return n <= 0 ? 0 : Integer.parseInt(new String(b, 0, n).trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void setDismissed(Activity act, int vc) {
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(ignoreFile(act));
            fos.write(String.valueOf(vc).getBytes("UTF-8"));
            fos.close();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "persist dismissed failed: " + t);
        }
    }

    private static void openUrl(android.content.Context ctx, String url) {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "open url failed: " + url);
        }
    }

    private static int dp(Activity a, float v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int statusBarH(Activity a) {
        int id = a.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? a.getResources().getDimensionPixelSize(id) : dp(a, 24);
    }
}
