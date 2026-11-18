package cc.catsco.android.ui

import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cc.catsco.android.WorkspaceState
import cc.catsco.android.WorkspaceViewModel
import cc.catsco.android.data.encodeSegment
import cc.catsco.android.data.first
import cc.catsco.android.data.ApiFailure
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLEncoder

internal fun knowledgeUrlAllowed(origin: String, target: String): Boolean = runCatching {
    val base = URI(origin)
    val url = URI(target)
    val basePort = if (base.port < 0) 443 else base.port
    val urlPort = if (url.port < 0) 443 else url.port
    url.scheme == "https" && url.rawUserInfo == null && url.host.equals(base.host, ignoreCase = true) && urlPort == basePort
}.getOrDefault(false)

/** The hosted knowledge workspace exchanges a single-use POST, without the account bearer. */
@Composable
internal fun AgentKnowledgeScreen(state: WorkspaceState, model: WorkspaceViewModel) {
    val session = state.session ?: return
    val uid = state.page.removePrefix("agent-knowledge/")
    var attempt by remember(session.userId, uid) { mutableIntStateOf(0) }
    var postBody by remember(session.userId, uid) { mutableStateOf<ByteArray?>(null) }
    var error by remember(session.userId, uid) { mutableStateOf<String?>(null) }
    var errorStatus by remember(session.userId, uid) { mutableStateOf<Int?>(null) }
    val appearanceScript by rememberUpdatedState(knowledgeAppearanceScript(MaterialTheme.colorScheme.background.luminance() < .5f))
    val background = MaterialTheme.colorScheme.background.toArgb()
    LaunchedEffect(session, uid, attempt) {
        postBody = null
        error = null
        errorStatus = null
        try {
            val result = model.request(session, "/api/agents/${encodeSegment(uid)}/knowledge/handoff", method = "POST")
            val token = result.first("token")
            check(token.isNotBlank()) { "知识库响应缺少登录凭证。" }
            postBody = "token=${URLEncoder.encode(token, "UTF-8")}&agent_uid=${URLEncoder.encode(uid, "UTF-8")}".toByteArray(Charsets.UTF_8)
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (failure: Exception) {
            if (failure is ApiFailure && failure.status == 401) model.report(failure)
            error = failure.message ?: "知识库暂时无法打开。"
            errorStatus = (failure as? ApiFailure)?.status
        }
    }
    Column(Modifier.fillMaxSize()) {
        when {
            error != null -> Column(Modifier.padding(16.dp)) {
                ModuleError(error.orEmpty(), errorStatus, { attempt++ }, model::back)
            }
            postBody == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> key(postBody) {
                val body = postBody!!
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            tag = false
                            setBackgroundColor(background)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String) {
                                    if (view.tag != true && knowledgeUrlAllowed(session.server.value, url)) view.evaluateJavascript(appearanceScript, null)
                                }
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                    !knowledgeUrlAllowed(session.server.value, request.url.toString())

                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                    if (!knowledgeUrlAllowed(session.server.value, request.url.toString())) {
                                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                                    }
                                    // Serve packaged fonts at this allowed origin, without a network request
                                    // or enabling file access in the hosted workspace.
                                    val path = request.url.path.orEmpty()
                                    if (path.startsWith("/__catsco_native_fonts__/")) {
                                        val resource = CatsCoFonts.webResources[path.removePrefix("/__catsco_native_fonts__/")]
                                        return if (resource != null) WebResourceResponse("font/ttf", null, context.resources.openRawResource(resource))
                                        else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                                    }
                                    return null
                                }

                                override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                                    if (request.isForMainFrame) error = "知识库加载失败，请重试。"
                                }

                                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                    if (request.isForMainFrame) {
                                        error = "知识库请求失败（HTTP ${response.statusCode}）。"
                                        errorStatus = response.statusCode
                                    }
                                }
                            }
                            // Clear the previous workspace's browser session before exchanging this account's token.
                            android.webkit.WebStorage.getInstance().deleteAllData()
                            CookieManager.getInstance().removeAllCookies {
                                if (tag != true) postUrl("${session.server.value}/wiki/handoff", body)
                            }
                        }
                    },
                    update = { view ->
                        view.setBackgroundColor(background)
                        if (view.url?.let { knowledgeUrlAllowed(session.server.value, it) } == true) view.evaluateJavascript(appearanceScript, null)
                    },
                    onRelease = { view ->
                        view.tag = true
                        view.stopLoading()
                        view.clearHistory()
                        view.clearCache(true)
                        view.destroy()
                        CookieManager.getInstance().removeAllCookies(null)
                        android.webkit.WebStorage.getInstance().deleteAllData()
                    },
                )
            }
        }
    }
}

/** Adapt only the hosted knowledge surface to the native palette. */
internal fun knowledgeAppearanceScript(dark: Boolean): String {
    val surface = if (dark) "#0f0f0f" else "#f8f8f8"
    val elevated = if (dark) "#1a1a1a" else "#ffffff"
    val text = if (dark) "#ececec" else "#1f1f1f"
    val muted = if (dark) "#9a9aa0" else "#747479"
    val border = if (dark) "#303032" else "#e4e4e8"
    val accent = if (dark) "#7be0c1" else "#147c65"
    val css = """
        @font-face { font-family:'CatsCo PuHuiTi'; src:url('/__catsco_native_fonts__/regular.ttf') format('truetype'); font-weight:400; font-display:swap; }
        @font-face { font-family:'CatsCo PuHuiTi'; src:url('/__catsco_native_fonts__/medium.ttf') format('truetype'); font-weight:500; font-display:swap; }
        @font-face { font-family:'CatsCo PuHuiTi'; src:url('/__catsco_native_fonts__/semibold.ttf') format('truetype'); font-weight:600; font-display:swap; }
        @font-face { font-family:'CatsCo PuHuiTi'; src:url('/__catsco_native_fonts__/bold.ttf') format('truetype'); font-weight:700; font-display:swap; }
        html, body, .cc-knowledge-wiki, .cc-knowledge-wiki *:not(svg):not(svg *) { font-family:'CatsCo PuHuiTi', sans-serif !important; }
        :root, .cc-knowledge-wiki { color-scheme: ${if (dark) "dark" else "light"}; --v3-surface:$surface;
        --v3-surface-elevated:$elevated; --v3-text-name:$text; --v3-text-muted:$muted;
        --v3-border:$border; --v3-accent:$accent; }
        html, body { background:$surface; color:$text; }
        .cc-knowledge-wiki { padding:16px; }
        .cc-knowledge-wiki-search input { --cc-field-surface:transparent;
        --cc-field-ink:$text; --cc-field-placeholder:$muted; --cc-field-border:$border; }
        .cc-knowledge-wiki-card p, .cc-knowledge-wiki-state { font-size:14px; line-height:1.6; }
        .cc-knowledge-wiki-card { border-radius:16px; }
        .cc-pwa-prompt[aria-label="应用更新"] { display:none; }
    """.trimIndent()
    return """(() => {
        let style = document.getElementById('catsco-native-knowledge');
        if (!style) { style = document.createElement('style'); style.id = 'catsco-native-knowledge'; document.head.appendChild(style); }
        style.textContent = ${org.json.JSONObject.quote(css)};
    })();""".trimIndent()
}
