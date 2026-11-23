package cc.catsco.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.catsco.android.R

internal enum class BubbleTone(val id: String, val label: String, val light: Color, val dark: Color) {
    Default("default", "默认", Color.Unspecified, Color.Unspecified),
    Mint("mint", "薄荷绿", Color(0xFFDDF1E9), Color(0xFF203B35)),
    Blue("blue", "雾蓝", Color(0xFFE1EBF8), Color(0xFF28384A)),
    Lilac("lilac", "淡紫", Color(0xFFEEE6F7), Color(0xFF3B3049)),
    Sand("sand", "暖砂", Color(0xFFF3E9D9), Color(0xFF40362A)),
    Rose("rose", "浅玫瑰", Color(0xFFF6E5E8), Color(0xFF453137));

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: Default
    }
}

internal data class ConversationBubbleTones(
    val user: BubbleTone = BubbleTone.Default,
    val agent: BubbleTone = BubbleTone.Default,
)

internal val LocalConversationBubbleTones = staticCompositionLocalOf { ConversationBubbleTones() }

internal val UserBubbleShape = RoundedCornerShape(16.dp)
internal val AgentBubbleShape = RoundedCornerShape(15.dp)

private fun defaultUserBubbleColor(dark: Boolean) = if (dark) Color(0xFF2C2C2E) else Color(0xFFE6E6E9)

@Composable
internal fun conversationBubbleColor(mine: Boolean): Color {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < .5f
    val tones = LocalConversationBubbleTones.current
    val tone = if (mine) tones.user else tones.agent
    return when {
        tone != BubbleTone.Default -> if (dark) tone.dark else tone.light
        mine -> defaultUserBubbleColor(dark)
        else -> colors.surface
    }
}

@Composable
internal fun conversationBubbleTextColor(mine: Boolean): Color {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < .5f
    // Resolve the previous translucent text against its default bubble once so that
    // every selected tint displays the same opaque text within the current theme.
    return when {
        mine -> colors.onSurface.copy(alpha = .86f).compositeOver(defaultUserBubbleColor(dark))
        dark -> colors.onSurface.copy(alpha = .86f).compositeOver(colors.surface)
        else -> colors.onSurface
    }
}

internal const val PersonalizationTitle = "个性化"

private val themeOptions = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")

@Composable
internal fun PersonalizationSettings(
    mode: String,
    wallpaper: ConversationWallpaperStore,
    onThemeSelected: (String) -> Unit,
    onWallpaper: () -> Unit,
    onBubbleSelected: (Boolean, BubbleTone) -> Unit,
) {
    val tones = LocalConversationBubbleTones.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = CatsCoMobileSpec.settingsGroupShape,
            color = MaterialTheme.colorScheme.background,
            border = androidx.compose.foundation.BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box {
                if (wallpaper.revision != null) StoredWallpaper(wallpaper)
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Surface(
                        modifier = Modifier.align(Alignment.End).widthIn(max = 280.dp),
                        shape = UserBubbleShape, color = conversationBubbleColor(mine = true),
                    ) {
                        Text("我的气泡预览", Modifier.padding(horizontal = 14.dp, vertical = 10.dp), fontSize = 15.sp,
                            color = conversationBubbleTextColor(mine = true))
                    }
                    Surface(
                        modifier = Modifier.align(Alignment.Start).widthIn(max = 280.dp),
                        shape = AgentBubbleShape, color = conversationBubbleColor(mine = false),
                    ) {
                        Text("Agent 气泡预览", Modifier.padding(horizontal = 14.dp, vertical = 10.dp), fontSize = 15.sp,
                            color = conversationBubbleTextColor(mine = false))
                    }
                }
            }
        }
        SettingsGroup("界面", listOf(
            SettingsEntry("主题", R.drawable.e_contrast,
                value = themeOptions.firstOrNull { it.first == mode }?.second ?: themeOptions.first().second,
                options = themeOptions),
            SettingsEntry("会话壁纸", R.drawable.e_image,
                value = if (wallpaper.revision == null) "默认" else "自定义", action = "wallpaper"),
        )) { choice ->
            if (choice == "wallpaper") onWallpaper() else onThemeSelected(choice)
        }
        val choices = BubbleTone.entries
        SettingsGroup("聊天气泡", listOf(
            SettingsEntry("我的气泡", value = tones.user.label, options = choices.map { "user/${it.id}" to it.label }),
            SettingsEntry("Agent 气泡", value = tones.agent.label, options = choices.map { "agent/${it.id}" to it.label }),
        )) { choice ->
            onBubbleSelected(choice.startsWith("user/"), BubbleTone.fromId(choice.substringAfter('/')))
        }
    }
}
