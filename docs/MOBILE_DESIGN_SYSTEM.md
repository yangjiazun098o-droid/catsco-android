# CatsCo Android Mobile Design System

This native Compose system adapts the desktop CatsCo visual language to phone use. Desktop values are a visual reference; mobile hit areas and system behaviors take priority.

## Foundations

- Surfaces: app background `#F8F8F8`, raised panels `#FFFFFF`, borders `#E4E4E8`, primary text `#1F1F1F`, secondary text `#747479`.
- Desktop-inspired background layers use `background` for the page, `surfaceContainerLow` for the drawer, `surface` for content cards, and `surfaceContainerHigh` for the composer, attachment tiles and floating menus. Light uses `#F8F8F8` / `#FCFCFC` / `#FFFFFF` / `#FFFFFF`; dark uses `#0F0F0F` / `#1A1A1A` / `#1A1A1A` / `#232325`, with `#303032` borders. Foreground and brand colors follow the existing roles.
- Brand: CatsCo mint `#29BC95`; semantic danger uses the Material error color.
- Shape: 24dp settings/list groups, 12dp editing fields and file cards, 20dp home Agent avatars, 28dp composer shell. Use each component's current token for its own role.
- Spacing: 4, 8, 12, 16 and 24dp steps. Prefer these shared values for new spacing.
- Type: sidebar titles 15sp, body 16sp/25sp, composer 17sp, contextual menu labels 17sp/24sp with normal weight, supporting dates 12sp, settings/module section labels 16sp/24sp medium.
- Native type roles use the bundled Alibaba PuHuiTi 2.0 with zero letter spacing. Regular / Medium / SemiBold / Bold map to the supplied 400 / 500 / 600 / 700 font files. Compose, native Markdown, tables, code/tool output, measurements, hosted knowledge and widget text share this family. The existing CatsCo wordmark image remains unchanged. Secondary and tertiary page titles use 18sp/26sp medium, dialog titles 20sp/28sp medium, form/list labels 16sp/24sp, supporting text 14sp/22sp and metadata 13sp/20sp. Native action buttons use 15sp/22sp medium. Task headers retain their compact 15sp/22sp semibold role.
- Enabled neutral action icons use `onSurface`, matching the drawer collapse button in light and dark themes. Apply this to header actions, navigation, composer controls, menus, filters and attachment actions. Explicit icon tints use 38% opacity when their action is disabled. Supporting text and informational icons use `onSurfaceVariant`; brand and destructive actions follow their semantic colors.
- Agent identity surfaces read the selected employee's configured `avatarUrl`, using the account-scoped image loader and shared media URL resolution. A missing avatar address uses the Agent fallback icon until an image is supplied.

## Mobile Components

- The icon source is `design-assets/icons-complete`: 61 provided SVGs, 25 matching additions and one send-state alias. Use `scripts/sync-icon-library.py` to regenerate Android vectors and the searchable catalog. Retain recognizable source shapes and semantics; all action icons are optically calibrated to the usage-chart icon: 23dp display size, effective 1.5-unit rounded strokes, a 17.5-unit visible long edge and small optical overshoot for circles. The original filled outlines are redrawn as strokes; transforms compensate stroke width so scaling never changes line weight.  New task uses `new-task`; attachment expansion uses `plus`. Source, per-icon status and code references are recorded in the manifest. Brand artwork and server-provided avatars remain separate.

