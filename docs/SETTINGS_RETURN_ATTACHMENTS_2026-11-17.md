# 设置右滑返回与附件入口 · 2026-11-17

用户反馈从设置右滑回首页时闪动，并提供附件入口参考，要求照片、拍照、文件三个图标放大、采用参考中的实心造型并添加背景。

## 实现

- Composer 的附件展开状态改为按页面保存，并置于账号隔离的 SaveableStateHolder 内。返回首页时不再突然收起附件面板，避免首页头像和问候语改变位置。
- 右滑预览从半分辨率改为窗口原分辨率，避免切回真实页面时字形和图标锐度突变。缓存最多 8 张、32MiB，至少保留一张；仍不重复挂载页面或发起请求。
- 通过窗口 OnDraw 更新预览，覆盖子图层和原生 Markdown 独立重绘；合并期间发生的绘制请求，保留最后一次更新。等待绘制提交后 PixelCopy，并检查页面、窗口区域和尺寸，丢弃过期结果。
- 返回提交后保留相同预览，待目标页面已绘制并经过后续两帧再交给真实内容，遮住 Scaffold 和附件栏恢复时的瞬时布局变化。
- 附件入口使用独立的实心相机、照片、文件夹矢量资源，36dp 图标、64dp 背景、16dp 圆角，使用主题色。保留三个入口原有动作和顺序。
- 完整图标库新增上述三种附件专用造型，共 87 个 SVG、55 个 Android 映射，已同步生成器、预览和 ZIP。普通操作图标继续使用 23dp 描边版本。

## 验证资料

最终构建、core 单元测试、Lint 和测试 APK 编译通过；26 项相关原生回归通过（64.117 秒），覆盖右滑/预测返回取消与完成、附件展开状态与预览更新、输入/发送/停止以及侧栏和消息交互。最终版本已安装到 Redmi Note 13R（Android 15）。深浅色附件入口均已真机截图检查，并恢复用户的浅色外观。最终展开/收起录屏及衔接帧图已检查，没有再出现首页位置跳动。

- 构建日志：`build/reviews/settings-swipe-attachments-20261117-build.log`
- 原生回归：`build/reviews/settings-swipe-attachments-20261117-tests.log`
- 问题录屏：`build/reviews/swipe-flash-before-20261117/`、`swipe-flash-collapsed-before-20261117/`
- 修复录屏：`build/reviews/swipe-flash-expanded-after-20261117/`、`swipe-flash-collapsed-after-20261117/`
- 附件深浅色截图：`build/reviews/attachments-filled-light-20261117.png`、`attachments-filled-dark-20261117.png`

新增原生回归覆盖进入设置前后的附件展开状态、首页问候语位置，以及收起附件后滑动过程中预览的实际像素。
