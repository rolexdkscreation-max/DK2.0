package com.dk.assistant

import android.content.Context
import com.dk.assistant.backend.BackendApi
import com.dk.assistant.backend.BackendConfig
import com.dk.assistant.backend.BackendException
import com.dk.assistant.backend.BackendWire

/**
 * 17 नवंबर 2026 (मालिक का "DK बैकएंड मास्टर प्रॉम्प्ट"): AI का क्रम - सिर्फ़ यही एक परत.
 *
 *   1) उस काम की अपनी key (AI ब्रेन में डाली)  2) ऑलराउंड ("एक key से सब") key  3) DK का सर्वर (backend)
 *
 * 1 और 2 पहले जैसे [BrainManager] / [CoderAi] / [Transcriber] में - कुछ नहीं बदला. backend सिर्फ़ तब, जब
 * key नहीं / खत्म / limit / गलती ([fallback]). backend ऐप की कोई key नहीं लेता - सिर्फ़ Firebase पहचान.
 *
 * बाद में "सब काम backend से" करना हो: बस [backendFirst] में उस काम का नाम जोड़ें (या `true` लौटाएँ) - बाकी ऐप नहीं बदलता.
 */
object AiRouter {
    const val LABEL = "DK सर्वर"

    /** काम के नाम - backend के TASK_LEVEL जैसे */
    object Task { const val CHAT = "chat"; const val VOICE = "voice"; const val LIVE = "live"; const val SEARCH = "search"
        const val RESEARCH = "research"; const val CODER = "coder"; const val COMPUTER = "computer"; const val HEAVY = "heavy" }

    /** test बदल सके */
    @Volatile var readyOverride: Boolean? = null

    fun backendReady(): Boolean = readyOverride ?: BackendConfig.ready()

    /** आज: कोई काम backend से पहले नहीं (अपनी key पहले). बाद में बदलना = सिर्फ़ यहाँ */
    @Suppress("UNUSED_PARAMETER")
    fun backendFirst(task: String): Boolean = false

    /**
     * अपनी keys से नहीं हुआ (गलती [e], या key ही नहीं = null) - अब backend आज़माएँ?
     * रुकावट (cancel) / backend की अपनी गलती / race हारना = नहीं.
     */
    fun fallback(e: Throwable?): Boolean = backendReady() && when (e) {
        is kotlinx.coroutines.CancellationException, is LoserException, is BackendException -> false
        else -> true
    }

    /** दोनों गलतियों में से कौन सी बताएँ: लाइसेंस वाली हमेशा; key नहीं थी = backend की; वरना अपनी key वाली (ज़्यादा काम की) */
    fun pick(own: Exception?, backend: Exception): Exception = when {
        backend is BackendException && backend.final -> backend
        own == null || own.message == "NO_KEY" || (own is AiException && own.code == -1) -> backend
        else -> own
    }

    // ---- कोडिंग टीम / DK कंप्यूटर ([CoderAi] की chain के आख़िर में)
    const val PROVIDER_ID = "dk_server"
    /** [Providers.all] में नहीं (Settings में key का कोई डिब्बा नहीं बनता) - सिर्फ़ chain का आख़िरी हिस्सा */
    val provider = Provider(PROVIDER_ID, LABEL, true, "DK का अपना सर्वर (लाइसेंस से)", "", "auto", false, "")
    @Volatile private var app: Context? = null
    fun install(c: Context) { app = c.applicationContext }

    /** [CoderAi] के संदेश (user/assistant) -> backend. [task] = coder / computer (premium) */
    fun coderChat(task: String, system: String, msgs: List<Pair<String, String>>): String {
        val c = app ?: throw AiException(-1, "NO_KEY")
        val last = msgs.lastOrNull { it.first == "user" }?.second.orEmpty()
        val idx = msgs.indexOfLast { it.first == "user" }
        val hist = msgs.filterIndexed { i, _ -> i != idx }.map { (r, t) -> Turn(if (r == "user") "user" else "model", t) }
        return client(c, task).complete(system, hist, last, null).ifBlank { throw AiException(0, "khali jawab") }
    }

