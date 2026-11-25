package com.dk.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.dk.assistant.agent.master.BrowserRules
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/**
 * v5 मालिक: DK कंप्यूटर का अपना ब्राउज़र (जैसे Perplexity Computer चलाता है) - फ़ोन के अंदर छिपा WebView.
 * read_url सिर्फ़ HTML पढ़ता है; यह JavaScript वाली साइट भी चलाता है, बटन दबाता है, खोज-बॉक्स में लिखता है,
 * स्क्रॉल करता है, और स्क्रीनशॉट लेकर look टूल से "देख" भी सकता है. एक काम में पेज वहीं खुला रहता है (कई कदम).
 *
 * सुरक्षा: पासवर्ड / कार्ड / OTP वाले खाने नहीं भरता, कोई खरीद या पेमेंट नहीं.
 */
object ComputerBrowser {
    const val W = 1080
    const val H = 2200
    const val LOAD_MS = 25_000L

    @SuppressLint("StaticFieldLeak") private var web: WebView? = null
    @Volatile private var loaded: CompletableDeferred<Unit>? = null

    /**
     * 25 नवंबर 2026 (मालिक: "मैं बोलूँ दिखाओ तो वो पेज खोल के मुझे दिखाए"): काम खत्म होते ही ब्राउज़र बंद नहीं -
     * [PARK_MS] तक वही पेज (login / cart / बुकिंग / भुगतान वाला) ज़िंदा, ताकि [BrowserShowActivity] उसे सामने ला सके.
     */
    const val PARK_MS = 30 * 60 * 1000L
    /** मालिक अभी यह पेज स्क्रीन पर देख रहा है ([BrowserShowActivity]) */
    @Volatile var shown = false
        private set
    @Volatile var lastUrl = ""
        private set
    @Volatile var lastTitle = ""
        private set
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val killer = Runnable { if (!shown) destroyNow() }

    // ------------------------------------------------------------ pure (tests)
    /** पेज पर जो दिखता है: शीर्षक, URL, text, और दबाने/लिखने लायक चीज़ें [n] नंबर के साथ */
    const val SNAPSHOT_JS = """(function(){
var out={title:document.title,url:location.href,els:[]};
var sel='a[href],button,input,select,textarea,[role=button],[role=link],[role=tab],[role=menuitem],[onclick],summary,label[for]';
var n=0;document.querySelectorAll('[data-dk-i]').forEach(function(e){e.removeAttribute('data-dk-i')});
document.querySelectorAll(sel).forEach(function(e){
 if(n>=120)return;var r=e.getBoundingClientRect();var st=getComputedStyle(e);
 if(r.width<2||r.height<2||st.visibility=='hidden'||st.display=='none')return;
 if(e.type=='hidden')return;
 var t=(e.innerText||e.value||e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.getAttribute('title')||e.getAttribute('alt')||e.name||'').replace(/\s+/g,' ').trim().slice(0,80);
 var tag=e.tagName.toLowerCase();var kind=tag=='input'?('input:'+(e.type||'text')):tag;
 if(!t&&tag=='a')t=(e.getAttribute('href')||'').slice(0,60);
 n++;e.setAttribute('data-dk-i',n);out.els.push([n,kind,t,tag=='a'?(e.href||'').slice(0,160):'']);
});
var b=document.body?document.body.innerText:'';out.text=b.replace(/\n{3,}/g,'\n\n').slice(0,60000);
out.scroll=[Math.round(scrollY),Math.round(document.documentElement.scrollHeight)];
return JSON.stringify(out);})()"""

    private fun q(s: String) = JSONObject.quote(s)

    /** [n] नंबर या बटन का नाम -> उस element को ढूँढने वाला JavaScript */
    private fun el(v: Any?) = when (v) {
        is Number -> "document.querySelector('[data-dk-i=\"${v.toInt()}\"]')"
        is String -> if (v.toIntOrNull() != null) "document.querySelector('[data-dk-i=\"${v.toInt()}\"]')"
            else "(function(t){t=t.toLowerCase();var c=[].slice.call(document.querySelectorAll('[data-dk-i],a,button,[role=button],input[type=submit]'));" +
                "return c.find(function(e){return ((e.innerText||e.value||e.getAttribute('aria-label')||'')+'').trim().toLowerCase()==t})||" +
                "c.find(function(e){return ((e.innerText||e.value||e.getAttribute('aria-label')||'')+'').toLowerCase().indexOf(t)>=0})})(${q(v)})"
        else -> "null"
    }

