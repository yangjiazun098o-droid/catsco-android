package cc.catsco.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

internal data class PlanOverview(
    val priceFen: Long,
    val tier: Int,
    val introduction: String,
    val modelAccess: String = "",
    val monthlyCredits: Long = 0,
    val allowances: List<Pair<String, String>> = emptyList(),
    val benefits: List<String>,
) {
    val monthlyCredit: Boolean get() = monthlyCredits > 0
}

// Copy from the desktop plan chooser. Quantitative claims only apply to matching offers.
private val desktopPlanOverviews = mapOf(
    "catsco-credit-v1-monthly" to PlanOverview(
        3900, 1, "适合提炼资料重点、起草邮件和文案。",
        modelAccess = "支持 DeepSeek Flash，适合轻量调用和基础体验。",
        monthlyCredits = 4000,
        allowances = listOf("模型调用优惠" to "无优惠"),
        benefits = listOf("短文阅读与重点摘要", "邮件回复与日常文案起草", "基础表格整理与格式转换", "简单问答与资料归纳"),
    ),
    "catsco-credit-v2-monthly" to PlanOverview(
        7900, 2, "适合每天整理文件、汇总数据和待办。",
        modelAccess = "可切换已开通点数计费的模型，当前支持 DeepSeek Flash。",
        monthlyCredits = 12000,
        allowances = listOf("模型调用优惠" to "无优惠"),
        benefits = listOf("多份资料对比与综合归纳", "数据分析与图表解读", "代码编写、解释与问题排查", "项目方案与报告撰写"),
    ),
    "catsco-credit-v3-monthly" to PlanOverview(
        15900, 3, "适合多项工作并行、持续推进项目。",
        modelAccess = "可切换已开通点数计费的模型，当前支持 DeepSeek Flash。",
        monthlyCredits = 22500,
        allowances = listOf("模型调用优惠" to "9 折"),
        benefits = listOf("更频繁的长文与资料分析", "复杂代码与方案多轮迭代", "研究、写作任务持续推进", "多项工作交替处理与复盘"),
    ),
    "catsco-team" to PlanOverview(
        59900, 4, "适合团队共同使用与管理 AI 员工。",
        allowances = listOf("团队成员" to "最多 10 人", "Token 额度" to "最高 30 亿"),
        benefits = listOf("独享服务器，8 核 CPU · 16 GB 内存", "工作经验与 Skill 供全团队复用", "集中管理和共享知识库", "管理成员权限与团队资源访问范围"),
    ),
)

internal fun desktopPlanOverview(plan: JSONObject): PlanOverview? =
    desktopPlanOverviews[plan.optString("slug")]?.takeIf {
        plan.optLong("price_fen") == it.priceFen && plan.optInt("plan_tier") == it.tier &&
            plan.optInt("duration_days") == 30 && plan.optString("currency").equals("CNY", ignoreCase = true) &&
            (!it.monthlyCredit || plan.optLong("model_credit_units") == it.monthlyCredits * 1_000_000)
    }

@Composable
internal fun CommercialPlanBenefits(overview: PlanOverview) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (overview.modelAccess.isNotBlank()) {
            Text("模型支持", style = MaterialTheme.typography.titleSmall)
            Text(overview.modelAccess, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (overview.monthlyCredit) "适用场景" else "团队权益",
            modifier = Modifier.padding(top = if (overview.modelAccess.isBlank()) 0.dp else 6.dp),
            style = MaterialTheme.typography.titleSmall)
        overview.benefits.forEach { benefit ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(benefit, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
