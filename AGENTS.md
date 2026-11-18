# CatsCo Android

- Native Kotlin / Jetpack Compose app. Do not replace the app with a WebView of the web demo.
- iOS source reference: ../catsco-ios at 3fcb674c94dc1b6349878125b0a2a44244914383. Preserve original CatsCo identity, drawer hierarchy and outlined composer. Use native Android back, keyboard, accessibility and system bars.
- Never put fixtures, preview login or API-write bypasses in Release; use src/debug and src/release implementations.
- Session secrets stay encrypted by Android Keystore, never in logs, URLs, source files or backups. Do not forward credentials through redirects.
- No invented assistant replies or fake task success. Missing modules must be described honestly.
- Build/test commands: ./gradlew :core:test :app:assembleDebug :app:lintDebug. UI tests require a real emulator/device. Never report browser screenshots as native verification.
- No signing assets in Git. Publishing and release-signing credentials are separate explicit user actions.
