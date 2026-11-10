package com.dk.assistant

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// SettingsActivity का हिस्सा: कोडिंग एजेंट और DK कंप्यूटर.
// यह code पहले SettingsActivity.kt में था; सिर्फ़ जगह बदली है, व्यवहार वही (extension functions, वही state SettingsActivity में)।

internal fun SettingsActivity.secComputer() {
    card("DK कंप्यूटर", "कोई भी काम कदम-दर-कदम: इंटरनेट पर खोज, वेबसाइट पढ़ना, हिसाब (कोड), फ़ाइल/PDF, फ़ोन के काम, कोडिंग, रिमाइंडर") { box ->
        swPc = switch(box, "DK कंप्यूटर चालू करें", Prefs.computerOn(this))
        swPcConfirm = switch(box, "मैसेज, कॉल, पेमेंट, डिलीट जैसे फ़ोन के काम से पहले मुझसे पूछो", Prefs.computerConfirm(this))
        edPcSteps = field(box, "एक काम में ज़्यादा से ज़्यादा कदम (5–60)", Prefs.computerMaxSteps(this).toString(), "40")
        edPcSteps.inputType = InputType.TYPE_CLASS_NUMBER
        row(box, button("कंप्यूटर खोलें") {
            save(false)
            if (!Prefs.computerOn(this)) { toast("पहले \"DK कंप्यूटर चालू करें\" ON करें"); return@button }
            startActivity(android.content.Intent(this, ComputerActivity::class.java))
        })
    }
    apiLinkCard(ApiScopes.PC, "brain_pc")
    card("वेब search", "बैकअप क्रम: Gemini (Google) → Tavily → OpenRouter → Exa — जिसकी key डली है और जो चल जाए, उसी से खोज") { box ->
        val cur = Prefs.computerSearch(this)
        val names = mapOf("auto" to "अपने-आप (Gemini → Tavily → OpenRouter → Exa, जो key हो)", "tavily" to "Tavily", "exa" to "Exa Search", "gemini" to "Google (Gemini key से)", "openrouter" to "OpenRouter web खोज (OpenRouter key से)")
        rgPcSearch = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        pcSearchIds.forEachIndexed { i, id ->
            rgPcSearch.addView(RadioButton(this).apply {
                this.id = 5100 + i; text = names[id]; setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
                isChecked = cur == id; setPadding((6 * dp).toInt(), (6 * dp).toInt(), 0, (6 * dp).toInt())
            })
        }
        box.addView(rgPcSearch)
        edTavily = field(box, "Tavily API Key", Prefs.tavilyKey(this), "tvly-…", secret = true)
        row(box, button("Tavily key बनाएँ") { Setup.openUrl(this, "https://app.tavily.com/home") })
        edExa = field(box, "Exa API Key", Prefs.exaSearchKey(this), "Exa key", secret = true)
        row(box, button("Exa key बनाएँ") { Setup.openUrl(this, WebSearch.EXA_KEY_URL) })
        hint(box, "Keys सिर्फ़ इसी फ़ोन में रहती हैं। एक search fail हो तो DK अपने-आप अगला आज़माता है।")
    }
    card("कंप्यूटर के टूल", "जो बंद करेंगे, वह DK कंप्यूटर इस्तेमाल नहीं करेगा") { box ->
        val names = mapOf("web_search" to "इंटरनेट पर खोज", "read_url" to "वेबसाइट पढ़ना", "run_js" to "कोड चलाना (हिसाब/डेटा)",
            "write_file" to "फ़ाइल बनाना", "make_pdf" to "PDF बनाना", "read_file" to "अपनी फ़ाइलें पढ़ना", "list_files" to "फ़ाइलों की लिस्ट",
            "phone_task" to "फ़ोन कंट्रोल (ऐप, मैसेज, कॉल)", "coding_task" to "कोडिंग टीम को काम देना", "remember" to "बातें याद रखना",
            "schedule" to "बाद में काम / रिमाइंडर (रोज़ / हर हफ़्ते भी)",
            "browse" to "असली ब्राउज़र (बटन दबाना, खोज-बॉक्स, स्क्रीनशॉट)", "look" to "फ़ोटो / PDF / स्क्रीनशॉट देखना",
            "make_docx" to "Word फ़ाइल बनाना", "make_xlsx" to "Excel फ़ाइल बनाना", "make_chart" to "चार्ट / ग्राफ़ बनाना",
            "sub_agents" to "कई सहायक एजेंट एक साथ (बड़ी रिसर्च)", "past_tasks" to "पिछले काम याद रखना")
        pcTools.clear()
        ComputerAgent.TOOL_IDS.forEach { id -> pcTools[id] = switch(box, names[id] ?: id, Prefs.computerTool(this, id)) }
    }
    card("तय किए काम", "रिमाइंडर और बाद में चलने वाले काम — फ़ोन restart होने पर भी याद रहते हैं") { box ->
        val jobs = ComputerJobs.all(this)
        val fmt = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale("hi", "IN"))
        hint(box, if (jobs.isEmpty()) "अभी कोई काम तय नहीं है। बोलिए: \"डीके कंप्यूटर, 30 मिनट बाद मुझे पानी पीने की याद दिलाना\""
            else jobs.take(10).joinToString("\n") { "⏰ " + fmt.format(java.util.Date(it.at)) + " — " + it.task.take(70) } +
                if (jobs.size > 10) "\n…और ${jobs.size - 10}" else "")
        if (jobs.isNotEmpty()) row(box, button("सारे तय काम रद्द करें") {
            val n = ComputerJobs.cancelAll(this); toast("$n काम रद्द किए"); recreate()
        })
        hint(box, "कुछ फ़ोन (Xiaomi, Oppo, Vivo, Realme) में Settings → Apps → DK → Autostart चालू करना पड़ता है, तभी restart के बाद काम चलते हैं।")
    }
}

