# XHS SaveFix · 小红书解除「作者关闭保存」

一个极简 LSPosed 模块：解除小红书（`com.xingin.xhs`）笔记图片的「作者已关闭下载/保存」限制，让受限笔记也能长按保存到相册。

> 仅供个人学习与逆向研究使用。请尊重原作者版权与平台规则，勿用于侵权或商业用途。

## 原理（一句话）

小红书在笔记数据里下发 `MediaSaveConfig`，其 JSON 字段 **`disable_save: true`** 表示作者关闭了保存。客户端在下载/保存路径（`DownloadController.onDownloadClick`）里检查 getter `MediaSaveConfig.b()`（即 `disableSaveMedia`），为 `true` 就弹 toast 并 `return` 拦截。

本模块把 `b()`（以及水印开关 `c()`）**强制返回 `false`**，判断永不命中，保存照常执行。

完整逆向过程见 **[逆向分析与思路.md](逆向分析与思路.md)**。

## 适用

- 小红书 `com.xingin.xhs`，已在 **v9.33.4** 验证。
- 需要 Android + Root + **LSPosed**（或兼容的 Xposed 框架）。
- 小红书带反 Frida（`libmsaoaidsec.so`），外部 Frida 注入会被掐断；但 LSPosed 模块在 App 进程内、以 App 身份运行，过反调试。

## 安装使用

1. 下载 [Releases](../../releases) 里的 `xhs-savefix.apk` 并安装（或自行构建，见下）。
2. 在 **LSPosed** 中启用 **XHS SaveFix** 模块，作用域勾选 **小红书 `com.xingin.xhs`**。
3. 强制停止小红书后重新打开，使模块生效。
4. 打开受限笔记 → 长按图片 → 保存到相册。

加载成功时 LSPosed 日志可见：

```
LSPosedFramework: (com.xingin.xhs)[com.chekayo.xhssavefix] [xhs-savefix] hooked MediaSaveConfig.b() (disableSaveMedia) -> false
```

## 自行构建

需要 JDK 11+、Android SDK（build-tools + 一个 platform 的 `android.jar`）。

```bash
ANDROID_HOME=~/Android/Sdk ./build.sh
# 产物: xhs-savefix.apk
```

纯 Java，无 native 依赖。核心代码就一个文件：[`app/src/main/java/com/chekayo/xhssavefix/XhsSaveFix.java`](app/src/main/java/com/chekayo/xhssavefix/XhsSaveFix.java)。

## 赞赏

如果这个小工具帮到了你，欢迎请作者喝杯咖啡 ☕，非常感谢！

<img src="images/reward.png" alt="微信赞赏码" width="240">

## 免责声明

本项目为逆向学习产物，仅在使用者自有设备上解除客户端 UI 限制，不修改、不访问小红书服务器，不涉及账号或数据破解。是否保存、如何使用所保存的内容由使用者自行承担法律与道德责任，作者不对任何滥用负责。

## License

[MIT](LICENSE)
