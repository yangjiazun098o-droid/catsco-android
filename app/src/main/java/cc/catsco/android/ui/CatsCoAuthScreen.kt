package cc.catsco.android.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.catsco.android.R
import cc.catsco.android.WorkspaceState
import kotlinx.coroutines.delay
import java.util.Locale

private enum class AuthMode(val label: String) { Login("登录"), Register("注册"), Reset("重置密码") }
private val AuthGreen = Color(0xFF20B997)
private val AuthShape = CatsCoMobileSpec.fieldShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatsCoAuthScreen(
    state: WorkspaceState,
    initialServer: String,
    onDismissFeedback: () -> Unit,
    onLogin: (String, String, String) -> Unit,
    onRegister: (String, String, String, String, String, () -> Unit) -> Unit,
    onResetPassword: (String, String, String, String, () -> Unit) -> Unit,
    onSendCode: (String, String, Boolean, () -> Unit) -> Unit,
    onPreview: (() -> Unit)? = null,
) {
    var mode by rememberSaveable { mutableStateOf(AuthMode.Login) }
    var account by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var invite by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf(initialServer) }
    var serverOpen by rememberSaveable { mutableStateOf(false) }
    var touched by remember { mutableStateOf(emptySet<String>()) }
    var focused by remember { mutableStateOf<String?>(null) }
    var requestKind by remember { mutableStateOf<String?>(null) }
    var deadlines by rememberSaveable { mutableStateOf(mapOf<String, Long>()) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val cooldownKey = "$server|$mode|${account.trim().lowercase(Locale.ROOT)}"
    val deadline = deadlines[cooldownKey] ?: 0L
    LaunchedEffect(deadline, cooldownKey) {
        now = SystemClock.elapsedRealtime()
        while (now < deadline) { delay(1000); now = SystemClock.elapsedRealtime() }
    }
    LaunchedEffect(state.busy) { if (!state.busy) requestKind = null }
    val remaining = ((deadline - now).coerceAtLeast(0) + 999) / 1000
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val background = MaterialTheme.colorScheme.background
    val panel = MaterialTheme.colorScheme.surfaceContainerHigh
    val field = MaterialTheme.colorScheme.surface
    val border = MaterialTheme.colorScheme.outlineVariant
    val secondary = if (dark) Color(0xFFC5C5CA) else Color(0xFF57575E)
    val muted = if (dark) Color(0xFFA5A5AD) else Color(0xFF727279)
    val scroll = rememberScrollState()

    fun changeMode(next: AuthMode, clearFeedback: Boolean = true) {
        focus.clearFocus()
        keyboard?.hide()
        mode = next
        password = ""
        confirmation = ""
        code = ""
        touched = emptySet()
        if (clearFeedback) onDismissFeedback()
    }
    fun validation(name: String): String? = when (name) {
        "account" -> when {
            account.isBlank() -> if (mode == AuthMode.Login) "请输入用户名或邮箱" else "请输入邮箱地址"
            mode != AuthMode.Login && !android.util.Patterns.EMAIL_ADDRESS.matcher(account.trim()).matches() -> "请输入有效的邮箱地址"
            else -> null
        }
        "password" -> when {
            password.isEmpty() -> "请输入密码"
            mode != AuthMode.Login && password.length < 6 -> "密码至少需要6位"
            else -> null
        }
        "code" -> if (code.isBlank()) "请输入邮箱验证码" else null
        "confirmation" -> when {
            confirmation.isEmpty() -> "请再次输入新密码"
            password != confirmation -> "两次输入的密码不一致"
            else -> null
        }
        else -> null
    }
    fun submit() {
        if (state.busy) return
        val fields = buildSet {
            add("account"); add("password")
            if (mode != AuthMode.Login) add("code")
            if (mode == AuthMode.Reset) add("confirmation")
        }
        touched = fields
        if (fields.any { validation(it) != null } || serverAddressError(server) != null) return
        keyboard?.hide()
        focus.clearFocus()
        requestKind = "submit"
        when (mode) {
            AuthMode.Login -> onLogin(server, account.trim(), password)
            AuthMode.Register -> onRegister(server, account.trim(), password, code.trim(), invite.trim()) { changeMode(AuthMode.Login, clearFeedback = false) }
            AuthMode.Reset -> onResetPassword(server, account.trim(), password, code.trim()) { changeMode(AuthMode.Login, clearFeedback = false) }
        }
    }
    BackHandler(enabled = mode != AuthMode.Login && !serverOpen && !state.busy) { changeMode(AuthMode.Login) }
    Box(Modifier.fillMaxSize().background(background).testTag("auth-screen")) {
        AuthFlowBackground(!imeVisible && focused == null && !serverOpen && !state.busy, dark, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().systemBarsPadding().displayCutoutPadding()) {
            Column(
                Modifier.fillMaxSize().imePadding().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, top = 116.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
                        .shadow(12.dp, RoundedCornerShape(12.dp), ambientColor = Color.Black.copy(alpha = .12f), spotColor = Color.Black.copy(alpha = .16f))
                        .testTag("auth-card"),
                    shape = RoundedCornerShape(12.dp), color = panel,
                    border = BorderStroke(.5.dp, border), tonalElevation = 0.dp,
                ) {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CatsCoWordmark(Modifier.width(112.dp).height(23.dp))
                        Spacer(Modifier.height(30.dp))
                        if (mode == AuthMode.Reset) {
                            Text("验证注册邮箱，设置新密码。", Modifier.fillMaxWidth().padding(bottom = 16.dp), fontSize = 13.sp, lineHeight = 20.sp, color = secondary)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val accountLabel = if (mode == AuthMode.Login) "用户名或邮箱" else "邮箱地址"
                            AuthField(account, { account = it }, accountLabel, "auth-account", field, border,
                                enabled = !state.busy, errorMessage = if ("account" in touched) validation("account") else null,
                                keyboardType = if (mode == AuthMode.Login) KeyboardType.Text else KeyboardType.Email,
                                onFocus = { focused = if (it) "account" else focused.takeUnless { it == "account" } },
                                onIme = { focus.moveFocus(FocusDirection.Down) })
                            if (mode != AuthMode.Login) {
                                Column {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                                        AuthField(code, { code = it }, "邮箱验证码", "auth-code", field, border,
                                            modifier = Modifier.weight(1f), enabled = !state.busy,
                                            errorMessage = if ("code" in touched) validation("code") else null,
                                            keyboardType = KeyboardType.Number,
                                            onFocus = { focused = if (it) "code" else focused.takeUnless { it == "code" } },
                                            onIme = { focus.moveFocus(FocusDirection.Down) })
                                        OutlinedButton(
                                            onClick = {
                                                touched = touched + "account"
                                                if (validation("account") == null && serverAddressError(server) == null) {
                                                    requestKind = "code"
                                                    val key = cooldownKey
                                                    onSendCode(server, account.trim(), mode == AuthMode.Reset) {
                                                        val sent = SystemClock.elapsedRealtime()
                                                        deadlines = deadlines + (key to sent + 60_000)
                                                        now = sent
                                                    }
                                                }
                                            },
                                            enabled = !state.busy && remaining == 0L,
                                            modifier = Modifier.width(110.dp).heightIn(min = 48.dp).testTag("auth-send-code"),
                                            shape = AuthShape, border = BorderStroke(1.dp, border),
                                            colors = ButtonDefaults.outlinedButtonColors(containerColor = field, contentColor = secondary),
                                            contentPadding = PaddingValues(horizontal = 6.dp),
                                        ) { Text(if (state.busy && requestKind == "code") "发送中…" else if (remaining > 0) "${remaining}秒后重发" else "发送验证码", fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                                    }
                                }
                            }
                            val passwordLabel = when (mode) { AuthMode.Login -> "密码"; AuthMode.Register -> "设置密码（至少6位）"; AuthMode.Reset -> "新密码（至少6位）" }
                            AuthField(password, { password = it }, passwordLabel, "auth-password", field, border,
                                enabled = !state.busy, password = true,
                                errorMessage = if ("password" in touched) validation("password") else null,
                                imeAction = if (mode == AuthMode.Reset) ImeAction.Next else ImeAction.Done,
                                onFocus = { focused = if (it) "password" else focused.takeUnless { it == "password" } },
                                onIme = { if (mode == AuthMode.Reset) focus.moveFocus(FocusDirection.Down) else submit() })
                            if (mode == AuthMode.Reset) AuthField(confirmation, { confirmation = it }, "确认新密码", "auth-confirmation", field, border,
                                enabled = !state.busy, password = true,
                                errorMessage = if ("confirmation" in touched) validation("confirmation") else null,
                                imeAction = ImeAction.Done,
                                onFocus = { focused = if (it) "confirmation" else focused.takeUnless { it == "confirmation" } }, onIme = ::submit)
                            if (mode == AuthMode.Register) AuthField(invite, { invite = it.uppercase(Locale.ROOT) }, "邀请码", "auth-invite", field, border,
                                enabled = !state.busy, imeAction = ImeAction.Done,
                                onFocus = { focused = if (it) "invite" else focused.takeUnless { it == "invite" } }, onIme = ::submit)
                            state.error?.let { Text(authFeedback(it), Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 19.sp) }
                            state.notice?.let { Text(it, Modifier.fillMaxWidth(), color = secondary, fontSize = 13.sp, lineHeight = 19.sp) }
                            serverAddressError(server)?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                            Box(Modifier.padding(top = 8.dp)) {
                                CatsCoButton(onClick = ::submit, enabled = !state.busy,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("auth-submit"), shape = AuthShape,
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AuthGreen, contentColor = Color.White,
                                        disabledContainerColor = AuthGreen.copy(alpha = .65f), disabledContentColor = Color.White),
                                ) {
                                    Text(if (state.busy && requestKind != "code") "${mode.label}中…" else mode.label,
                                        Modifier.fillMaxWidth(), color = Color.White, fontSize = 15.sp, lineHeight = 20.sp,
                                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                                }
                            }
                        }
                        FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center, verticalArrangement = Arrangement.Center) {
                            Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                                Text(when (mode) { AuthMode.Login -> "还没有账号？"; AuthMode.Register -> "已有账号？"; AuthMode.Reset -> "想起密码了？" }, color = muted, fontSize = 13.sp)
                            }
                            AuthLink(when (mode) { AuthMode.Login -> "立即注册"; AuthMode.Register -> "立即登录"; AuthMode.Reset -> "返回登录" }, secondary, !state.busy) {
                                changeMode(if (mode == AuthMode.Login) AuthMode.Register else AuthMode.Login)
                            }
                            if (mode == AuthMode.Login) AuthLink("忘记密码？", secondary, !state.busy) { changeMode(AuthMode.Reset) }
                        }
                    }
                }
                TextButton(onClick = { serverOpen = true }, enabled = !state.busy, modifier = Modifier.padding(top = 7.dp)) {
                    Text("服务器设置", fontSize = 12.sp, color = muted)
                }
            }
        }
    }
    if (serverOpen) {
        var serverDraft by remember { mutableStateOf(server) }
        var showServerError by remember { mutableStateOf(false) }
        ModalBottomSheet(onDismissRequest = { serverOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), shape = CatsCoMobileSpec.sheetShape, containerColor = panel) {
            Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("服务器设置", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("HTTPS 服务器", fontSize = 13.sp, color = muted)
                AuthField(serverDraft, { serverDraft = it }, "HTTPS 服务器", "auth-server", field, border,
                    keyboardType = KeyboardType.Uri, errorMessage = if (showServerError) serverAddressError(serverDraft) else null,
                    imeAction = ImeAction.Done, onFocus = {}, onIme = { keyboard?.hide(); focus.clearFocus() })
                CatsCoButton(onClick = {
                    showServerError = true
                    if (serverAddressError(serverDraft) == null) { server = serverDraft.trim(); serverOpen = false; keyboard?.hide(); focus.clearFocus() }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = AuthShape,
                    colors = ButtonDefaults.buttonColors(containerColor = AuthGreen, contentColor = Color.White)) { Text("完成", color = Color.White) }
                onPreview?.let { openPreview ->
                    TextButton(onClick = { serverOpen = false; openPreview() }, enabled = !state.busy) { Text("进入调试预览", fontSize = 12.sp, color = muted) }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private fun authFeedback(message: String): String {
    val text = message.lowercase(Locale.ROOT)
    return when {
        "user not found" in text -> "账号不存在，请检查用户名或邮箱。"
        "password mismatch" in text -> "密码错误，请重试。"
        "email already" in text -> "该邮箱已经注册，请直接登录。"
        "invalid email format" in text -> "邮箱格式无效，请检查后重试。"
        "verification code expired" in text || "invalid or expired verification code" in text -> "验证码已过期，请重新获取。"
        "verification code does not match" in text -> "验证码不匹配，请使用最新邮件中的验证码。"
        "verification code required" in text -> "请输入邮箱验证码。"
        "invalid or unavailable bot invite code" in text -> "邀请码无效或已不可用，请检查后重试。"
        else -> message
    }
}

@Composable
private fun AuthLink(label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 4.dp),
        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)) { Text(label, color = color, fontSize = 13.sp, lineHeight = 20.sp) }
}

@Composable
private fun AuthField(
    value: String, onValueChange: (String) -> Unit, label: String, tag: String, background: Color, border: Color,
    modifier: Modifier = Modifier, enabled: Boolean = true, password: Boolean = false, errorMessage: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text, imeAction: ImeAction = ImeAction.Next,
    onFocus: (Boolean) -> Unit, onIme: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    var hasFocus by remember { mutableStateOf(false) }
    val fieldBorder = when { errorMessage != null -> MaterialTheme.colorScheme.error; hasFocus -> Color(0xFF898E8D); else -> border }
    Column(modifier) {
        BasicTextField(
            value, onValueChange, enabled = enabled, singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
                .onFocusChanged { hasFocus = it.isFocused; onFocus(it.isFocused) }
                .semantics { contentDescription = label; errorMessage?.let { error(it) } },
            textStyle = TextStyle(fontFamily = CatsCoFontFamily, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, lineHeight = 22.sp),
            visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onNext = { onIme() }, onDone = { onIme() }),
            cursorBrush = SolidColor(AuthGreen),
            decorationBox = { inner ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(background, AuthShape).border(1.dp, fieldBorder, AuthShape), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).padding(start = 14.dp, end = if (password) 0.dp else 14.dp, top = 12.dp, bottom = 12.dp)) {
                        if (value.isEmpty()) Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp, lineHeight = 22.sp)
                        inner()
                    }
                    if (password) IconButton(onClick = { visible = !visible }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(if (visible) R.drawable.message_eye_off else R.drawable.message_eye),
                            if (visible) "隐藏$label" else "显示$label", Modifier.size(CatsCoMobileSpec.actionIconSize), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
                    }
                }
            },
        )
        errorMessage?.let { Text(it, Modifier.padding(start = 1.dp, top = 5.dp), color = MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 18.sp) }
    }
}