    /**
     * 25 नवंबर 2026 (मालिक: ब्राउज़र एजेंट): दबाने से पहले बटन का नाम पढ़ो (दबाओ नहीं) - [BrowserRules.riskyClick] के लिए.
     * Enter = उस फ़ॉर्म का जमा करने वाला बटन. null = click / enter नहीं.
     */
    fun labelJs(a: JSONObject): String? = when {
        a.has("click") -> "(function(){var e=" + el(a.opt("click")) + ";if(!e)return '';return ((e.innerText||e.value||e.getAttribute('aria-label')||e.getAttribute('title')||'')+'').replace(/\\s+/g,' ').trim().slice(0,80)})()"
        a.has("enter") -> "(function(){var e=" + el(a.opt("enter")) + ";if(!e)e=document.activeElement;if(!e||!e.form)return '';" +
            "var b=e.form.querySelector('button[type=submit],input[type=submit],button:not([type])');return b?((b.innerText||b.value||'')+'').replace(/\\s+/g,' ').trim().slice(0,80):''})()"
        else -> null
    }

    /** मास्टर चालू हो तो ब्राउज़र के ऊपर पहरा: खतरे वाला बटन = [ask] से पूछो, हर कदम [log] में */
    class Guard(val ask: suspend (String) -> Boolean, val log: (String) -> Unit)

    /** एक action -> JavaScript. null = अनजाना action */
    fun actionJs(a: JSONObject): String? {
        val pre = "(function(){var e="
        return when {
            a.has("click") -> pre + el(a.opt("click")) + ";if(!e)return 'ERROR: element नहीं मिला';e.scrollIntoView({block:'center'});e.click();return 'clicked: '+((e.innerText||e.value||'')+'').trim().slice(0,60)})()"
            a.has("type") -> {
                val arr = a.optJSONArray("type") ?: return null
                val text = arr.optString(1)
                pre + el(arr.opt(0)) + ";if(!e)return 'ERROR: खाना नहीं मिला';" +
                    "var l=((e.getAttribute('aria-label')||'')+' '+(e.getAttribute('placeholder')||'')+' '+(e.name||'')+' '+(e.id||'')+' '+(e.getAttribute('autocomplete')||'')).toLowerCase();" +
                    "if(e.type=='password'||/password|otp|cvv|cvc|card|pin|cc-/.test(l))return 'ERROR: पासवर्ड/कार्ड/OTP वाला खाना DK नहीं भरता - user से खुद भरने को कहें';" +
                    "e.focus();var p=Object.getOwnPropertyDescriptor(e.tagName=='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,'value');" +
                    "if(p&&p.set&&e.tagName!='SELECT')p.set.call(e,${q(text)});else e.value=${q(text)};" +
                    "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'typed'})()"
            }
            a.has("enter") -> pre + el(a.opt("enter")) + ";if(!e)e=document.activeElement;if(!e)return 'ERROR: खाना नहीं मिला';" +
                "['keydown','keypress','keyup'].forEach(function(k){e.dispatchEvent(new KeyboardEvent(k,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}))});" +
                "if(e.form){if(e.form.requestSubmit)e.form.requestSubmit();else e.form.submit()}return 'enter'})()"
            a.has("select") -> {
                val arr = a.optJSONArray("select") ?: return null
                pre + el(arr.opt(0)) + ";if(!e||e.tagName!='SELECT')return 'ERROR: select नहीं मिला';var v=${q(arr.optString(1))}.toLowerCase();" +
                    "var o=[].slice.call(e.options).find(function(o){return o.value.toLowerCase()==v||o.text.trim().toLowerCase()==v||o.text.toLowerCase().indexOf(v)>=0});" +
                    "if(!o)return 'ERROR: विकल्प नहीं मिला';e.value=o.value;e.dispatchEvent(new Event('change',{bubbles:true}));return 'selected: '+o.text})()"
            }
            a.has("scroll") -> when (a.optString("scroll")) {
                "up" -> "(function(){scrollBy(0,-innerHeight*0.9);return 'scrolled up'})()"
                "top" -> "(function(){scrollTo(0,0);return 'top'})()"
                "bottom" -> "(function(){scrollTo(0,document.documentElement.scrollHeight);return 'bottom'})()"
                else -> "(function(){scrollBy(0,innerHeight*0.9);return 'scrolled down'})()"
            }
            a.has("js") -> "(function(){try{var r=(function(){${a.optString("js")}})();return r===undefined?'ok':String(r).slice(0,4000)}catch(x){return 'ERROR: '+x}})()"
            else -> null
        }
    }

