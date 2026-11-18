package cc.catsco.android

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cc.catsco.android.ui.CatsCoAuthScreen
import cc.catsco.android.ui.CatsCoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class AuthScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: MessageReviewActivity
    private var dark by mutableStateOf(false)
    private var state by mutableStateOf(WorkspaceState())
    private var logins = 0
    private var resets = 0
    private var codeRequests = 0
    private var codeSent: (() -> Unit)? = null

    private fun show() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n cc.catsco.android.debug/cc.catsco.android.MessageReviewActivity",
        ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            host = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MessageReviewActivity>().single()
            host.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            host.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            host.setContent {
                SideEffect { WindowCompat.getInsetsController(host.window, host.window.decorView).isAppearanceLightStatusBars = !dark }
                CatsCoTheme(dark) {
                    CatsCoAuthScreen(state, "https://app.catsco.cc", {},
                        onLogin = { _, account, password ->
                            assertEquals("preview@example.test", account)
                            assertEquals("Preview123", password)
                            logins++
                        },
                        onRegister = { _, _, _, _, _, _ -> fail("Unexpected registration") },
                        onResetPassword = { _, _, _, _, done -> resets++; state = state.copy(notice = "密码已重置，可以使用新密码登录。"); done() },
                        onSendCode = { _, _, _, done -> codeRequests++; codeSent = done },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun snapshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(250)
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "auth-review").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { stream ->
            // Xiaomi's secure keyboard blocks full-screen capture. Keep an app-only layout
            // capture in that case; IME visibility is verified separately below.
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                ?: compose.onRoot().captureToImage().asAndroidBitmap()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            bitmap.recycle()
        }
    }

    private fun imeVisible(): Boolean = ViewCompat.getRootWindowInsets(host.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    @Test fun loginUsesTheImeActionAndClosesTheKeyboard() {
        show()
        val cardTop = compose.onNodeWithTag("auth-card").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("auth-account").performClick().performTextInput("preview@example.test")
        compose.onNodeWithTag("auth-password").performClick().performTextInput("Preview123")
        compose.onNodeWithContentDescription("显示密码").performClick()
        compose.onNodeWithContentDescription("隐藏密码").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { imeVisible() }
        val keyboardCardTop = compose.onNodeWithTag("auth-card").fetchSemanticsNode().boundsInRoot.top
        assertEquals("Opening the keyboard must preserve the login card position", cardTop, keyboardCardTop, 2f)
        snapshot("login-keyboard-layout")
        compose.onNodeWithTag("auth-password").performImeAction()
        compose.waitUntil(5000) { !imeVisible() }
        compose.runOnIdle { assertEquals(1, logins) }
    }

    @Test fun resetRequiresMatchingPasswordsAndReturnsToLoginAfterSuccess() {
        show()
        compose.onNodeWithText("忘记密码？").performClick()
        compose.onNodeWithTag("auth-account").performTextInput("preview@example.test")
        compose.onNodeWithTag("auth-code").performTextInput("000000")
        compose.onNodeWithTag("auth-password").performTextInput("Preview123")
        compose.onNodeWithTag("auth-confirmation").performClick().performTextInput("Other123")
        compose.onNodeWithTag("auth-confirmation").performImeAction()
        compose.onNodeWithText("两次输入的密码不一致").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, resets) }
        snapshot("reset-validation-keyboard-layout")
        compose.onNodeWithTag("auth-confirmation").performTextReplacement("Preview123")
        compose.onNodeWithTag("auth-confirmation").performImeAction()
        compose.onNodeWithTag("auth-confirmation").assertDoesNotExist()
        compose.onNodeWithText("密码已重置，可以使用新密码登录。").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, resets) }
    }

    @Test fun verificationCountdownStartsOnSuccessAndSurvivesModeChanges() {
        show()
        compose.onNodeWithText("立即注册").performClick()
        compose.onNodeWithTag("auth-account").performTextInput("invalid")
        compose.onNodeWithTag("auth-send-code").performClick()
        compose.onNodeWithText("请输入有效的邮箱地址").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, codeRequests) }
        compose.onNodeWithTag("auth-account").performTextReplacement("preview@example.test")
        compose.onNodeWithTag("auth-send-code").performClick().assertIsEnabled()
        compose.runOnIdle { assertEquals(1, codeRequests); codeSent!!.invoke() }
        compose.onNodeWithTag("auth-send-code").assertIsNotEnabled()
        compose.onNodeWithText("立即登录").performScrollTo().performClick()
        compose.onNodeWithText("立即注册").performClick()
        compose.onNodeWithTag("auth-send-code").assertIsNotEnabled()
    }

    @Test fun capturesTheNativeLoginRegisterAndResetLayouts() {
        show()
        snapshot("login-light")
        compose.runOnIdle { dark = true }
        snapshot("login-dark")
        compose.runOnIdle { dark = false }
        compose.onNodeWithText("立即注册").performClick()
        snapshot("register-light")
        compose.onNodeWithText("立即登录").performScrollTo().performClick()
        compose.onNodeWithText("忘记密码？").performClick()
        snapshot("reset-light")
    }
}
