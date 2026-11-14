package com.dk.assistant.dkai

import android.content.Context
import com.dk.assistant.BrainManager
import com.dk.assistant.CoderAi
import com.dk.assistant.DKLog
import com.dk.assistant.Prefs
import com.dk.assistant.StoryAi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 14 नवंबर 2026 (मालिक): "होम पर जो मॉडल चुनूँ, वही काम करे - चैट हो, ऐप बनाना (Builder / Pipeline) हो या ब्रॉडकास्ट.
 * और उसी AI दिमाग वाली key से. कौन-सा मॉडल काम कर रहा है वो बीच में सामने न आए, बस तेज़ काम हो."
 *
 * वजह (पहले): चुनने पर सिर्फ़ चैट की कंपनी बदलती थी, पर
 *  - चैट भी सबसे तेज़ कंपनी को पहले लेता था (चुनी हुई को सिर्फ़ 150ms की छूट) - दूसरा मॉडल जवाब दे देता था;
 *  - Pipeline / Copilot पहले "कोडिंग एजेंट" की अलग key/मॉडल लेते थे, Broadcast "कहानी" वाली - चुना मॉडल लगता ही नहीं था;
 *  - गलती होने पर "Coder AI: ..." जैसा संदेश सामने आ जाता था.
 *
 * अब: [choose] = चुना (एक निशान के साथ). [ask] = चुना मॉडल पहले (उसी की AI दिमाग वाली key), न चले तो चुपचाप
 * पुराना रास्ता (कोडिंग एजेंट -> कहानी -> कोई भी चालू key). [activeFirst] = चैट में चुनी कंपनी सबसे पहले.
 */
object DkaiPick {

    /** होम के मॉडल शीट से मॉडल चुना गया (तब चुना मॉडल हर काम में पहले) */
    const val PREF = "dkai_model_picked"

    fun choose(c: Context, provider: String, model: String) = Prefs.edit(c) {
        putString("active_provider", provider); putString("model_$provider", model); putBoolean(PREF, true)
    }

    fun on(c: Context) = Prefs.bool(c, PREF, false)

    /** चुनी कंपनी ([active]) सबसे पहले, बाकी वैसे ही (तेज़ वाली पहले) - Pure, JUnit से जाँच */
    fun <T> activeFirst(list: List<T>, active: String, id: (T) -> String): List<T> {
        val (a, rest) = list.partition { id(it) == active }
        return a + rest
    }

    /** मॉडल/कंपनी का नाम सामने नहीं - गलती सिर्फ़ log में */
    private fun quiet(where: String, e: Exception) = DKLog.d("$where: ${e.message?.take(100)}")

    /**
     * Pipeline / Copilot / Broadcast का एक सवाल. [preferStory] = लंबा लिखना (Broadcast) - चुना न हो तो कहानी वाली AI पहले.
     * null = कहीं से जवाब नहीं मिला.
     */
    suspend fun ask(c: Context, system: String, prompt: String, preferStory: Boolean = false): String? {
        val app = c.applicationContext
        val brain = BrainManager(app)
        if (on(app)) {
            try { brain.quickText(system, prompt)?.takeIf { it.isNotBlank() }?.let { return it } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { quiet("चुना मॉडल", e) }
        }
        return withContext(Dispatchers.IO) {
            val coder: () -> String? = { try { CoderAi.fromPrefs(app)?.chat(system, listOf("user" to prompt))?.takeIf { it.isNotBlank() } } catch (e: Exception) { quiet("कोडिंग AI", e); null } }
            val story: () -> String? = { try { StoryAi.fromPrefs(app)?.write(system, listOf("user" to prompt))?.text?.takeIf { it.isNotBlank() } } catch (e: Exception) { quiet("कहानी AI", e); null } }
            if (preferStory) story() ?: coder() else coder() ?: story()
        } ?: if (on(app)) null else brain.quickText(system, prompt)
    }
}
