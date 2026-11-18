package cc.catsco.android

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cc.catsco.android.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/** Real device rendering, isolated from the user's account. */
class IconFontReviewTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: MessageReviewActivity
    private fun show(content: @Composable () -> Unit) {
        val i = InstrumentationRegistry.getInstrumentation()
        i.uiAutomation.executeShellCommand("am start -W -n cc.catsco.android.debug/cc.catsco.android.MessageReviewActivity")
            .use { d -> FileInputStream(d.fileDescriptor).use { it.readBytes() } }
        i.waitForIdleSync()
        i.runOnMainSync {
            host = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MessageReviewActivity>().single()
            host.setContent(content = content)
        }
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        val i = InstrumentationRegistry.getInstrumentation()
        val bitmap = i.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val file = File(i.targetContext.getExternalFilesDir(null), "icon-font-review/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private val icons = listOf(
        R.drawable.e_add to "plus",
        R.drawable.e_edit to "edit",
        R.drawable.e_more to "more",
        R.drawable.e_computer to "monitor",
        R.drawable.message_terminal to "terminal",
        R.drawable.e_select to "check",
        R.drawable.e_apps to "grid",
        R.drawable.e_send to "send",
        R.drawable.e_language to "globe",
        R.drawable.message_copy to "copy",
        R.drawable.e_search to "search",
        R.drawable.e_sidebar to "sidebar",
        R.drawable.e_sidebar_active to "sidebar-active",
        R.drawable.e_settings to "settings",
        R.drawable.e_menu to "list",
        R.drawable.e_expand to "chevron-down",
        R.drawable.e_notifications to "bell",
        R.drawable.e_download to "download",
        R.drawable.e_key to "key",
        R.drawable.e_close to "close",
        R.drawable.e_new_task to "new-task",
        R.drawable.e_external to "arrow-up-right",
        R.drawable.e_knowledge to "knowledge",
        R.drawable.e_chat to "message",
        R.drawable.e_account to "user",
        R.drawable.message_eye to "eye",
        R.drawable.e_people to "team",
        R.drawable.e_chevron_right to "chevron-right",
        R.drawable.e_agent to "agent",
        R.drawable.e_attach to "attachment",
        R.drawable.e_camera to "camera",
        R.drawable.e_chart to "chart",
        R.drawable.e_cloud to "cloud",
        R.drawable.e_contrast to "contrast",
        R.drawable.e_delete to "delete",
        R.drawable.e_file to "document",
        R.drawable.message_document to "document",
        R.drawable.message_eye_off to "eye-off",
        R.drawable.e_image to "image",
        R.drawable.e_mic to "microphone",
        R.drawable.e_pin to "pin",
        R.drawable.e_refresh to "refresh",
        R.drawable.message_quote to "quote",
        R.drawable.e_sparkle to "sparkle",
        R.drawable.e_star to "star",
        R.drawable.e_widgets to "widgets",
        R.drawable.e_next to "arrow-right",
        R.drawable.e_stop to "stop",
        R.drawable.e_circle to "circle",
        R.drawable.e_check_circle to "check-circle",
        R.drawable.e_progress_circle to "progress-circle",
        R.drawable.e_send_active to "send-active"
    )
    @Test fun allAndroidVectorsRenderInBothThemes() {
        listOf(false, true).forEach { dark ->
            show {
                CatsCoTheme(dark) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.statusBarsPadding().padding(12.dp)) {
                            Text("CatsCo · 图标 / 普惠体 2.0", fontSize = 18.sp)
                            Spacer(Modifier.height(12.dp))
                            icons.chunked(5).forEach { row ->
                                Row(Modifier.fillMaxWidth()) {
                                    row.forEach { (resource, name) ->
                                        Column(Modifier.weight(1f).height(67.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(painterResource(resource), null, Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Icon(painterResource(resource), name, Modifier.size(CatsCoMobileSpec.actionIconSize))
                                            }
                                            Text(name, fontSize = 9.sp, lineHeight = 12.sp, maxLines = 2)
                                        }
                                    }
                                    repeat(5-row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }
            capture(if (dark) "icons-dark" else "icons-light")
        }
    }
    @Test fun nativeMarkdownUsesPackagedFacesAndPreservesSelection() {
        show {
            CatsCoTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.statusBarsPadding().padding(20.dp)) {
                        MarkdownContent("# 普惠体标题 Alibaba 2.0\n\n正文与 **短重点**，`inline code`。\n\n| 表头 | 内容 |\n| --- | --- |\n| 日期 | 2026-11-17 |\n\n```kotlin\nval message = \"你好 CatsCo\"\n```")
                    }
                }
            }
        }
        compose.runOnIdle {
            fun views(view: View): List<TextView> = (if (view is TextView) listOf(view) else emptyList()) +
                (if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList())
            val texts = views(host.window.decorView).filter { it.text.isNotBlank() }
            assertTrue("Native Markdown was rendered", texts.size >= 4)
            texts.forEach { view ->
                assertTrue("Native text uses a bundled face: ${view.text}", view.typeface == CatsCoFonts.regular(host) || view.typeface == CatsCoFonts.bold(host))
                assertTrue(view.isTextSelectable)
            }
            val paint = android.text.TextPaint()
            val rich = readableMessageSpans(android.text.SpannableStringBuilder("重点").apply {
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, 2, 33)
            }, host) as android.text.Spanned
            rich.getSpans(0,2,CatsCoTypefaceSpan::class.java).single().updateMeasureState(paint)
            assertEquals(CatsCoFonts.bold(host), paint.typeface)
            assertFalse(paint.isFakeBoldText)
        }
        capture("native-markdown")
    }
}