    /** backend वाला AI ([AiClient]) - task के हिसाब से स्तर backend जाँचता है */
    fun client(c: Context, task: String): AiClient = BackendAiClient(c.applicationContext, task)

    // ---- आवाज़ (मुफ़्त वालों का 10 मिनट): /stt का टिकट अगली चैट के साथ (वही आवाज़ दोबारा नहीं गिनी जाती)
    @Volatile private var ticket: String? = null
    @Volatile private var ticketAt = 0L
    fun noteTicket(t: String?) { ticket = t; ticketAt = System.currentTimeMillis() }
    fun takeTicket(): String? {
        val t = ticket ?: return null
        ticket = null
        return if (System.currentTimeMillis() - ticketAt < 110_000) t else null
    }

    /** 1 मिनट बचा = एक बार बोलकर/लिखकर बताओ (दिन में एक बार) */
    @Volatile private var warnedDay = ""
    fun takeWarn(): String? {
        val q = BackendApi.lastQuota ?: return null
        if (!q.warn) return null
        val d = istDay(System.currentTimeMillis())
        if (warnedDay == d) return null
        warnedDay = d
        return BackendWire.WARN_HI
    }

    internal fun istDay(ms: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata") }.format(java.util.Date(ms))

    /** आज का मुफ़्त समय/संदेश पूरा - बातचीत (Live) रोक दो */
    fun stopsTalk(e: Throwable?): Boolean = (e as? BackendException)?.stopsTalk == true
        || (e?.cause as? BackendException)?.stopsTalk == true

    /**
     * आज की मुफ़्त बातचीत पूरी: DK तय शब्द बोले/लिखे और Live रुके. [onStop] को DK की सेवा भरती है
     * (say, बोलना है?). एक बार ही (बार-बार "पूरी हो गई" न दोहराए) - नया दिन/लाइसेंस = फिर से.
     */
    @Volatile var onStop: ((String, Boolean) -> Unit)? = null
    @Volatile private var stoppedAt = 0L
    fun stopped(e: Throwable, speak: Boolean) {
        val say = say(e) ?: BackendWire.VOICE_DONE
        val now = System.currentTimeMillis()
        val again = now - stoppedAt < 30_000
        stoppedAt = now
        DKState.replyFailed(say)
        try { onStop?.invoke(say, speak && !again) } catch (x: Exception) { Quiet.note(x) }
    }

    /** DK यह बोले/लिखे (लाइसेंस वाली गलती में मालिक के तय शब्द) */
    fun say(e: Throwable): String? = (e as? BackendException)?.say?.takeIf { it.isNotBlank() }

    /** खोज: अपनी Tavily/Exa/Gemini keys न चलीं - backend की खोज (स्रोत उसी पुराने रूप में) */
    fun search(c: Context, q: String, deep: Boolean): Pair<String, String> = BackendWire.searchRaw(BackendApi.search(c, q, deep))

    /** आवाज़ -> लिखा (backend का Whisper). टिकट याद */
    fun stt(c: Context, wav: ByteArray, lang: String): String {
        val r = BackendApi.stt(c, wav, lang)
        noteTicket(r.ticket)
        return r.text
    }
}

/** backend से AI ([AiRouter.client]) - पुराने [AiClient] जैसा, ताकि [BrainManager] / [CoderAi] बिना बदलाव इस्तेमाल करें */
class BackendAiClient(private val c: Context, private val task: String) : AiClient {
    override val label: String = AiRouter.LABEL

    private fun body(system: String, history: List<Turn>, prompt: String): org.json.JSONObject {
        val ticket = if (task == AiRouter.Task.CHAT || task == AiRouter.Task.VOICE || task == AiRouter.Task.LIVE) AiRouter.takeTicket() else null
        val mode = if (ticket != null) "voice" else "chat"
        return BackendWire.chatBody(system, history.map { it.role to it.text }, prompt, task, mode, ticket)
    }

    override fun complete(system: String, history: List<Turn>, prompt: String, imageB64: String?): String =
        BackendApi.chat(c, body(system, history, prompt))

    override fun stream(system: String, history: List<Turn>, prompt: String, onDelta: (String) -> Unit): String =
        BackendApi.chatStream(c, body(system, history, prompt), onDelta)
}
