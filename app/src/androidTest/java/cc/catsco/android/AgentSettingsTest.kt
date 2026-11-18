package cc.catsco.android

import cc.catsco.android.data.agentQuotaPresentation
import cc.catsco.android.data.employee
import cc.catsco.android.ui.knowledgeUrlAllowed
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentSettingsTest {
    @Test fun modelAndQuotaUseOnlyReturnedValues() {
        val quota = agentQuotaPresentation(JSONObject("""{"summary":{"model":"minimax-m3","remaining_percent":37.5}}"""))
        assertEquals("minimax-m3", quota.model)
        assertEquals("37.5%", quota.remaining)
        val full = agentQuotaPresentation(JSONObject("""{"summary":{"model":"model","remaining_percent":100}}"""))
        assertEquals("100%", full.remaining)
    }

    @Test fun absentQuotaNeverBecomesFullQuota() {
        val unavailable = agentQuotaPresentation(JSONObject())
        assertEquals("暂无信息", unavailable.model)
        assertEquals("暂无信息", unavailable.remaining)
        assertEquals("未共享", agentQuotaPresentation(JSONObject("""{"shared":true}""")).remaining)
        assertEquals("自备模型", agentQuotaPresentation(JSONObject("""{"summary":{"status":"custom"}}""")).remaining)
    }

    @Test fun knowledgeNavigationStaysOnTheAuthenticatedHttpsOrigin() {
        val origin = "https://app.catsco.cc"
        assertTrue(knowledgeUrlAllowed(origin, "https://app.catsco.cc/wiki/page"))
        assertTrue(knowledgeUrlAllowed(origin, "https://app.catsco.cc:443/wiki/page"))
        assertFalse(knowledgeUrlAllowed(origin, "https://app.catsco.cc.evil.example/wiki"))
        assertFalse(knowledgeUrlAllowed(origin, "https://app.catsco.cc:8443/wiki"))
        assertFalse(knowledgeUrlAllowed(origin, "https://user:password@app.catsco.cc/wiki"))
        assertFalse(knowledgeUrlAllowed(origin, "http://app.catsco.cc/wiki"))
        assertFalse(knowledgeUrlAllowed(origin, "file:///sdcard/private"))
    }

    @Test fun rosterProvidesAgentIdentityAndAvatar() {
        val agent = employee(JSONObject("""{"id":23,"username":"agent-23","display_name":"Agent","avatar_url":"/uploads/agent.png","relation":"friend"}"""))
        assertEquals("agent-23", agent.username)
        assertEquals("/uploads/agent.png", agent.avatarUrl)
        assertFalse(agent.owner)
    }
}
