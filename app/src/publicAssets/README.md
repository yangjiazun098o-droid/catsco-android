# 公开测试版资源

干净检出构建时使用本目录中的资源。带有完整内部资源的工程使用 `app/src/main/res` 中的版本。

- 字体使用 Noto Sans SC，对应 Light、Regular、Medium、SemiBold 和 Bold 五个字重。TTF 从 Google Fonts 的可变字体生成静态字重，沿用 App 的 Android 资源名称。[上游字体](https://github.com/google/fonts/tree/2894aab31764f10f29c421bdfd2340d3b382d384/ofl/notosanssc) · [SIL Open Font License](assets/third-party/NotoSansSC-OFL.txt)
- 图标使用项目自绘的 Android VectorDrawable 和转绘自 Lucide 的资源。Lucide 资源在 XML 中标注上游图标名。[上游图标](https://github.com/lucide-icons/lucide/tree/149eba0aea48178a5ab1efe2c701de1da94f4be8/icons) · [Lucide 与 Feather 许可证](assets/third-party/Lucide-LICENSE.txt)

两份许可证随 APK 打包在 `assets/third-party/`。