    /** SNAPSHOT_JS का नतीजा -> AI के पढ़ने लायक */
    fun format(raw: String, from: Int = 0, max: Int = 9000): String {
        val o = try { JSONObject(raw) } catch (_: Exception) { return "ERROR: पेज पढ़ा नहीं जा सका" }
        val text = o.optString("text")
        val els = o.optJSONArray("els") ?: JSONArray()
        val sc = o.optJSONArray("scroll")
        return buildString {
            append("TITLE: ").append(o.optString("title")).append("\nURL: ").append(o.optString("url"))
            if (sc != null) append("\nSCROLL: ${sc.optInt(0)} / ${sc.optInt(1)} px")
            append("\n\nCLICKABLE / INPUTS (use the [number] in actions):\n")
            for (i in 0 until els.length()) {
                val e = els.optJSONArray(i) ?: continue
                append("[${e.optInt(0)}] ${e.optString(1)}: ${e.optString(2)}")
                e.optString(3).takeIf { it.isNotBlank() }?.let { append(" → ").append(it) }
                append('\n')
            }
            append("\nPAGE TEXT:\n")
            val part = text.drop(from.coerceAtLeast(0)).take(max)
            append(part)
            val left = text.length - from - part.length
            if (left > 0) append("\n…(बाकी $left अक्षर — browse {\"from\":${from + part.length}} से आगे पढ़ें)")
        }
    }

