package cc.catsco.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.catsco.android.BuildConfig
import cc.catsco.android.R
import cc.catsco.android.WorkspaceState
import coil.ImageLoader

internal data class SettingsEntry(val label: String, val icon: Int? = null, val value: String? = null,
    val action: String? = label, val options: List<Pair<String, String>> = emptyList())

internal const val HiddenTasksSettingsLabel = "本机隐藏的任务"

@Composable
internal fun NativeSettings(
    state: WorkspaceState,
    imageLoader: ImageLoader,
    hasHiddenTasks: Boolean = false,
    open: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "account") {
            Column(Modifier.fillMaxWidth().catsCoActionClickable { open("个人资料") }.padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CatsCoAccountAvatar(state.session, imageLoader, Modifier.size(72.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(state.session?.name.orEmpty(), fontSize = 19.sp, lineHeight = 27.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("个人资料", fontSize = 14.sp, lineHeight = 22.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
            }
        }

        item(key = "account-services") {
            SettingsGroup("账号与服务", listOf(
                SettingsEntry("套餐与权益", R.drawable.e_sparkle),
                SettingsEntry("模型用量", R.drawable.e_chart),
                SettingsEntry("模型访问凭证", R.drawable.e_key),
            ), open)
        }
        item(key = "preferences") {
            SettingsGroup("应用偏好", buildList {
                add(SettingsEntry(PersonalizationTitle, R.drawable.e_contrast, action = "appearance"))
                add(SettingsEntry("消息通知", R.drawable.e_notifications))
                add(SettingsEntry("桌面组件", R.drawable.e_widgets, action = "实时活动与组件"))
                add(SettingsEntry("缓存与草稿", R.drawable.e_download, action = "离线与分享草稿"))
                if (hasHiddenTasks) add(SettingsEntry(HiddenTasksSettingsLabel, R.drawable.message_eye_off))
            }, open)
        }
        item(key = "agents-and-devices") {
            SettingsGroup("工作空间", listOf(
                SettingsEntry("员工管理", R.drawable.e_agent),
                SettingsEntry("定制 Agent", R.drawable.e_sparkle),
                SettingsEntry("联系人与群组", R.drawable.e_people),
                SettingsEntry("我的电脑", R.drawable.e_computer),
                SettingsEntry("云托管", R.drawable.e_cloud),
            ), open)
        }
        item(key = "help-and-connection") {
            SettingsGroup("帮助与连接", listOf(
                SettingsEntry("反馈", R.drawable.e_chat),
                SettingsEntry("打开网页版", R.drawable.e_external, action = "打开 WebApp"),
                SettingsEntry("服务器", R.drawable.e_language),
            ) + listOfNotNull(if (BuildConfig.DEBUG) SettingsEntry("检查更新", R.drawable.e_refresh) else null), open)
        }
        item(key = "logout") {
            Surface(
                shape = CatsCoMobileSpec.settingsGroupShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(Modifier.fillMaxWidth().heightIn(min = CatsCoMobileSpec.settingsRowHeight)
                    .catsCoActionClickable { open("logout") }.padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("退出登录", fontSize = CatsCoMobileSpec.settingsText, fontWeight = FontWeight.Medium)
                }
            }
        }
        item(key = "version") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CatsCoWordmark(Modifier.width(100.dp).height(24.dp))
                Spacer(Modifier.weight(1f))
                Text(BuildConfig.VERSION_NAME, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun SettingsGroup(title: String, entries: List<SettingsEntry>, open: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            Modifier.padding(start = 16.dp).semantics { heading() },
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = CatsCoMobileSpec.settingsSectionText,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Medium,
        )
        Surface(shape = CatsCoMobileSpec.settingsGroupShape, color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column {
                entries.forEachIndexed { index, entry ->
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = CatsCoMobileSpec.settingsRowHeight)
                            .then(when {
                                entry.options.isNotEmpty() -> Modifier.catsCoDisclosureClickable(expanded) { expanded = true }
                                entry.action != null -> Modifier.catsCoActionClickable { open(entry.action) }
                                else -> Modifier
                            })
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        entry.icon?.let { icon ->
                            Icon(painterResource(icon), null, Modifier.size(CatsCoMobileSpec.settingsIconSize), tint = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.width(CatsCoMobileSpec.settingsLabelGap))
                        }
                        Text(entry.label, Modifier.weight(1f), fontSize = CatsCoMobileSpec.settingsText, lineHeight = 24.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        entry.value?.let { value ->
                            Spacer(Modifier.width(8.dp))
                            val valueText: @Composable () -> Unit = {
                                Text(value, Modifier.widthIn(max = 160.dp), fontSize = 14.sp,
                                    lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.End)
                            }
                            if (entry.action == null) SelectionContainer { valueText() } else valueText()
                        }
                        if (entry.action != null) {
                            Spacer(Modifier.width(10.dp))
                            if (entry.options.isNotEmpty()) {
                                CatsCoDisclosureChevron(expanded, tint = MaterialTheme.colorScheme.onSurface)
                            } else {
                                Icon(painterResource(R.drawable.e_chevron_right), null,
                                    Modifier.size(CatsCoMobileSpec.actionIconSize), tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                    CatsCoMenuPopup(expanded, { expanded = false }, width = 208.dp) {
                        entry.options.forEach { (id, label) ->
                            DropdownMenuItem(text = { Text(label, fontSize = 16.sp) },
                                trailingIcon = { if (entry.value == label) Icon(painterResource(R.drawable.e_select), null, Modifier.size(23.dp)) },
                                onClick = { expanded = false; open(id) })
                        }
                    }
                    }
                    if (index < entries.lastIndex) HorizontalDivider(
                        Modifier.padding(start = if (entry.icon == null) 16.dp else 16.dp + CatsCoMobileSpec.settingsIconSize + CatsCoMobileSpec.settingsLabelGap, end = 16.dp),
                        thickness = .5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f),
                    )
                }
            }
        }
    }
}
