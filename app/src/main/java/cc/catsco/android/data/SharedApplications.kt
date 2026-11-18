package cc.catsco.android.data

import cc.catsco.core.Employee
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Catalogues belong to individual Agents. A failed catalogue must not discard others. */
internal suspend fun loadSharedApplications(
    agents: List<Employee>,
    fetch: suspend (String) -> JSONObject,
): JSONObject = coroutineScope {
    val permits = Semaphore(4)
    val results = agents.distinctBy { it.id }.map { agent -> async {
        permits.withPermit {
            try {
                val apps = fetch(agent.id).objects("apps").map { app ->
                    JSONObject(app.toString()).put("source_agent_id", agent.id).put("source_agent_name", agent.name)
                }
                apps to ""
            } catch (canceled: CancellationException) { throw canceled }
            catch (failure: Exception) {
                if (failure is ApiFailure && failure.status == 401) throw failure
                emptyList<JSONObject>() to if ((failure as? ApiFailure)?.status in setOf(404, 405, 501)) "" else agent.id
            }
        }
    } }.awaitAll()
    val apps = results.flatMap { it.first }.distinctBy(::applicationKey)
        .sortedByDescending(::applicationTimestamp)
    JSONObject().put("apps", JSONArray(apps)).put("failed_agents", JSONArray(results.map { it.second }.filter(String::isNotBlank)))
}

internal fun applicationKey(app: JSONObject): String = JSONArray(listOf(
    app.first("source_agent_id", "agent", "agent_uid"), app.first("id", "url"),
)).toString()

internal fun applicationTimestamp(app: JSONObject): Long {
    val value = app.first("updated_at", "updatedAt", "created_at")
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrElse {
        runCatching { LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrDefault(0)
    }
}

/** Only catalogue addresses are shared. Launch URLs contain a private, short-lived code. */
internal fun applicationShareUrl(app: JSONObject, server: String): String {
    val urls = (listOf(app.first("url")) + app.optJSONArray("urls").let { list ->
        (0 until (list?.length() ?: 0)).map { list!!.optString(it) }
    }).mapNotNull { runCatching { URI(it.trim()) }.getOrNull() }
        .filter { it.scheme == "https" && !it.host.isNullOrBlank() && it.rawUserInfo == null }
    val family = runCatching { URI(server).host?.substringAfter('.') }.getOrNull()
    return (urls.firstOrNull { family != null && it.host.endsWith(".$family") } ?: urls.firstOrNull())?.toASCIIString().orEmpty()
}