    // ------------------------------------------------------------ Android
    @SuppressLint("SetJavaScriptEnabled")
    private fun view(c: Context): WebView = (web ?: WebView(MutableContextWrapper(c.applicationContext)).also { w ->
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.loadWithOverviewMode = true
        w.settings.useWideViewPort = true
        w.settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"
        w.setBackgroundColor(Color.WHITE)
        w.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView?, url: String?) { loaded?.complete(Unit) }
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                val s = r?.url?.scheme.orEmpty()
                // मालिक खुद देख रहा है और खुद दबाया (जैसे UPI से भुगतान) = उसका ऐप खोलो; DK अकेले में कभी नहीं
                if (r != null && s != "http" && s != "https" && shown && r.hasGesture()) openOutside(v?.context, r.url.toString())
                return s != "http" && s != "https"      // intent:/upi:/tel: वगैरह नहीं खोलते
            }
        }
        w.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        w.layout(0, 0, W, H)
        web = w
    }).also { main.removeCallbacks(killer) }

    private fun openOutside(c: Context?, url: String) {
        val ctx = c ?: return
        try {
            val i = if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply { addCategory(Intent.CATEGORY_BROWSABLE) }
                else Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            i.component = null; i.selector = null       // वेब पेज किसी ख़ास (छिपे) हिस्से को सीधे न खोल पाए
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { Quiet.note(e) }
    }

    /**
     * [BrowserShowActivity] के लिए: वही चलता WebView (session / cookies / भरा फ़ॉर्म वैसे ही).
     * ब्राउज़र बंद हो चुका हो तो आख़िरी पता नए सिरे से खोलते हैं. null = दिखाने को कुछ नहीं.
     */
    fun showIn(a: android.app.Activity): WebView? {
        val fresh = web == null
        if (fresh && lastUrl.isBlank()) return null
        val w = view(a)
        (w.context as? MutableContextWrapper)?.baseContext = a
        (w.parent as? android.view.ViewGroup)?.removeView(w)
        w.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        shown = true
        if (fresh) w.loadUrl(lastUrl)
        return w
    }

    /** मालिक ने देखना बंद किया = फिर से छिपा (DK का चलता काम वहीं से आगे), [PARK_MS] बाद बंद */
    fun hide(w: WebView) {
        (w.parent as? android.view.ViewGroup)?.removeView(w)
        (w.context as? MutableContextWrapper)?.baseContext = w.context.applicationContext
        w.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        w.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        w.layout(0, 0, W, H)
        w.url?.let { lastUrl = it }; w.title?.let { lastTitle = it }
        shown = false
        main.removeCallbacks(killer); main.postDelayed(killer, PARK_MS)
    }

    private suspend fun js(w: WebView, code: String): String = suspendCancellableCoroutine { k ->
        w.evaluateJavascript(code) { r -> if (k.isActive) k.resume(r ?: "null") }
    }

    /** evaluateJavascript का नतीजा JSON-string में आता है ("\"abc\"") */
    fun unquote(r: String): String = try { if (r.startsWith("\"")) JSONArray("[$r]").getString(0) else r } catch (_: Exception) { r }

    private suspend fun waitLoad(w: WebView, go: () -> Unit) {
        val d = CompletableDeferred<Unit>(); loaded = d
        go()
        withTimeoutOrNull(LOAD_MS) { d.await() }
        delay(1500)     // JavaScript से बनने वाला हिस्सा
        repeat(6) { if (unquote(js(w, "document.readyState")) == "complete") return; delay(500) }
    }

    /**
     * @param url नया पेज (खाली = अभी वाला ही)
     * @param actions [{"click":3}, {"type":[5,"text"]}, {"enter":5}, {"select":[2,"Hindi"]}, {"scroll":"down"}, {"wait":2000}, {"back":true}]
     * @param shot स्क्रीनशॉट फ़ाइल का नाम (null = नहीं)
     */
    suspend fun browse(c: Context, url: String?, actions: JSONArray?, from: Int, max: Int, shot: String?, guard: Guard? = null): Pair<String, File?> = withContext(Dispatchers.Main) {
        val w = view(c)
        val log = StringBuilder()
        if (!url.isNullOrBlank()) {
            val u = url.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
            waitLoad(w) { w.loadUrl(u) }
            guard?.log?.invoke("खोला: ${u.take(120)}")
        } else if (w.url.isNullOrBlank()) return@withContext "ERROR: पहले url दें (कोई पेज खुला नहीं)" to null
        js(w, SNAPSHOT_JS)      // [n] नंबर लगाओ ताकि actions उन्हें पहचानें
        if (actions != null) acts@ for (i in 0 until minOf(actions.length(), 12)) {
            val a = actions.optJSONObject(i) ?: continue
            if (guard != null) {
                // 25 नवंबर 2026 (मालिक: "कोई भी एजेंट खुद से खरीदारी, भुगतान, बुकिंग ... नहीं करेगा") - दबाने से पहले नाम पढ़ो
                val lj = labelJs(a)
                if (lj != null) {
                    val label = unquote(js(w, lj))
                    val k = BrowserRules.riskyClick(label, w.url.orEmpty())
                    if (k == BrowserRules.Click.PAY) {
                        log.append(BrowserRules.payNote(label)).append('\n'); guard.log("रुका (भुगतान): $label"); break@acts
                    }
                    if (k != null && !guard.ask(BrowserRules.confirmText(k, label, w.url.orEmpty()))) {
                        log.append(BrowserRules.deniedNote(k, label)).append('\n'); guard.log("मना (${k.hi}): $label"); break@acts
                    }
                    if (k != null) guard.log("पुष्टि मिली (${k.hi}): $label")
                }
                if (a.has("js") && Regex("\\.click\\(|submit\\(|dispatchEvent").containsMatchIn(a.optString("js"))) {
                    log.append("ERROR: DK master: use {\"click\":n} / {\"enter\":n} instead of clicking with js\n"); continue@acts
                }
                guard.log(BrowserRules.logLine(a))
            }
            when {
                a.has("wait") -> { delay(a.optLong("wait", 1000).coerceIn(100, 10_000)); log.append("wait\n") }
                a.has("back") -> { if (w.canGoBack()) waitLoad(w) { w.goBack() }; log.append("back\n") }
                else -> {
                    val code = actionJs(a) ?: run { log.append("ERROR: अनजाना action ${a.toString().take(60)}\n"); null } ?: continue
                    val before = w.url
                    val r = unquote(js(w, code))
                    log.append(r.take(200)).append('\n')
                    if (a.has("click") || a.has("enter")) {
                        delay(1200)
                        if (w.url != before || unquote(js(w, "document.readyState")) != "complete") waitLoad(w) {}
                    } else delay(250)
                    js(w, SNAPSHOT_JS)
                }
            }
        }
        val snap = unquote(js(w, SNAPSHOT_JS))
        val guardNote = if (guard == null) "" else {
            val g = BrowserRules.parseGuard(unquote(js(w, BrowserRules.PAGE_GUARD_JS)))
            if (g.any) guard.log("पेज पर ${BrowserRules.pageHi(g)} - मालिक खुद करेंगे")
            BrowserRules.pageNote(g, w.url.orEmpty())
        }
        // "पेज दिखाओ" के लिए याद (कहाँ पहुँचा) - भुगतान वाला पेज अलग से निशान
        lastUrl = w.url.orEmpty(); lastTitle = w.title.orEmpty()
        ShowPage.dkWeb(lastUrl, lastTitle, pay = log.contains("PAYMENT step") || BrowserRules.isPaymentUrl(lastUrl))
        var file: File? = null
        if (!shot.isNullOrBlank()) file = try {
            val h = H.coerceAtMost(4000)
            val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp); cv.drawColor(Color.WHITE); cv.translate(0f, -w.scrollY.toFloat()); w.draw(cv)
            val f = File(ComputerTools.dir(c), ComputerTools.safeName(shot.removeSuffix(".png") + ".png"))
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            bmp.recycle(); f
        } catch (_: Exception) { null }
        val head = if (log.isNotEmpty()) "ACTIONS:\n$log\n" else ""
        (head + format(snap, from, max) + guardNote + (file?.let { "\n\nSCREENSHOT saved: ${it.name} (देखने के लिए look {\"file\":\"${it.name}\"})" } ?: "")) to file
    }

    /** HTML (जैसे SVG चार्ट) -> PNG: अलग छिपा WebView, ताकि खुला ब्राउज़र न बिगड़े */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun renderPng(c: Context, html: String, w: Int, h: Int, out: File): File? = withContext(Dispatchers.Main) {
        val v = WebView(c.applicationContext)
        try {
            v.setLayerType(View.LAYER_TYPE_SOFTWARE, null); v.setBackgroundColor(Color.WHITE)
            v.settings.useWideViewPort = false; v.settings.loadWithOverviewMode = false
            val d = CompletableDeferred<Unit>()
            v.webViewClient = object : WebViewClient() { override fun onPageFinished(x: WebView?, u: String?) { d.complete(Unit) } }
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            v.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            withTimeoutOrNull(8000) { d.await() }
            delay(400)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(bmp).also { it.drawColor(Color.WHITE); v.draw(it) }
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) }
            bmp.recycle(); out
        } catch (_: Exception) { null } finally { try { v.destroy() } catch (e: Exception) { Quiet.note(e) } }
    }

    /**
     * काम खत्म = ब्राउज़र अभी बंद नहीं: [PARK_MS] तक पेज ज़िंदा ("पेज दिखाओ" पर वही), फिर बंद (memory खाली).
     * मालिक देख रहा हो ([shown]) तो तब तक बिल्कुल नहीं.
     */
    fun close() {
        if (web == null) return
        main.removeCallbacks(killer)
        main.postDelayed(killer, PARK_MS)
    }

    private fun destroyNow() {
        val w = web ?: return
        web = null
        try { (w.parent as? android.view.ViewGroup)?.removeView(w); w.stopLoading(); w.destroy() } catch (e: Exception) { Quiet.note(e) }
    }
}
