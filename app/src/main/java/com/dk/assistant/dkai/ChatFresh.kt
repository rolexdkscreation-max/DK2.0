package com.dk.assistant.dkai

import android.content.Context
import android.content.Intent
import com.dk.assistant.ChatGreet
import com.dk.assistant.DKState
import com.dk.assistant.LiveCarry
import com.dk.assistant.LiveHandleStore
import com.dk.assistant.Mode
import com.dk.assistant.NameRule
import com.dk.assistant.Prefs
import com.dk.assistant.Quiet

/**
 * 19 नवंबर 2026 (मालिक): चैट बदलने के Android वाले काम ([ChatGreet] का pure हिस्सा अलग).
 * - [onLaunch]: ऐप हटाकर फिर खोला = अभी वाली चैट "सभी चैट" सूची में सेव, नई खाली चैट
 * - [changed]: नई / पुरानी चैट = Live की save बातचीत (handle) मिटे, अगली बार जुड़ते ही एक बार अभिवादन
 * - [note]: Live जुड़ते ही अभिवादन (नई चैट = सिर्फ़ नमस्ते, पुरानी = उसी के हिसाब से)
 */
object ChatFresh {
    /** MainActivity.onCreate (DKAI होम बनने से पहले). true = नई चैट शुरू की */
    fun onLaunch(c: Context, restored: Boolean, intent: Intent?): Boolean = try {
        val busy = DKState.mode == Mode.THINKING || DKState.mode == Mode.WORKING || DKState.mode == Mode.SPEAKING
        val fresh = ChatGreet.freshOnLaunch(restored, intent?.action == Intent.ACTION_MAIN,
            intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true,
            ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0,
            DKState.messages.isNotEmpty(), busy)
        if (fresh) start(c, "ऐप हटाकर फिर खोला")
        else if (!restored && DKState.messages.isEmpty()) changed(c, Dkai.chatNo(c), reopened = false)   // पहले से खाली चैट: फिर भी सिर्फ़ नमस्ते
        fresh
    } catch (e: Exception) { Quiet.note(e); false }

    /** अभी वाली चैट सेव (पीछे के धागे पर), नई खाली चैट - नंबर सबसे बड़े + 1 */
    fun start(c: Context, why: String) {
        val app = c.applicationContext
        val cur = Dkai.chatNo(app)
        val msgs = DKState.messages.toList()
        val dir = DkaiChats.dir(app)
        DKState.clearChat()
        Dkai.nextChat(app)
        if (msgs.isNotEmpty()) Thread { DkaiChats.save(dir, cur, msgs) }.start()
        changed(app, Dkai.chatNo(app), reopened = false)
        com.dk.assistant.DKLog.d("नई चैट ($why): चैट $cur सूची में, अब चैट ${Dkai.chatNo(app)}")
    }

    /** चैट बदली: Live की फ़ोन में save बातचीत मिटाओ (नई चैट में पुरानी न चले), अगली बार जुड़ते ही एक बार अभिवादन */
    fun changed(c: Context, chat: Int, reopened: Boolean) {
        try { c.getSharedPreferences("dk_live_race", Context.MODE_PRIVATE).edit().remove(LiveHandleStore.PREF).apply() } catch (e: Exception) { Quiet.note(e) }
        ChatGreet.reopenedChat = if (reopened) chat else 0
        ChatGreet.arm(chat, System.currentTimeMillis())
    }

    fun address(c: Context): String = ChatGreet.address(try { NameRule.personaCall() } catch (_: Exception) { "" }, Prefs.userName(c))

    /** Live जुड़ा: इस चैट का अभिवादन बाकी हो तो उसका SYSTEM NOTE (null = कुछ नहीं बोलना) */
    fun note(c: Context): String? {
        if (!ChatGreet.take(Dkai.chatNo(c), System.currentTimeMillis())) return null
        return greetFor(c)
    }

    /** अभी की चैट के हिसाब से: खाली = सिर्फ़ नमस्ते, बातें हों = उन्हीं के हिसाब से */
    fun greetFor(c: Context): String {
        val turns = DKState.messages.map { LiveCarry.Turn(it.role, it.text, it.time) }
        return if (turns.none { it.text.isNotBlank() && (it.role == "u" || it.role == "d") }) ChatGreet.newNote(address(c))
        else ChatGreet.oldNote(address(c), turns)
    }
}
