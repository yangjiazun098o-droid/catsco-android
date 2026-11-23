package cc.catsco.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import cc.catsco.android.WorkspaceState
import cc.catsco.android.WorkspaceViewModel
import cc.catsco.android.data.*
import org.json.JSONObject

@Composable
internal fun CommercialPlans(state: WorkspaceState, model: WorkspaceViewModel) {
    val catalog = state.resource.value
    val plans = catalog.objects("plans").filter { it.optLong("id") > 0L && it.optLong("price_fen") > 0L }
    val runningEntitlements = catalog.optJSONObject("account")?.optJSONObject("summary")
        ?.objects("entitlements").orEmpty().filter { it.optString("state") == "active" }
    fun isActive(plan: JSONObject) = runningEntitlements.any { entitlement ->
        val id = entitlement.optLong("plan_id")
        if (id > 0) id == plan.optLong("id")
        else entitlement.first("plan_slug").let { it.isNotBlank() && it == plan.first("slug") }
    }
    val activeTier = plans.filter(::isActive).maxOfOrNull { it.optInt("plan_tier") } ?: 0
    val paymentAvailable = alipayAvailable(catalog)
    val hasMonthlyCreditPlans = plans.any { desktopPlanOverview(it)?.monthlyCredit == true }
    var purchase by remember { mutableStateOf<CommercialPurchase?>(null) }

    ModuleForm {
        if (plans.isEmpty()) ModuleSection {
            ModuleEmpty("暂无可购买的套餐", "可在套餐与权益中查看已开通的内容。")
        }
        if (plans.isNotEmpty() && !paymentAvailable) CommerceHint("支付宝支付暂未开放。")
        plans.forEach { plan ->
            val name = plan.first("name").ifBlank { "套餐" }
            val tier = plan.optInt("plan_tier")
            val active = isActive(plan)
            val covered = tier > 0 && tier < activeTier
            val cny = plan.optString("currency").equals("CNY", ignoreCase = true)
            val amountFen = plan.optLong("price_fen")
            val credits = plan.optLong("model_credit_units").takeIf { it > 0 }?.let { creditAmount(it) }.orEmpty()
            val overview = desktopPlanOverview(plan)
            ModuleSection(spacing = 12.dp) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(orderAmount(plan), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp, lineHeight = 36.sp))
                val introduction = overview?.introduction ?: plan.first("description")
                introduction.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                ModuleInfoRows(listOf(
                    if (overview?.monthlyCredit == true) "计费周期" to "每月"
                    else "有效期" to plan.optInt("duration_days").takeIf { it > 0 }?.let { "$it 天" }.orEmpty(),
                    (if (overview?.monthlyCredit == true) "月额度" else "模型点数") to
                        credits.takeIf(String::isNotBlank)?.let { "$it 点" }.orEmpty(),
                    "云托管" to if (plan.optBoolean("cloud_worker_included")) "包含" else "",
                ) + overview?.allowances.orEmpty())
                overview?.let { CommercialPlanBenefits(it) }
                when {
                    active -> CommerceHint("当前套餐 · 生效中")
                    covered -> CommerceHint("当前套餐已包含此档权益")
                    else -> CatsCoButton(
                        { purchase = CommercialPurchase(name, amountFen, plan.optLong("id"), credits) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = cny && paymentAvailable && !state.busy && !state.resource.loading && state.resource.error == null,
                    ) { Text("选择套餐") }
                }
            }
        }
        if (hasMonthlyCreditPlans) {
            CommerceHint("有效期内未用月额度结转下一期。套餐到期后，未用额度冻结，续费后解锁。模型调用按实际用量及套餐费率扣费。")
        }
    }
    purchase?.let { selected ->
        val unchanged = plans.any { it.optLong("id") == selected.planId && it.optLong("price_fen") == selected.amountFen }
        ConfirmPurchase(selected, paymentAvailable && unchanged, state, model) { purchase = null }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommercialTopup(state: WorkspaceState, model: WorkspaceViewModel) {
    val catalog = state.resource.value
    val config = catalog.optJSONObject("credit_topup") ?: JSONObject()
    val account = catalog.optJSONObject("credit_account")
    val unitScale = account?.optLong("units_per_credit") ?: 0L
    val paidUnits = account?.first("paid_units")?.toLongOrNull()
    val balance = paidUnits?.takeIf { unitScale > 0 && account?.optBoolean("enabled") == true }
        ?.let { "${creditAmount(it, unitScale)} 点" }
    val minimum = config.optLong("min_amount_fen")
    val maximum = config.optLong("max_amount_fen")
    val rate = config.optLong("credits_per_cny")
    val enabled = alipayAvailable(catalog) && config.optBoolean("enabled") && minimum > 0 && maximum >= minimum && rate > 0
    var input by rememberSaveable { mutableStateOf("") }
    val amount = amountInFen(input)
    val valid = amount != null && amount in minimum..maximum
    val projected = amount?.takeIf { valid }?.let { topUpCreditAmount(it, rate) }.orEmpty()
    var purchase by remember { mutableStateOf<CommercialPurchase?>(null) }
    val focus = LocalFocusManager.current

    ModuleForm(Modifier.imePadding()) {
        if (balance != null) ModuleSection(spacing = 8.dp) {
            CommerceHint("充值点数余额")
            Text(balance, style = MaterialTheme.typography.headlineSmall)
        }
        ModuleSection(spacing = 12.dp) {
            if (!enabled) {
                ModuleEmpty("点数充值暂未开放", config.first("reason").takeUnless { it == "点数充值暂未开放" }.orEmpty())
            } else {
                Text("充值金额", style = MaterialTheme.typography.titleMedium)
                val presets = config.optJSONArray("presets_fen")
                val presetAmounts = (0 until (presets?.length() ?: 0)).mapNotNull { index ->
                    presets?.optLong(index)?.takeIf { it in minimum..maximum }
                }.distinct()
                if (presetAmounts.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presetAmounts.forEach { preset ->
                        FilterChip(selected = preset == amount, onClick = {
                            focus.clearFocus()
                            input = yuanAmount(preset)
                        }, label = { Text("¥${yuanAmount(preset)}") }, enabled = !state.busy)
                    }
                }
                val range = "¥${yuanAmount(minimum)} – ¥${yuanAmount(maximum)}"
                ModuleTextField(input, { input = it.take(15) }, Modifier.fillMaxWidth(),
                    label = { Text("金额（元）") }, placeholder = { Text(range) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    errorMessage = if (input.isNotBlank() && !valid) "请输入 $range，最多两位小数。" else null)
                ModuleInfoRows(listOf("预计到账" to (projected.takeIf(String::isNotBlank)?.let { "$it 点" } ?: "—")))
                CatsCoButton({
                    focus.clearFocus()
                    amount?.let { purchase = CommercialPurchase("点数充值", it, credits = projected) }
                }, Modifier.fillMaxWidth(), enabled = valid && !state.busy && !state.resource.loading && state.resource.error == null) {
                    Text("确认充值")
                }
            }
        }
    }
    purchase?.let { selected ->
        val unchanged = enabled && selected.amountFen in minimum..maximum &&
            selected.credits == topUpCreditAmount(selected.amountFen, rate)
        ConfirmPurchase(selected, unchanged, state, model) { purchase = null }
    }
}

@Composable
private fun ConfirmPurchase(
    purchase: CommercialPurchase,
    available: Boolean,
    state: WorkspaceState,
    model: WorkspaceViewModel,
    close: () -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    CatsCoAlertDialog(
        onDismissRequest = { if (!state.busy) close() },
        properties = DialogProperties(dismissOnBackPress = !state.busy, dismissOnClickOutside = !state.busy),
        title = { Text("确认订单") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(purchase.name)
                ModuleInfoRows(listOf(
                    "支付金额" to "¥${yuanAmount(purchase.amountFen)}",
                    "模型点数" to purchase.credits.takeIf(String::isNotBlank)?.let { "$it 点" }.orEmpty(),
                    "支付方式" to "支付宝",
                ))
                if (!available) CommerceHint("购买信息已更新，请返回重新选择。")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        dismissButton = { TextButton(close, enabled = !state.busy) { Text("返回") } },
        confirmButton = {
            CatsCoButton({
                error = null
                model.createCommercialPurchase(purchase) { error = it }
            }, enabled = available && !state.busy && !state.resource.loading && state.resource.error == null) {
                Text(if (state.busy) "创建中…" else "创建订单")
            }
        },
    )
}

@Composable
internal fun CommercialOrderDetails(state: WorkspaceState, model: WorkspaceViewModel) {
    val order = state.resource.value.optJSONObject("order") ?: return
    val no = order.first("order_no")
    val status = order.optString("status")
    val isTopup = order.optString("kind") == "credit_topup"
    val canAct = !state.busy && !state.resource.loading && state.resource.error == null
    val checkout = alipayCheckoutUrl(order)
    var confirmCancel by rememberSaveable(no) { mutableStateOf(false) }
    var error by remember(no) { mutableStateOf<String?>(null) }

    ModuleForm {
        ModuleSection(spacing = 12.dp) {
            Text(commercialOrderStatus(order), style = MaterialTheme.typography.titleLarge)
            Text(orderAmount(order), style = MaterialTheme.typography.headlineSmall)
            val hint = when (status) {
                "paid" -> if (isTopup) "支付成功，点数入账中。" else "支付成功，套餐权益生效中。"
                "pending" -> if (checkout != null) "完成支付后，返回 CatsCo 查看订单状态。" else "支付链接暂不可用，请刷新订单状态。"
                "failed" -> "可取消此订单，再重新购买。"
                else -> ""
            }
            hint.takeIf(String::isNotBlank)?.let { CommerceHint(it) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ModuleInfoRows(listOf(
                "商品" to order.first("plan_name").ifBlank { if (isTopup) "点数充值" else "套餐" },
                "模型点数" to order.optLong("plan_model_credit_units").takeIf { it > 0 }?.let { "${creditAmount(it)} 点" }.orEmpty(),
                "订单编号" to no,
                "下单时间" to displayModuleDate(order.first("created_at")),
                "支付截止" to if (status == "pending") displayModuleDate(order.first("expires_at")) else "",
            ))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (checkout != null) CatsCoButton({
                val current = model.state.value.resource.value.optJSONObject("order")
                current?.let { alipayCheckoutUrl(it) }?.let(model::openUrl) ?: model.loadResource()
            }, Modifier.fillMaxWidth(), enabled = canAct) { Text("前往支付宝") }
            CatsCoSecondaryButton({ error = null; model.loadResource() }, Modifier.fillMaxWidth(),
                enabled = !state.busy && !state.resource.loading) {
                Text(if (state.resource.loading) "正在刷新…" else "刷新订单状态")
            }
            if (commercialOrderCancelable(order)) TextButton({ confirmCancel = true }, Modifier.fillMaxWidth(), enabled = canAct) {
                Text("取消订单")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (confirmCancel) CatsCoAlertDialog(
        onDismissRequest = { confirmCancel = false },
        title = { Text("取消订单？") },
        text = { Text("未支付的订单将被关闭。") },
        dismissButton = { TextButton({ confirmCancel = false }) { Text("保留订单") } },
        confirmButton = {
            CatsCoButton({
                confirmCancel = false
                error = null
                model.cancelCommercialOrder(no) { error = it }
            }, enabled = canAct && commercialOrderCancelable(order)) { Text("确认取消") }
        },
    )
}

internal fun commercialOrderStatus(order: JSONObject): String = when (val status = order.first("status", "state")) {
    "fulfilled" -> if (order.optString("kind") == "credit_topup") "已到账" else "已生效"
    "paid" -> "已支付"
    "" -> "状态待更新"
    else -> displayOrderStatus(status)
}

@Composable
private fun CommerceHint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}
