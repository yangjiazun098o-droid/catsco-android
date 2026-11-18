# Android migration status — 2026-11-13

## Connected in the Android code

- HTTPS login and restore, email-code registration, password reset, existing employee invitation-code field.
- Native history, date sections, filtering, rename, local pin/hide/multi-select, and four equal context-menu rows.
- History pagination, structured attachments, native Markdown headings/lists/code blocks, horizontally scrollable tables, execution-plan revisions, socket/history deduplication, foreground WebSocket lifecycle.
- Agent-turn presentation with paired tool inputs/results, persistent plan cards, rounded reply panels, hanging list indentation and light/dark typography. File cards separate system preview from document-picker saving.
- Message-content search is reachable from the drawer's query results and keeps the entered query. Existing conversations share the home screen's header capsule and current-topic file/device routes.
- New agent-task groups, message sends with persistent client IDs, uncertain-result recovery, stop requests when the socket is available.
- Account/server-scoped encrypted drafts, uploaded attachment metadata, directory and message caches.
- Android document/photo pickers, external camera capture, raw upload, protected downloads, system speech-to-text, text/file share intake and account-validated conversation deep links.
- Shared apps, SkillHub search/details and revision-based skill installation.
- Device directory, connect codes/status, audit records and revision-based conversation device selection.
- Profile editing, appearance, feedback, contacts/groups/friend requests, owner employee editing, prompt/model settings, service-agent subscriptions.
- Usage, existing commercial entitlements/orders, model access credentials and invitation redemption, cloud-worker directory.
- Native desktop task widget displaying the last synchronized tasks. Widget content clears on sign-out.

## Compatibility and remaining work

- Some deployed servers accept WebSocket JWTs only in the URL query. Android keeps session credentials out of URLs. A rejected socket handshake first validates `/api/me`; a valid account falls back to five-second foreground message synchronization instead of being signed out. True streamed deltas and stop commands require backend support for the Authorization header.
- The 10-credit inviter / 20-credit friend reward needs a server referral/ledger contract. The existing `bot_invite_code` registers an employee invitation and must not be presented as that reward mechanism.
- Android remote push registration/background delivery remains separate from the existing iOS push API. Notification permission/system settings and the snapshot widget are native Android features.
- Audio recording/transcription beyond the installed Android speech-recognition service, full rich-message rendering parity and complete administrator/cloud-worker provisioning flows remain future work.
- Projects and contacts stay out of the mobile drawer, as requested. Contacts remain available in Settings.
- Core and device parser tests are automated. Menu instrumentation is compiled but its test Activity startup has not completed on the Xiaomi device. Native menu screenshots/layout bounds independently verify four equal rows.
- Business writes are connected through their in-app controls. Production task sends, registrations, purchases and account mutations have not been exercised in this verification run.
- Release signing, store assets, privacy review and store submission remain separate release work.