- `CatsCoWordmark`: shared tinted wordmark asset. Use the mark only when the full CatsCo brand is needed, not as a substitute for action icons.
- `CatsCoSidebarShortcut`: transparent full-width navigation action, 48dp minimum height and 23dp icon. History and date headings align to the visible left edge of its icon.
- `CatsCoHeaderAction`: 48dp touch target, 40dp decorated surface where applicable, 23dp icon.
- `CatsCoAccountFooter`: desktop-aligned avatar, account name and trailing settings action, enlarged for phone touch and type sizes without claiming an unsupported online state.
- `CatsCoComposerFrame`: outlined white composer shell with the established 28dp shape.
- `CatsCoConversationActionMenu`: 176dp menu, four equal 48dp action rows, 23dp icons and 17sp/24sp normal-weight labels. Operation menus share 12dp corners, 6dp insets, 0.5dp theme borders and a light 2dp shadow (zero in dark mode). Row feedback clips to 6dp corners. Destructive actions use the error color.
- `CatsCoMessageActionMenu`: the same row and type tokens, with three equal rows for quote, copy full response and regenerate. Application action menus share this rhythm at their existing 208dp width.
- `ModuleSection` and `SettingsGroup`: neutral themed groups with 24dp corners and a shared 16dp content inset. Editing fields use 12dp corners, body-sized text and theme borders.
- Settings use a centered 72dp circular account avatar above the name and profile action, placed directly on the page background. Group panels share 0.5dp borders. Account ID appears in profile details. Cloud hosting is available directly from settings. Brand artwork and the actual version appear in the footer.
- Module information rows align labels left and selectable values right, with wrapping for long addresses and enlarged text. `ModuleInfoRows` inserts separators only between populated values. Empty/error states use the same group surface with a centered icon, title, explanation and applicable action. Secondary page headers center their titles between equal navigation spaces.
- Contacts show account-scoped avatars with person/group fallbacks and a search field above the list. Search filters loaded names and usernames locally. Add-friend and create-group actions share the plus menu. Repeated friend labels and identical usernames are omitted.
- `CatsCoMorphingMenu`: 96×40dp closed visual capsule with a new-task action and a more action, two 23dp icons and a 48dp hit area. Its expanded actions share the 48dp rows, 17sp/24sp normal-weight labels and 23dp icons used by contextual menus. Expanded height is 204dp at default text scale, derived from four action rows and 6dp outer padding. The first row is My Computer; the expanded menu has no separate collapse header. Geometry shares one progress value, with overlapping content fades and a faster scrim dismissal.
- Expanded composer attachments follow the user's image reference: 30dp filled photo/camera/folder pictograms on 64dp themed surface tiles with 16dp corners, 16sp labels and at least 112dp option areas. Regular composer actions retain 23dp visuals and 48dp hit areas.
- `CatsCoMobileSpec`: shared geometry and typography values used by these components and their host rows.
- `CatsCoAlertDialog`: 20dp corners and the raised surface role for confirmation, editing, choice and file-detail dialogs. Bottom sheets share 28dp top corners. The employee picker uses the dialog shape and the common floating border/shadow rules.
- `CatsCoSidebarSearchField`: 24dp corners for sidebar, application and cloud-file search. Form fields and filled form buttons share 12dp corners; buttons have at least 48dp touch height. Authentication surfaces use the same background/field/border roles as the rest of the app.
- File cards use the content surface, 12dp corners and 0.5dp borders before and after sending. File titles use 15sp/20sp medium and descriptions 12sp/18sp. Message plan cards share the 12dp content-card shape and border; inset code blocks use 8dp corners.

## Interaction Rules

- Keep touch targets at least 48dp. Contextual action menus use 48dp rows with vertically centered 24sp text lines. At the default font scale, this leaves approximately 12dp above and below the text line. The common 6dp outer inset plus 14dp row padding align content at 20dp from the card edge, with a 12dp icon-to-label gap. Adjacent rows remain contiguous. Larger text expands each row to its scaled line height plus 24dp.
- Larger system text expands menu rows and available label width. Bound cards to the screen and allow their actions to scroll when height is limited. Never reduce the requested text scale to fit a fixed row.
- Expanded menu typography and spacing adapt the two reference screenshots supplied on 2026-11-17. These Android values are a proportional adaptation. Apply the shared menu tokens to history actions, message actions, application actions and the expanded top-right capsule. Message body typography follows its own tokens.
- Use native ripple, keyboard focus, system back and accessibility semantics. Long press opens contextual actions; menus dismiss before performing an action.
- Use stable row geometry when dates change to active indicators or selection checkboxes appear.
- Keep status color semantic and restrained. Use theme colors for text, borders and surfaces so light and dark appearances remain legible.
- Use short, non-bouncy motion for transient menus and feedback. Do not scale or shift surrounding layout.
- System back closes expanded composer attachments before navigating away. Message actions use the agreed left swipe. File names support long-press details.
- Right swipes on native reply text open the sidebar. Direction is resolved before message actions claim a drag. Tables keep horizontal scrolling, and native text selection retains its own gestures.
- Secondary and tertiary pages support interactive right-swipe back. The current page follows the finger, revealing the preceding page with a shared parallax, scrim and edge shadow. Release completes at 32% width or a sufficiently fast rightward flick, and a short or reversed drag returns to the current page. Android predictive back uses the same progress and cancellation behavior. Preview images stay in account-scoped memory at native resolution, limited to 32MiB and eight entries (retaining at least one), with no duplicate page requests. Composer expansion is saved per page so returning from settings preserves its height and the home content position.
- The 60dp task header centers its title and left action with the closed capsule's 48dp hit area. Derive the capsule's top inset from those heights, including the status-bar inset.
- The collapsed drawer uses three horizontal lines; the open drawer uses three dots with three lines. The composer has one primary action: voice for an empty/whitespace draft, the original upward arrow for text or attachments, and stop while a task is running. Voice recognition writes into the draft for user review; it never submits automatically. Sending and stopping states remain disabled while pending.
- Sending from the composer or its keyboard action hides the keyboard and clears focus. Draft edits remain available in memory while encrypted persistence runs on the serialized background queue. A delivery waits for its retry ID and task-creation marker to persist before requesting the server. The stop action retains its separate behavior.
- A nonblank composer draft uses the same calibrated 1.5-unit send-arrow stroke and 23dp icon as the empty state. The latest unified-weight request supersedes the earlier 2.1-unit active stroke. Button geometry and enabled/disabled colors still communicate state.
- The home Agent card opens the selected employee's settings on tap and the employee picker on long press. Picker rows show selection and availability, support scrolling and preserve the selected employee when dismissed. Long personalized greetings wrap before the user name when the full line exceeds the available width.
- Required and malformed fields explain validation after focus leaves the field. Existing server-side errors remain authoritative. Verification-code cooldown begins after the server accepts the request.
- Cloud files open at the current conversation's Agent or selected task Agent. Switching the file scope replaces that scope without adding navigation history.
- SkillHub uses server-provided purposes and names. Configuration state is scoped to the chosen managed Agent and distinguishes reading, unavailable, unconfigured and configured versions.
- Search results preserve query/list position, load the matched message's bounded context and provide an explicit return to latest messages.
- A newly created task opens its conversation after message acknowledgement, with the acknowledged message present in the same UI update. Conversation refresh preserves visible replies and uses the existing older-history action for pagination feedback.
- Module refresh retains loaded content. First load, confirmed empty results, permission denial and transient failure have distinct presentations. Permission-denied surfaces offer navigation out.
- Editing drafts use the existing account/server-scoped encrypted store. Refresh reconciles untouched fields while preserving edits. Saving clears only the submitted values, and clearing local data is serialized after queued draft writes.
- Stop feedback distinguishes sending the request from completion. The HTTP fallback sends the stop message and waits for synchronized task state.
- Native dialogs and hosted knowledge content use the same neutral light/dark palette. Hosted knowledge adaptation is scoped to its own selectors.
- Main-surface blur animation state is read in the graphics layer so each animation frame does not recompose the message timeline.
- Drawer blur follows its measured opening fraction up to 5dp, including partial drags and cancellation. The drawer scrim uses 16% black in light mode and 12% in dark mode. Transient drawer frames are excluded from swipe-back previews.
- When a short drawer window cannot fit its navigation header and three history rows, search and shortcuts scroll with history. Taller windows keep the established fixed navigation area. The wordmark, close action and account footer remain reachable.

