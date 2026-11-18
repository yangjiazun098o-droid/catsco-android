# 侧栏与输入栏调整 · 2026-11-17

已构建并安装到安卓真机，验收后恢复正常 MainActivity。

- 打开侧栏为三条横线；关闭侧栏为三个点加三条横线。资源为 e_sidebar 和 e_sidebar_active，后者现在直接纳入 App 引用。
- 输入栏移除独立语音图标；右侧主按钮在空白输入时显示语音，有文字或附件时显示原来的向上箭头。运行中停止优先；发送/停止等待时不可重复点击。
- 语音沿用系统识别入口，结果填入草稿，不自动发送。已只读确认设备支持 `com.xiaomi.mibrain.speech/.asr.AsrActivity`；未录制用户语音。
- 保持上一轮 23dp 图标、等效 1.5px 描边、普惠体及品牌 Logo。
- 图标库仍为 84 个 SVG，Android 映射增加到 52 个；预览与完整 ZIP 已同步。

构建、core 单元测试、Lint、测试 APK 编译通过。36 项相关原生回归全部通过，83.171 秒，包括输入/附件/语音回调分派、发送中状态、停止优先级、主按钮边界、键盘、侧栏手势、菜单、首页和原生图标/字体。语音回调使用隔离数据检查，没有向外部语音服务发送音频。

证据：
- build/reviews/sidebar-voice-arrow-20261117-build.log
- build/reviews/sidebar-voice-arrow-20261117-tests.log
- build/reviews/composer-empty-voice-20261117.png
- build/reviews/composer-text-arrow-20261117.png
- build/reviews/sidebar-voice-arrow-real-20261117.png

真机预览 http://127.0.0.1:5193/ ，图标预览 http://127.0.0.1:5198/preview.html 。
