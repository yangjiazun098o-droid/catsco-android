# Message component verification — 2026-11-13

- Desktop reference was read from `catsco-plans-pr-20261027/webapp/src/widgets/chat-message.jsx`, together with the iOS message projection. Android now groups an Agent turn, pairs tool calls/results by ID, retains the latest plan, and keeps delivery plumbing out of the process list.
- Replies use a rounded neutral panel, 16sp body text with 25sp line height, separate heading/paragraph spacing, hanging list indentation and content-sized table columns. Small tables fill the available panel width. Wide tables scroll horizontally.
- The existing Agent name, process summary and plan card are separate components. File cards have preview and save actions. Preview delegates to an installed system app. Saving uses the Android document picker. A complete real-attachment save has not been exercised in this verification run.
- Eight core tests and thirteen Android message tests pass. The Android run covers parsing, grouping, result-ID matching, task/turn boundaries, runtime-plan precedence, failed plan updates and Chinese Markdown compatibility with code/escape preservation.
- `:core:test`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:lintDebug` and `:app:compileReleaseKotlin` pass. The final Debug APK is installed on Xiaomi 2406ERN9CC over wireless ADB.
- Native screenshots check light/dark reply panels, headings, lists, tables, persistent plans, collapsed/expanded process rows and a tool's input/result detail. The dedicated debug gallery is labelled as sample data and does not read the account or ship in Release.
- Real-account history was checked separately. Twenty-one tool steps are grouped under one process toggle, and legacy Chinese bold punctuation renders correctly. The right-side native preview is left on this real conversation.
- Evidence includes `build/emulator/message-body-list-table-final.png`, `message-body-panel-dark-final.png`, `message-review-light-final.png`, `message-review-expanded-final.png`, `message-review-tool-detail.png`, `message-live-company-expanded.png` and `message-live-body-delivery.png`. Earlier screenshots represent earlier visual iterations.

# Module integration verification — 2026-11-13

- `:core:test`, `:app:assembleDebug`, `:app:lintDebug`, `:app:compileReleaseKotlin` pass. The instrumentation APK also compiles.
- Six core tests pass, including history/socket deduplication, finalized-stream handling, cancellation and execution-plan revision ordering.
- Three Android parser tests pass on Xiaomi 2406ERN9CC through wireless ADB. They cover nested/encoded content blocks, encoded metadata, attachment ownership metadata and execution-plan revisions.
- Two native menu layout tests compile but did not complete test-Activity startup on this Xiaomi device. Do not report them as passed.
- Actual menu hierarchy/screenshot inspection after module integration shows four contiguous rows of 143 pixels each, total 572 pixels at 440 dpi (52dp × 4). Bounds are `[44,966][484,1109]`, `[44,1109][484,1252]`, `[44,1252][484,1395]`, `[44,1395][484,1538]`. The screenshot and hierarchy are saved in `build/emulator/modules-final-menu.png` and `.xml`.
- The module-integration APK is installed on the phone. Live account authentication was verified with user-provided credentials without writing them to project files or command output. Production mutations have not been exercised through tools.
- The preview at `http://127.0.0.1:5193/` mirrors actual native frames. Native screenshots verify login restoration, sidebar alignment, four-row menu, history/tool-process expansion, apps empty state, SkillHub list/detail, profile, device empty state, contacts, usage and commercial entitlements. Screenshots are saved under `build/emulator/modules-*.png`.
- Authenticated read-only responses were verified for profile, 14 agents, 117 conversations, 30 skills, 23 friends, usage, commercial entitlements and one order. Apps/devices/cloud workers returned empty collections. Service-agent catalog returned 403 for this account.
- Latest native checks also verify message-content search with a carried query, current-topic file navigation from the header capsule, parsed Markdown headings and table headers, and horizontal table scrolling. Evidence includes `modules-message-search-live.png`, `modules-chat-menu.png`, `modules-topic-files.png`, `modules-table-fixed.png` and `modules-table-scroll.png`. Invalid Markdown delimiters in stored text remain literal. Full rich-message parity is still pending.
- Current lint report has zero errors and 26 warnings, chiefly dependency-update/KTX suggestions. See the current XML/HTML report for details.
- Backend socket compatibility was checked against `ServeWS`: older implementations read the human JWT only from the query. Android uses a header and verifies HTTP authentication before falling back to periodic foreground synchronization.

The earlier foundation record below is historical.

# Foundation verification — 2026-11-10

Subsequent native-device verification: Android 16 emulator is now configured and connectedDebugAndroidTest passed (1 test, no failures/skips). Browser bridge displays the emulator's actual frame and forwards clicks/back/theme controls. See NATIVE_PREVIEW.md. The original build-only limitations below describe the earlier foundation run; real account and physical-device acceptance remain outstanding.

Environment: Windows 11 x64, Temurin JDK 17.0.21+9, Gradle 8.13, SDK platform 36 revision 2, build-tools 35.0.0. Tools installed under E:/CodexProjects/.tools/android with explicit user authorization; no system PATH changes.

Final command:

```powershell
./scripts/build-local.ps1 -Tasks ':core:test',':app:assembleDebug',':app:lintDebug',':app:compileReleaseKotlin',':app:assembleDebugAndroidTest'
```

Result: BUILD SUCCESSFUL, final incremental run 12 seconds, 100 tasks (19 executed / 81 up-to-date).

- Core tests: 2 passed, 0 skipped/failures/errors. HTTPS server normalization and rejection of HTTP/userinfo/path/query/fragment/invalid origins.
- Debug APK compiled; apksigner verifies APK signature v2. Application ID cc.catsco.android.debug, version 0.1.0-debug, minimum SDK 26 / target SDK 36.
- Release Kotlin compiled. javap inspection confirms the release DebugEntry.repository() returns null; the anonymous fixture repository class exists only under Debug output.
- Instrumentation test APK compiled. Tests were NOT executed: adb devices reported no devices. No emulator/system image was installed.
- Android lint: 0 errors, 12 warnings. Ten dependency/tool-version update suggestions and two SharedPreferences KTX style suggestions remain. The explicit synchronous commit checks are intentional for session persistence error reporting.
- Source logo SHA256 matches the original iOS asset exactly.
- No live account credentials used; authentication, session restore and directory API compatibility need account acceptance. No production business mutations executed.
- CI workflow is added locally but has not run on GitHub; no remote repository was created or pushed.

Debug APK: app/build/outputs/apk/debug/app-debug.apk, 19,263,252 bytes.

SHA256: BC593123C8921476ECEFCDA2FB56334CC46C241D98216D884FC89B378B30C70D

Reports:

- app/build/reports/lint-results-debug.html
- core/build/reports/tests/test/index.html
- app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

Validation limits: compilation/signature checks do not establish actual device installation, visual fidelity, accessibility, gestures, IME behavior, process recovery, Keystore behavior on hardware, login compatibility, or complete release readiness. Browser replica screenshots are not native evidence. No signed release AAB was produced.
