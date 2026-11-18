package cc.catsco.android.data

import org.json.JSONObject
import java.util.Locale

internal data class AgentQuotaPresentation(val model: String, val remaining: String)

internal fun agentQuotaPresentation(value: JSONObject): AgentQuotaPresentation {
    val summary = value.optJSONObject("summary")
    val unavailable = if (value.optBoolean("shared")) "未共享" else "暂无信息"
    val model = summary?.first("model").orEmpty().ifBlank { unavailable }
    val percent = summary?.optDouble("remaining_percent", Double.NaN) ?: Double.NaN
    val remaining = when {
        percent.isFinite() && percent >= 0 -> if (percent == percent.toLong().toDouble()) "${percent.toLong()}%" else String.format(Locale.ROOT, "%.1f%%", percent)
        summary?.optString("status") == "custom" -> "自备模型"
        else -> unavailable
    }
    return AgentQuotaPresentation(model, remaining)
}