## Desktop Mapping

Desktop references include `catsco-plans-pr-20261027/webapp/src/widgets/relay-access-modal.jsx` for account hierarchy and localized order status, and `bot-model-selector.jsx` for desired/applied model state and supported reasoning options. Android adapts these through the current native tokens and interaction sizes.

消息解析与缓存序列化、加密写入在后台执行。账号缓存与模块草稿按同一队列顺序写入，清理和退出登录排在已提交写入之后。

## 内容优化交互规范

以下规则由交互预览确认后接入原生共享组件。

| 样式角色 | 浅色 | 深色 |
| --- | --- | --- |
| 次级按钮底色 | `#EDEDED` | `#414141` |
| 输入框焦点边框 | `#8C8C96` | `#6C6C76` |

- 次级按钮统一使用中性灰填充、12dp 圆角、48dp 最小触控高度和 15sp 中等字重。取消、关闭、重新读取、移除等同类操作共用无描边样式，覆盖选择模型、选择员工、编辑资料和确认弹窗。
- 原生按钮沿用 Material 的按下波纹反馈，选项切换使用共享的滑动底块。
- 输入框聚焦时只改变原有 1dp 边框颜色。搜索框的焦点由外层边框表达，文字输入区域采用单层边界。键盘操作按钮使用 1dp 焦点线。
- 外观入口直接展开跟随系统、浅色、深色三个选项，选择后立即应用并关闭菜单。菜单采用 12dp 圆角、6dp 内边距和 48dp 选项高度。外观图标左半透明、右半使用当前前景色填充。
- 通知开关采用原生 Switch 的白色圆形滑块和 48dp 最小触控区域。关闭时滑块在左侧，开启时在右侧。原生实现以系统通知授权状态回显，关闭时进入系统通知设置。
- 分段按钮使用连续移动的选中底块。应用、SkillHub 与同类分段控件统一使用 200ms、`cubic-bezier(.2,.7,.2,1)` 的水平滑动。快速反向操作时从当前位置转向，减少动态效果模式下直接更新选中位置。通知开关使用原生滑动反馈。
- 应用和 SkillHub 共用员工选择胶囊与列表文字组件，按员工选择、搜索、分段筛选、平铺列表的顺序排列。横向边距为 16dp，列表行至少 76dp，主文字 16sp/23sp，辅助文字 13sp/20sp。技能保留版本和所选员工的配置状态，技能列表使用页面底色与细分隔线。
- 设置顶部采用头像在上、姓名与个人资料入口在下的居中纵向布局。头像为圆形，个人资料区域直接置于页面背景上。
- 缓存页用一句短文案说明加密状态，清除入口采用中性整行操作，删除范围放在确认提示中。