internal fun SettingsActivity.secCoder() {
    card("कोडिंग एजेंट", "आपके GitHub repo में कोड लिखता, बदलता, हटाता और push करता है") { box ->
        swCoder = switch(box, "कोडिंग एजेंट चालू करें", Prefs.coderOn(this))
        hint(box, "बंद रहने पर यह कुछ नहीं करता। चालू करके नीचे GitHub और AI भरें, फिर \"एजेंट खोलें\" दबाएँ।")
        swTeam = switch(box, "मल्टी-एजेंट टीम: बिल्डर → रिव्यूअर → कोऑर्डिनेटर (एक बार बोलो, बाकी सब अपने-आप)", Prefs.coderTeam(this))
        hint(box, "चालू: बिल्डर कोड लिखता है → रिव्यूअर खुद जाँचता है (बग/गलती/लॉजिक, ब्रैकेट/XML) → गड़बड़ी हो तो बिल्डर को वापस → सही होने पर अपने-आप push → GitHub build देखकर fail हो तो फिर ठीक → पूरा होने पर बताता है। " +
            "कोई अतिरिक्त चीज़ ज़रूरी लगे तो टीम खुद नहीं जोड़ती — आपसे पूछती है (हाँ/नहीं)। आवाज़ से: \"कोडिंग एजेंट, … बनाओ\"। बंद: पुराना तरीका (एक एजेंट, आप push दबाएँ)।")
        swSeven = switch(box, "7 एजेंट वाला सिस्टम: मैनेजर (समझ + आपकी हाँ) → प्लानर (पहले जड़/कारण) → कोडर → रिव्यूअर → टेस्टर → डिबगर → गिट/रिलीज़ (+ 🎓 समझाने वाला)", Prefs.coderSeven(this))
        hint(box, "चालू: GitHub token + चुना रिपो = GitHub मोड (backup, नई branch, PR, मंज़ूरी से merge); token/रिपो नहीं = नॉर्मल मोड (कोड फ़ाइल-नाम के साथ स्क्रीन पर)। " +
            "हर बदलाव FIND/REPLACE में, लागू होने से पहले रिव्यूअर जाँचता है। बंद: पुराना एजेंट/टीम जैसी की तैसी।")
        row(box, button("एजेंट खोलें") {
            save(false)
            if (!Prefs.coderOn(this)) { toast("पहले \"कोडिंग एजेंट चालू करें\" ON करें"); return@button }
            startActivity(android.content.Intent(this, CodingActivity::class.java))
        })
    }
    card("GitHub कनेक्टर", "Personal Access Token से जुड़ता है (फ़ोन में ही रहता है)") { box ->
        edGhToken = field(box, "GitHub Token", Prefs.ghToken(this), "github_pat_… या ghp_…", secret = true)
        edGhRepo = field(box, "Repo (owner/naam)", Prefs.ghRepo(this), "जैसे: username/my-repo")
        edGhBranch = field(box, "Branch", Prefs.ghBranch(this), "main")
        val res = TextView(this).apply { setTextColor(0xB3FFFFFF.toInt()); textSize = 13f; setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
        row(box,
            button("जाँचें") {
                save(false)
                val gh = GitHub.fromPrefs(this) ?: run { res.text = "पहले token डालें"; return@button }
                res.text = "जाँच रहा हूँ…"
                lifecycleScope.launch {
                    res.text = try {
                        val (who, br) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val w = gh.me()
                            val repo = Prefs.ghRepo(this@secCoder)
                            w to (if (repo.contains('/')) gh.defaultBranch(repo) else "")
                        }
                        "✓ जुड़ गया: $who" + if (br.isNotBlank()) " • repo मिला (मुख्य branch: $br)" else " • अब repo चुनें"
                    } catch (e: Exception) { "✗ " + (e.message ?: "गलती").take(160) }
                }
            },
            button("रिपो चुनें") {
                save(false)
                val gh = GitHub.fromPrefs(this) ?: run { res.text = "पहले token डालें"; return@button }
                res.text = "Repos ला रहा हूँ…"
                lifecycleScope.launch {
                    try {
                        val repos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { gh.repos() }
                        res.text = "${repos.size} repo मिले"
                        if (isFinishing || isDestroyed || repos.isEmpty()) return@launch
                        UiDialog(this@secCoder, Glass.DIALOG)
                            .setTitle("Repo चुनें")
                            .setItems(repos.map { it.fullName + if (it.private) "  (private)" else "" }.toTypedArray()) { _, i ->
                                edGhRepo.setText(repos[i].fullName); edGhBranch.setText(repos[i].branch); save(false)
                            }
                            .setNegativeButton("बंद करें", null).show()
                    } catch (e: Exception) { res.text = "✗ " + (e.message ?: "गलती").take(160) }
                }
            },
            button("Token बनाएँ") { Setup.openUrl(this, GitHub.TOKEN_URL) })
        box.addView(res)
        hint(box, "Token बनाते समय: Repository access → अपना repo चुनें • Permissions → Contents: Read and write, Pull requests: Read and write (सुरक्षित PR मोड), और Actions: Read (build/tests जाँचने के लिए)।")
        swAutoPush = switch(box, "बिना पूछे push करो (बंद = पहले बदलाव दिखाओ, फिर \"Push करें\" दबाने पर)", Prefs.coderAutoPush(this))
        // v3.6: हर एजेंट का अपना मॉडल - कोडर/डिबगर/प्लानर/रिव्यूअर = कोडिंग एजेंट का मॉडल (3.8 Flash), बाकी हल्का
        edCoderLight = field(box, "हल्के एजेंटों का मॉडल (मैनेजर, गिट/रिलीज़, टेस्टर)",
            Prefs.coderLightModel(this), GeminiModels.LIGHT + " (सुझाया — जल्दी जवाब, धीमे नेटवर्क पर भी)")
        hint(box, "कोडर, डिबगर, प्लानर और रिव्यूअर AI दिमाग → कोडिंग एजेंट में चुना मॉडल लेते हैं (सुझाया: ${GeminiModels.CODER})। 5 Gemini keys हों तो limit, धीमा नेटवर्क या server गलती पर अगली key अपने-आप।")
        strongModelBox(box)
        edCoderSteps = field(box, "एक काम में ज़्यादा से ज़्यादा कदम (3–30)", Prefs.coderMaxSteps(this).toString(),
            "5 (सुझाया) — कम कदम = कम API खर्च, limit/413 कम").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        swBuildFix = switch(box, "Push के बाद tests + build जाँचो, fail हो तो पहले जड़ ढूँढकर खुद ठीक करो (पास होने तक)", Prefs.coderBuildFix(this))
        hint(box, "🔒 सुरक्षित PR मोड हमेशा चालू: हर काम पहले main से नई feature/dk-… branch में, फिर Pull Request; सारे पुराने + नए unit tests और build पास होने के बाद " +
            "आपकी मंज़ूरी (\"Merge करें\" / \"PR merge कर दो\") से ही main में जाता है। पुराना फ़ीचर बदलने/हटाने से पहले backup/dk-… branch बनती है। " +
            "Token में Pull requests: Read and write दें; Actions: Read and write देंगे तो GitHub की नेटवर्क दिक्कत पर build अपने-आप दोबारा चलेगा।")
    }
    apiLinkCard(ApiScopes.CODER, "brain_coder")
}

