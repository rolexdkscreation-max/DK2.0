package com.dk.assistant.connect

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * v3.72: कनेक्टर की हालत और token - सिर्फ़ इसी फ़ोन में.
 * Token (Meta Page token, MCP token, पेज टोकन) Android Keystore की AES-GCM key से बंद होकर SharedPreferences में;
 * key फ़ोन के सुरक्षित हिस्से में रहती है, बाहर नहीं निकलती. Worker या किसी server पर कुछ नहीं जाता.
 * Google का token हम रखते ही नहीं - Google Play services ज़रूरत पर खुद देता है (सिर्फ़ "जुड़ा है" का निशान यहाँ).
 */
object ConnectorStore {
    private const val FILE = "dk_connect"
    private const val ALIAS = "dk_connect_key"

    data class McpServer(val id: String, val name: String, val url: String, val tools: List<ToolSpec>)

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- चालू / बंद
    fun isOn(c: Context, id: String) = sp(c).getBoolean("on_$id", false)
    fun setOn(c: Context, id: String, on: Boolean) = sp(c).edit().putBoolean("on_$id", on).apply()

    /** जुड़े कनेक्टर (tool वाले) - GitHub = कोडिंग एजेंट वाला token हो तो */
    fun connected(c: Context): List<ConnectorDef> = Catalog.ALL.filter { d ->
        when (d.kind) {
            Kind.GITHUB -> com.dk.assistant.Prefs.ghToken(c).isNotBlank() && sp(c).getBoolean("on_github", true)
            Kind.META -> isOn(c, d.id) && secret(c, "meta_token").isNotBlank() &&
                (d.id != "instagram" || metaPages(c).any { it.igId.isNotBlank() })
            Kind.GOOGLE -> isOn(c, d.id)
            else -> false
        }
    }

    // ---------------------------------------------------------------- बंद (encrypted) token
    fun secret(c: Context, key: String): String = sp(c).getString("s_$key", null)?.let { open(it) }.orEmpty()
    fun setSecret(c: Context, key: String, v: String?) {
        val e = sp(c).edit()
        if (v.isNullOrBlank()) e.remove("s_$key") else seal(v)?.let { e.putString("s_$key", it) }
        e.apply()
    }

    fun metaPages(c: Context): List<Graph.Page> = Graph.pagesFromJson(secret(c, "meta_pages"))
    fun setMetaPages(c: Context, p: List<Graph.Page>) = setSecret(c, "meta_pages", if (p.isEmpty()) null else Graph.pagesToJson(p))

    // ---------------------------------------------------------------- MCP servers
    fun mcpServers(c: Context): List<McpServer> = try {
        val a = JSONArray(sp(c).getString("mcp_list", "[]"))
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            McpServer(it.optString("id"), it.optString("name"), it.optString("url"), Mcp.toolsFromJson(it.optString("tools")))
        }
    } catch (_: Exception) { emptyList() }

    fun saveMcp(c: Context, s: McpServer, token: String?) {
        val list = mcpServers(c).filter { it.id != s.id } + s
        writeMcp(c, list)
        if (token != null) setSecret(c, "mcp_tok_${s.id}", token)
    }

    fun removeMcp(c: Context, id: String) {
        writeMcp(c, mcpServers(c).filter { it.id != id })
        setSecret(c, "mcp_tok_$id", null); sp(c).edit().remove("mcp_sess_$id").apply()
    }

    fun mcpSession(c: Context, id: String) = sp(c).getString("mcp_sess_$id", "") ?: ""
    fun setMcpSession(c: Context, id: String, v: String?) = sp(c).edit().putString("mcp_sess_$id", v ?: "").apply()

    private fun writeMcp(c: Context, list: List<McpServer>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("tools", Mcp.toolsToJson(it.tools))) }
        sp(c).edit().putString("mcp_list", a.toString()).apply()
    }

    fun newMcpId(c: Context): String {
        val used = mcpServers(c).map { it.id }.toSet()
        var n = 1; while ("mcp_$n" in used) n++
        return "mcp_$n"
    }

    // ---------------------------------------------------------------- Keystore AES-GCM
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }

    private fun seal(plain: String): String? = try {
        val ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.ENCRYPT_MODE, key())
        Base64.encodeToString(Box.pack(ci.iv, ci.doFinal(plain.toByteArray(Charsets.UTF_8))), Base64.NO_WRAP)
    } catch (e: Exception) { com.dk.assistant.DKLog.d("कनेक्टर: token बंद नहीं हुआ (${e.javaClass.simpleName})"); null }

    private fun open(stored: String): String? = try {
        val (iv, ct) = Box.unpack(Base64.decode(stored, Base64.NO_WRAP)) ?: throw IllegalStateException("bad box")
        val ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(ci.doFinal(ct), Charsets.UTF_8)
    } catch (e: Exception) { com.dk.assistant.DKLog.d("कनेक्टर: token खुला नहीं (${e.javaClass.simpleName})"); null }
}

/** [iv की लंबाई 1 byte][iv][ciphertext] - शुद्ध, test होता है */
object Box {
    fun pack(iv: ByteArray, ct: ByteArray): ByteArray = byteArrayOf(iv.size.toByte()) + iv + ct
    fun unpack(b: ByteArray): Pair<ByteArray, ByteArray>? {
        if (b.isEmpty()) return null
        val n = b[0].toInt() and 0xFF
        if (n !in 12..16 || b.size <= 1 + n) return null
        return b.copyOfRange(1, 1 + n) to b.copyOfRange(1 + n, b.size)
    }
}
