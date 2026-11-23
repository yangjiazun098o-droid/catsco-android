package cc.catsco.android.data

import org.json.JSONObject
import java.math.BigDecimal
import java.net.URI
import java.time.Instant

internal const val AlipayCheckoutChannel = "alipay_page"
internal const val CommercialOrdersPath = "/api/relay/commercial/orders"
private val orderNumberPattern = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}")
private val cnyInputPattern = Regex("[0-9]{1,12}(?:[.][0-9]{1,2})?")

internal data class CommercialPurchase(
    val name: String,
    val amountFen: Long,
    val planId: Long? = null,
    val credits: String = "",
) {
    val storageKey: String
        get() = commercialPurchaseKey(planId, amountFen)

    fun requestBody(id: String): JSONObject = JSONObject()
        .put("channel", AlipayCheckoutChannel)
        .put("client_request_id", id)
        .apply {
            if (planId != null) put("plan_id", planId)
            else put("kind", "credit_topup").put("amount_fen", amountFen)
        }

    fun matchesOrder(order: JSONObject): Boolean =
        order.optString("channel") == AlipayCheckoutChannel &&
            order.optString("currency").equals("CNY", ignoreCase = true) &&
            order.optLong("amount_fen") > 0 &&
            if (planId == null) {
                order.optString("kind") == "credit_topup" && order.optLong("plan_id") == 0L &&
                    order.optLong("amount_fen") == amountFen
            } else {
                order.optString("kind") in setOf("", "plan") && order.optLong("plan_id") == planId
            }
}

internal fun commercialOrderNumber(value: String): String? = value.takeIf(orderNumberPattern::matches)

private fun commercialPurchaseKey(planId: Long?, amountFen: Long): String =
    if (planId == null) "commercial:topup:$amountFen" else "commercial:plan:$planId"

internal fun commercialPurchaseKey(order: JSONObject): String? = when {
    order.optString("channel") != AlipayCheckoutChannel -> null
    order.optString("kind") == "credit_topup" && order.optLong("amount_fen") > 0 ->
        commercialPurchaseKey(null, order.optLong("amount_fen"))
    order.optLong("plan_id") > 0 -> commercialPurchaseKey(order.optLong("plan_id"), 0L)
    else -> null
}

internal fun commercialOrderFinished(order: JSONObject): Boolean =
    order.optString("status") in setOf("fulfilled", "closed", "cancelled", "canceled", "expired", "refunded")

internal fun commercialOrderCancelable(order: JSONObject): Boolean =
    order.optString("channel") == AlipayCheckoutChannel &&
        order.optString("status") in setOf("created", "pending", "failed")

internal fun alipayCheckoutUrl(order: JSONObject, now: Instant = Instant.now()): String? {
    if (order.optString("channel") != AlipayCheckoutChannel || order.optString("status") != "pending") return null
    val expiry = order.first("expires_at")
    if (expiry.isNotEmpty() && runCatching { Instant.parse(expiry).isAfter(now) }.getOrDefault(false).not()) return null
    val checkout = order.first("checkout_url")
    val uri = runCatching { URI(checkout) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    return checkout.takeIf {
        uri.scheme.equals("https", ignoreCase = true) && (host == "alipay.com" || host.endsWith(".alipay.com")) &&
            uri.userInfo == null && uri.port in setOf(-1, 443)
    }
}

internal fun amountInFen(input: String): Long? = input.trim().takeIf(cnyInputPattern::matches)
    ?.toBigDecimal()?.movePointRight(2)?.toLong()

internal fun yuanAmount(amountFen: Long): String =
    BigDecimal.valueOf(amountFen, 2).stripTrailingZeros().toPlainString()

internal fun creditAmount(units: Long, unitsPerCredit: Long = 1_000_000): String =
    BigDecimal.valueOf(units).divide(BigDecimal.valueOf(unitsPerCredit), 6, java.math.RoundingMode.DOWN)
        .stripTrailingZeros().toPlainString()

internal fun topUpCreditAmount(amountFen: Long, creditsPerYuan: Long): String =
    BigDecimal.valueOf(amountFen, 2).multiply(BigDecimal.valueOf(creditsPerYuan)).stripTrailingZeros().toPlainString()

internal fun alipayAvailable(catalog: JSONObject): Boolean = catalog.optBoolean("enabled") &&
    catalog.optBoolean("payment_configured") && catalog.objects("channels").any {
        it.optString("id") == AlipayCheckoutChannel && !it.optBoolean("test_mode")
    }

internal fun commercialPurchaseError(message: String): String = when (message) {
    "payment channel is unavailable" -> "支付宝支付暂不可用，请稍后再试。"
    "commercial plan is unavailable" -> "该套餐暂不可购买，请返回刷新套餐列表。"
    "purchase limit reached" -> "该套餐已达到购买次数上限。"
    "credit top-up amount is out of range", "invalid credit top-up amount" -> "充值金额超出当前范围，请刷新后重新选择。"
    "credit top-up is not enabled" -> "点数充值暂未开放。"
    "order can no longer be cancelled", "order status changed while cancelling", "payment has already completed" ->
        "订单状态已更新，请查看最新状态。"
    else -> message
}
