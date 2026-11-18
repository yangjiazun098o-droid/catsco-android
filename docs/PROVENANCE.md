# Source authority

Native iOS repository: https://github.com/buildsense-ai/catsco-ios (private), revision 3fcb674c94dc1b6349878125b0a2a44244914383, Record approved iOS 17 TestFlight build 18.

- Composition and tokens: DESIGN.md, CatsCo/Design/Components.swift, Features/TaskHomeView.swift, ConversationsView.swift, SettingsView.swift.
- Auth endpoints and response fields: CatsCo/App/CatsCoApp.swift authenticate/restore/refresh.
- HTTP origin validation: CatsCoCore/Sources/CatsCoCore/APIClient.swift.
- Record field fallbacks: CatsCoCore/Sources/CatsCoCore/Models.swift.
- Debug employees and task list: CatsCo/App/PreviewContent.swift.
- drawable-nodpi/catsco_mark.png and catsco_wordmark.png are unchanged copies from CatsCo/Assets.xcassets/CatsCoMark.imageset and CatsCoWordmark.imageset. Embedded metadata is retained. No newly generated logo.

This is a new Kotlin implementation; SwiftUI cannot be compiled for Android. Previous browser demo remains in the webapp repository and is not embedded in this application.