/** रेडियो id की शुरुआत (मज़बूत मॉडल की कंपनी) */
internal const val STRONG_RADIO = 7700

/**
 * नवंबर 2026 (मालिक): "कोडर, डिबगर और प्लानर के लिए ताक़तवर मॉडल" - अपनी paid key + मॉडल ([StrongAi]).
 * Key खाली = बंद (सब पहले जैसा). न चले = अपने-आप कोडिंग एजेंट की पुरानी API पर.
 */
internal fun SettingsActivity.strongModelBox(box: LinearLayout) {
    label(box, "💪 मज़बूत मॉडल — कोडर, डिबगर, प्लानर")
    hint(box, "कोड लिखना, गलती की जड़ ढूँढना और प्लान बनाना सबसे कठिन काम हैं — इनके लिए अपनी paid key डालें। " +
        "बाकी एजेंट ऊपर वाले मॉडल पर ही रहेंगे (खर्च कम)। Key खाली = बंद।")
    val cur = StrongAi.choice(Prefs.coderStrongProvider(this))
    rgStrong = radios(box, StrongAi.CHOICES.map { it.id to it.label }, cur.id, STRONG_RADIO)
    edStrongKey = field(box, "मज़बूत मॉडल की API key", Prefs.coderStrongKey(this), "sk-ant-… / sk-… / AIza… / sk-or-…", secret = true)
    row(box, showButton(edStrongKey), button("Key बनाएँ") {
        val ch = StrongAi.CHOICES.getOrElse(rgStrong.checkedRadioButtonId - STRONG_RADIO) { StrongAi.CHOICES.first() }
        Setup.openUrl(this, ch.provider.keyUrl)
    })
    edStrongModel = field(box, "मॉडल का नाम", Prefs.coderStrongModel(this).ifBlank { cur.model }, cur.model)
    // कंपनी बदली और मॉडल उसी पुरानी कंपनी का सुझाया था = नई कंपनी का सुझाया भरो
    rgStrong.setOnCheckedChangeListener { _, id ->
        val ch = StrongAi.CHOICES.getOrNull(id - STRONG_RADIO) ?: return@setOnCheckedChangeListener
        val now = edStrongModel.text.toString().trim()
        if (now.isBlank() || StrongAi.CHOICES.any { it.model == now }) edStrongModel.setText(ch.model)
        edStrongModel.hint = ch.model
    }
    hint(box, "अंदाज़न खर्च (1 काम ≈ 2–6 लाख token): DeepSeek सबसे सस्ता, OpenAI/Gemini बीच में, Claude Opus सबसे मज़बूत पर महँगा। " +
        "Key या पैसे खत्म, limit या server गलती पर उसी काम में अपने-आप पुरानी (फ्री) API — काम रुकता नहीं।")
}

