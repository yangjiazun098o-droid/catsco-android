# Codex 原生安卓预览

入口：<http://127.0.0.1:5189/>。本机运行 Android Emulator 的原生画面经 adb H.264 连续传输，并通过 adb 点击控制；不是重新实现的网页界面。设备显示与传输均设为 540×1200（210 dpi）、1.5 Mbps 以减轻模拟器负担，实际帧率仍取决于主机和设备，不能据此判断 60fps 动画流畅度。支持点击、拖动手势、系统返回、打开 App、切换系统深浅主题和暂停画面。电脑键盘直接输入和音频尚未桥接；复杂交互由设备测试或独立模拟器窗口验证。

真机入口：<http://127.0.0.1:5190/>。手机连接 USB、开启 USB 调试并允许这台电脑后，运行 `./scripts/start-phone-preview.ps1`。脚本只接受一台已授权的实体手机，读取屏幕尺寸、覆盖安装 Debug APK 并打开 CatsCo；不会修改手机分辨率。手机可能另行要求开启“通过 USB 安装”并确认安装。Codex 内的画面来自手机录屏，镜像延迟与 App 自身流畅度应分别判断。

以后修改安卓代码后，运行 `./scripts/update-phone.ps1` 编译并覆盖安装；已经有新 APK 时可用 `./scripts/update-phone.ps1 -SkipBuild`。覆盖安装保留当前应用数据。真机预览服务独立于模拟器的 5189 端口。

## 固定设备

- AVD：CatsCo_API_36，Pixel 7，1080×2400，Android 16 / API 36，Google APIs x86_64 镜像 revision 7。
- Android Emulator 37.2.13，WHPX 硬件加速已检测可用；SwiftShader 软件图形、关闭 Vulkan、2 个虚拟 CPU、启动参数内存 2048 MB。主机 GPU 模式在这台机器上出现过启动卡住和严重掉帧。
- serial：emulator-5556；adb 设备命令必须指定这个 serial，避免更新其他手机。
- 工具：E:/CodexProjects/.tools/android/sdk。
- AVD：E:/CodexProjects/.tools/android/avd；模拟器运行数据：E:/CodexProjects/.tools/android/emulator-home。
- 画面服务只监听 127.0.0.1:5189，设备操作只接受同源 POST 和固定操作，不提供任意 shell 命令接口。

## 每轮更新

在项目目录执行：

```powershell
# 模拟器和预览服务均未运行时：启动设备，安装现有 APK，启动本机画面服务
./scripts/start-native-preview.ps1

# 修改 Kotlin 后：编译 Debug，覆盖安装并打开 App
./scripts/update-device.ps1

# 仅重新安装已编译 APK
./scripts/update-device.ps1 -SkipBuild

# 设备测试（测试框架可能卸载调试应用，结束后重新安装）
./scripts/build-local.ps1 -Tasks ':app:connectedDebugAndroidTest'
./scripts/update-device.ps1 -SkipBuild
```

默认后台无窗口运行。若需要独立模拟器窗口，应先关闭当前模拟器，再执行 `./scripts/start-emulator.ps1 -ShowWindow`。不要同时在相同端口启动两个实例。

更新会重启 Activity，当前尚未持久化的输入和调试状态可能丢失；当前版本未承诺应用热更新。Release 不包含调试数据入口。

## 本次验收（2026-11-10）

- Android 16 启动完成，WHPX operational；APK 安装 Success，MainActivity 启动成功。
- connectedDebugAndroidTest：1 项通过，0 失败、0 跳过；Debug 本地入口、侧栏与 SkillHub 导航。
- 通过浏览器画面实际点击“进入调试预览”、打开侧栏、点击系统返回、切换深色并确认实际截图。
- 预览页面无脚本错误，原生截图位于 build/emulator/native-login.png、native-home.png、native-sidebar.png、native-home-dark.png（图片包含浏览器预览外框）。
- 首次开机约 113 秒，曾出现 System UI 等待弹层，处理后设备测试通过，最终截图无该弹层。首次启动和资源紧张时应等待系统稳定后操作。
- 未验证真实账号登录、业务发送、实体手机或完整系统交互。
