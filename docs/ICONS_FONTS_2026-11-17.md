# 图标统一与普惠体 2.0 · 2026-11-17

## 当前结果

本轮校准版已构建并安装到安卓真机。测试结束后已启动正常 MainActivity。

用户本轮补充要求：所有操作图标以“模型用量”的大小和粗细为准。该要求覆盖旧的菜单 20dp、附件 34dp、发送可用态 2.1px 描边等历史值。

- 图标库共 84 个 SVG：原库 61 个、补齐 22 个、发送状态别名 1 个；映射为 51 个 Android 资源。保留完整原库供后续使用，未新增业务入口。
- 操作图标使用共享 `CatsCoMobileSpec.actionIconSize = 23.dp`，以模型用量为基准。等效线宽为 1.5/24，基准视觉长边 17.5/24，圆形略作视觉补偿；缩放同时反向补偿线宽。37 个原填充轮廓重绘为圆端描边。
- 本地完整工程的 `design-assets/icons-complete` 保存图标清单、来源和校准文件。
- 本地 `design-assets/icons-20261117` 保存原图标包。公开构建所需的资源准备步骤见 [README](../README.md#准备-android-资源)。
- App 使用用户提供的阿里巴巴普惠体 2.0 原始 TTF：Regular 400、Medium 500、SemiBold 600、Bold 700。字体哈希与原文件对应记录在 `design-assets/fonts/manifest.json`。
- Compose 所有类型角色、自定义输入框、消息原生 TextView、Markdown 标题/强调/内联代码、表格测量、代码块、工具输出、原生主题与桌面组件均已接入字体。知识库通过允许源上的拦截路径读取打包字体，未放开 WebView 文件访问或跨源限制。
- CatsCo 字体 Logo 和品牌图片保持原样，两份品牌图片哈希与本轮前备份完全一致。
- 字体增加了包体；当前 Debug APK 为 44274559 字节（约 44.3 MB），完整四字重 TTF 共 33,379,448 字节，未做字形裁剪。

## 验证

- `core:test`、`app:assembleDebug`、`app:lintDebug`、`app:assembleDebugAndroidTest` 全部通过。Lint 0 error、37 warning；本轮未宣称清除既有警告。
- 62 项原生回归全部通过，耗时 216.054 秒。
- `TypographyReviewTest` 对 34 个入口执行浅色、深色及 1.3 倍字号检查；对取得的 Compose 文本布局断言字体为打包普惠体。菜单包含 2 倍字号测试。
- `IconFontReviewTest` 在真机绘制全套 Android 图标，检查原生 Markdown 实际 Typeface 和文字选择；字体及图标专项两项通过。
- 同时通过 Markdown、模块状态、右滑返回、键盘/输入栏、头像与首页、登录、员工设置、菜单交互回归。
- 已查看真实账号首页、原生浅深色图标表、原生 Markdown、浅深色设置页和大字号设置页截图。
- 知识库托管正文暂无可访问账号内容，仍未做真实托管正文的视觉验收；本轮仅完成字体接入与已有原生边界测试。
- 图标目录的静态 SVG 已用本机 Sharp 渲染检查；浏览器控制连接未提供可用浏览器，因此不将网页截图称为原生验收。HTTP 预览可访问。

## 日志和证据

- 构建：`build/reviews/icons-fonts-calibrated-20261117-build.log`
- 原生测试：`build/reviews/icons-fonts-calibrated-20261117-tests.log`
- 图标、Markdown 原生截图：`build/reviews/icons-fonts-native-20261117/`
- 全页面排版截图：`build/reviews/puhuiti-calibrated-20261117/`
- 菜单截图：`build/reviews/puhuiti-menus-20261117/`
- 真实账号首页：`build/reviews/icons-fonts-calibrated-real-20261117.png`
- 本轮前源代码与资源备份：`build/reviews/icons-fonts-20261117-before.zip`
- 本轮前安装包：`build/reviews/icons-fonts-20261117-before.apk`
- 当前 APK SHA-256：`071aa369e0dfca228d4b393a9150705fee054652be63b11bf38abf49340c688e`

## 交付及维护

- 图标预览：`http://127.0.0.1:5198/preview.html`
- 真机预览：`http://127.0.0.1:5193/`
- 重新生成图标：`python scripts/sync-icon-library.py`；旧 `sync-android-icons.cjs` 入口现已转发到此脚本，防止旧草图覆盖新库。
- 校准脚本 `calibrate-icons.cjs` 使用本机 Sharp；可通过 `CATSCO_SHARP_MODULE` 和 `CATSCO_NODE` 覆盖本机路径。几何补齐与原轮廓重绘分别位于 `sync-icon-library.py` 与 `icon_optical_source.py`。
- 来源与许可状态保留在 manifest，不将原库图标宣称为原创。本轮无发布、签名或远端上传。
