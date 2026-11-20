# CatsCo Android

2026-11-18 的安卓源码包含联系人固定搜索、创建群组时的键盘避让和取消重置，以及打开侧栏时收起键盘并保留草稿。这三项已通过真机复查。其余模块范围见 [迁移状态](docs/ROADMAP.md)。

安卓手机可从 [GitHub Releases](https://github.com/yangjiazun098o-droid/catsco-android/releases) 下载测试版 APK，安装后用自己的 CatsCo 账号登录。最低支持 Android 8。

从 Preview 3 开始，测试版可在「设置 → 帮助与连接 → 检查更新」下载下一版，由安卓安装程序确认。每次发布使用递增的 `-PcatscoPreviewNumber=N` 构建，沿用测试版签名，再执行 `scripts/publish-preview.ps1 -PreviewNumber N -Apk <CatsCo-Android-Preview.apk> -Notes <release-notes.md>`。脚本会发布 GitHub 预发行版、校验上传结果并更新 App 使用的版本清单。

安卓原生工程，Kotlin + Jetpack Compose。视觉与结构参照 `buildsense-ai/catsco-ios` 的 `3fcb674c94dc1b6349878125b0a2a44244914383`，详见 [来源记录](docs/PROVENANCE.md)。

## 工程结构

- `app`：Compose 原生界面、Android 生命周期、HTTP 实现、Android Keystore 加密会话存储。
- `core`：不依赖 Android 的服务地址验证、数据模型与 repository/store 契约，JVM 单元测试。
- `app/src/debug`：只读调试数据仓库；`app/src/release` 不包含示例账号或预览仓库实现。
- `docs/ROADMAP.md`：已完成与后续迁移范围。

## 开发环境

安装 JDK 17 和 Android SDK 36。最低系统版本为 Android 8（API 26）。Gradle Wrapper 固定使用 Gradle 8.13，依赖版本见 `gradle/libs.versions.toml`。在 Android Studio 打开本目录，或在终端中配置 `JAVA_HOME` 和 `ANDROID_HOME`。

### 准备 Android 资源

从公开仓库直接构建会使用 Noto Sans SC 和 Lucide 图标。仓库同时提供 25 个项目图标的 [SVG 源文件](design-assets/icons/svg) 和对应的 Android 矢量资源。[公开版资源与许可](app/src/publicAssets/README.md)

如需构建使用普惠体和原图标的内部版本，从有权使用的完整工程副本一次导入 5 个字体文件及 29 个图标文件。命令会先核对所有文件的 SHA-256，再写入 Android 资源目录。[原图标清单](design-assets/icons/manifest.json)

```sh
python scripts/restore-mobile-assets.py /path/to/full-catsco-android
```

阿里巴巴普惠体 2.0 的 Light、Regular、Medium、SemiBold 和 Bold 也可通过官方渠道单独获取。将五个 TTF 按 [字体清单](design-assets/fonts/manifest.json) 的 `resource` 字段命名并置于 `app/src/main/res/font/`。如果使用工程原配格式的 ZIP，可以运行 `python scripts/import-puhuiti.py <字体ZIP路径>`。[阿里巴巴字体官网](https://www.alibabafonts.com/)

### 构建与验证

```powershell
./gradlew.bat :core:test :app:assembleDebug :app:lintDebug
```

macOS 和 Linux 使用 `./gradlew`。真机或模拟器连接后执行 `:app:connectedDebugAndroidTest`。GitHub Actions 使用公开版资源构建并运行核心测试和 Android Lint。

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`，调试应用 ID `cc.catsco.android.debug`，与未来正式版分开安装。Release ID 暂定 `cc.catsco.android`；第一次上架前由所有者确认，之后不要随意更改。

## 使用范围

Debug 登录页可选“进入调试预览”，使用原仓库的员工/历史列表，不访问业务 API。Release 不显示这个入口。登录页的正常“登录”会在用户点击后请求自己填写的 HTTPS 服务器；默认 `https://app.catsco.cc`，与 iOS 使用同一服务。

已接入消息与任务、附件、应用与 SkillHub、设备、联系人与员工管理、个人资料和模型设置等现有接口。会话、草稿和缓存使用 Android Keystore 加密，并按账号与服务器隔离。相机、照片、文件、语音转文字、系统分享和桌面任务组件使用安卓原生能力。

当前可安装测试，尚需完成正式发布验收。旧服务器的 WebSocket 鉴权可能回退到前台自动同步，后台推送、10/20 credit 好友邀请奖励和完整富消息能力仍需后端或后续功能支持。发送、注册、修改与兑换已连接真实业务接口，这些写入流程尚未在生产账号上实测。

## 预览与发布

- Android Studio 可查看 `TaskHome` 的 Compose `@Preview`。
- 原生设备镜像与安装步骤见 [本地预览文档](docs/NATIVE_PREVIEW.md)。
- Release AAB、签名密钥、商店素材、隐私政策及提交审核属于后续发布工作，本次不生成或保存发布签名密钥。
