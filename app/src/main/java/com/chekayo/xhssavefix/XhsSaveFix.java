package com.chekayo.xhssavefix;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 小红书「作者关闭下载/保存」解除。
 *
 * 机制(v9.33.4 静态逆向):笔记数据带 com.xingin.entities.MediaSaveConfig,
 *   JSON 字段 "disable_save" -> 字段 disableSaveMedia,getter b()。
 *   下载/保存路径(DownloadController.onDownloadClick, 类 ap8.b)里:
 *       if (note.Q9().e1().b()) { toast(2131828069); return; }   // 作者开启=true 时拦截
 *   把 b() 强制返回 false,检查就永远不命中 -> 受限笔记也能保存。
 *
 * 仅作用于 com.xingin.xhs。XHS 带反 frida(libmsaoaidsec),外部 frida 进不去;
 * 但 LSPosed 模块在 app 进程内、以 app 身份运行,过反 frida。
 */
public class XhsSaveFix implements IXposedHookLoadPackage {

    private static final String XHS = "com.xingin.xhs";
    private static final String CFG = "com.xingin.entities.MediaSaveConfig";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!XHS.equals(lpparam.packageName)) return;

        // b() = disableSaveMedia(作者关闭保存)。强制 false = 解除限制。
        try {
            XposedHelpers.findAndHookMethod(CFG, lpparam.classLoader, "b", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(param.getResult())) {
                        param.setResult(Boolean.FALSE);
                    }
                }
            });
            XposedBridge.log("[xhs-savefix] hooked MediaSaveConfig.b() (disableSaveMedia) -> false");
        } catch (Throwable t) {
            XposedBridge.log("[xhs-savefix] hook b() failed: " + t);
        }

        // c() = disableWaterMark。一并去掉强制水印(可选,作者关保存通常也强水印)。
        try {
            XposedHelpers.findAndHookMethod(CFG, lpparam.classLoader, "c", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(param.getResult())) {
                        param.setResult(Boolean.FALSE);
                    }
                }
            });
            XposedBridge.log("[xhs-savefix] hooked MediaSaveConfig.c() (disableWaterMark) -> false");
        } catch (Throwable t) {
            XposedBridge.log("[xhs-savefix] hook c() failed: " + t);
        }
    }
}
