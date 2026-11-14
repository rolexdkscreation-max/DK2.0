package com.dk.assistant.dkai

import com.dk.assistant.DKAccessibilityService
import java.lang.ref.WeakReference

/**
 * 14 नवंबर 2026: DK लाइव <-> DKAI स्क्रीन का जोड़. DKAI की आवाज़ वाली स्क्रीन खुली हो तो [mode] = उसका चुना टैब
 * ([DkaiScreen.liveMode]: Chat / Builder / Agent / Pipeline / Broadcast) - DK लाइव का काम उसी टैब में जाता है ([DkaiCore.routeFor]).
 */
object DkaiLive {
    @Volatile private var ref: WeakReference<DkaiScreen>? = null
    fun attach(s: DkaiScreen) { ref = WeakReference(s) }
    fun detach(s: DkaiScreen) { if (ref?.get() === s) ref = null }
    fun screen(): DkaiScreen? = ref?.get()?.takeIf { !it.activity.isFinishing && !it.activity.isDestroyed }

    /** DKAI आवाज़ सत्र चालू हो तो चुना टैब; नहीं तो null (पुराना रास्ता). UI thread से पढ़ें */
    fun liveMode(): DkaiMode? = screen()?.liveMode()

    /** DK लाइव को चुपचाप बताओ (जुड़ा हो तो) */
    fun note(t: String) { try { DKAccessibilityService.instance?.gemLive?.pushNote(t) } catch (_: Exception) {} }
}
