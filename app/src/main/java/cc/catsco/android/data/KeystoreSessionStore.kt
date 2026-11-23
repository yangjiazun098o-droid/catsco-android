package cc.catsco.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import cc.catsco.core.*
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreSessionStore(context: Context) : SessionStore {
    private val prefs = context.getSharedPreferences("encrypted_session", Context.MODE_PRIVATE)
    private val alias = "catsco.session.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun read(): Session? {
        val raw = prefs.getString("value", null) ?: return null
        return try {
            val parts = raw.split(':')
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            val json = JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
            Session(ServerAddress.parse(json.getString("server")), json.getString("token"), json.getString("uid"), json.getString("name"), json.optString("avatar_url"))
        } catch (_: Exception) { clear(); null }
    }
    private fun seal(session: Session): String {
        val json = JSONObject().put("server", session.server.value).put("token", session.token).put("uid", session.userId).put("name", session.name).put("avatar_url", session.avatarUrl)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }
    override fun write(session: Session) {
        check(prefs.edit().putString("value", seal(session)).commit()) { "无法保存登录状态。" }
    }
    suspend fun save(session: Session) {
        // Cancellation during encryption must not put an old token back after logout.
        val value = withContext(Dispatchers.IO) { seal(session) }
        prefs.edit().putString("value", value).apply()
    }
    override fun clear() { check(prefs.edit().clear().commit()) { "无法清除登录状态。" } }
}
