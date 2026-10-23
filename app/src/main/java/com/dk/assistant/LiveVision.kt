package com.dk.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * v3.40: DK लाइव को दिखाना - स्क्रीन शेयर और कैमरा. दोनों हमेशा बंद से शुरू (बिना आपके कहे कोई तस्वीर नहीं जाती).
 *  • स्क्रीन: आवाज़ स्क्रीन का बटन, या बोलें "स्क्रीन देखो / शेयर करो" -> Android की इजाज़त (DK खुद Start दबाने की
 *    कोशिश करता है) -> हर ~1 सेकंड फ़ोन की स्क्रीन की तस्वीर Gemini Live को.
 *  • कैमरा: आवाज़ स्क्रीन का कैमरा बटन, या "कैमरा से देखो" -> वहीं छोटा preview, हर ~1 सेकंड तस्वीर Gemini को.
 *  DK लाइव बंद = दोनों अपने-आप बंद.
 */
object LiveVision {
    @Volatile var screenOn = false
        private set
    /** स्क्रीन शेयर की इजाज़त वाला popup खुला है */
    @Volatile var asking = false
        private set
    @Volatile var cameraOn = false
        private set
    /** कैमरा चाहिए (आवाज़ स्क्रीन खुलते ही चालू) */
    @Volatile var cameraWanted = false
        private set
    @Volatile var cameraFront = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { main.post { listeners.forEach { try { it() } catch (_: Exception) {} } } }

    private fun live(): GeminiLive? = DKAccessibilityService.instance?.gemLive
    private fun liveReady() = DKState.live && live()?.canTalk() == true
    private fun note(t: String) { live()?.visionNote(t) }

    /** तस्वीर Gemini को. false = अभी नहीं गई (Live नहीं जुड़ा / network भरा) */
    fun frame(jpeg: ByteArray): Boolean = live()?.sendFrame(jpeg) == true

    // ---------------------------------------------------------------- स्क्रीन
    fun setScreen(c: Context, on: Boolean): String {
        if (!on) {
            if (!screenOn && !asking) return "Screen sharing is already OFF."
            ScreenShareService.instance?.shutdown("बोलकर बंद") ?: run { asking = false; screenOn = false; changed() }
            return "Screen sharing turned OFF - you can no longer see the screen."
        }
        if (screenOn) return "Screen sharing is already ON - you are receiving the screen images."
        if (!liveReady()) return "DK Live is not connected right now, so the screen cannot be shared."
        if (live()?.visionBroken == true) return "This Live model does not accept images, so screen sharing is not possible. Tell the user to pick another Gemini Live model in Settings."
        if (asking) return "The Android permission popup for screen sharing is already open - the user must tap Start."
        asking = true; changed()
        try {
            c.startActivity(Intent(c, ScreenShareActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (e: Exception) {
            asking = false; changed()
            DKLog.d("स्क्रीन शेयर नहीं खुला: ${e.message}")
            return "Could not open the screen sharing permission (${e.message})."
        }
        DKAccessibilityService.instance?.autoTapShareStart()
        DKLog.d("स्क्रीन शेयर: इजाज़त माँगी")
        return "Asked Android for screen-sharing permission; DK tries to tap Start itself, otherwise the user taps Start on the popup. " +
            "You will get a SYSTEM NOTE when sharing really starts - until then you CANNOT see the screen, so do not describe it. Tell the user briefly."
    }

    fun toggleScreen(c: Context): String = setScreen(c, !(screenOn || asking))

    fun onScreenStarted() {
        asking = false; screenOn = true; changed()
        DKLog.d("स्क्रीन शेयर चालू")
        note("SYSTEM NOTE: Screen sharing is ON now. From now on you receive an image of the user's phone screen about once a second. " +
            "Say in one short sentence that you can see the screen now (in the user's language) - unless the user already asked something about it, then answer that.")
    }

    fun onScreenStopped(why: String) {
        val was = screenOn
        asking = false; screenOn = false; changed()
        DKLog.d("स्क्रीन शेयर बंद ($why)")
        if (was) note("SYSTEM NOTE: Screen sharing is OFF now ($why). You can no longer see the screen. Say it in one very short sentence.")
    }

    fun onScreenDenied(why: String) {
        asking = false; changed()
        DKLog.d("स्क्रीन शेयर शुरू नहीं हुआ ($why)")
        note("SYSTEM NOTE: Screen sharing did NOT start ($why). You cannot see the screen. Tell the user in one short sentence.")
    }

    // ---------------------------------------------------------------- कैमरा
    fun setCamera(c: Context, on: Boolean, facing: String = ""): String {
        if (!on) {
            if (!cameraWanted && !cameraOn) return "Camera is already OFF."
            cameraWanted = false; changed()
            return "Camera turned OFF - you can no longer see through it."
        }
        if (!liveReady()) return "DK Live is not connected right now, so the camera cannot be used."
        if (live()?.visionBroken == true) return "This Live model does not accept images, so the camera is not possible. Tell the user to pick another Gemini Live model in Settings."
        val f = facing.lowercase()
        val front = when {
            f.contains("front") || f.contains("selfie") || f.contains("आगे") || f.contains("सेल्फ़ी") || f.contains("सेल्फी") -> true
            f.contains("back") || f.contains("rear") || f.contains("पीछे") -> false
            else -> cameraFront
        }
        if (cameraOn && front == cameraFront) return "Camera is already ON (${if (front) "front" else "back"}) - you are receiving camera images."
        cameraFront = front; cameraWanted = true; changed()
        if (!VoiceActivity.visible) {
            try {
                c.startActivity(Intent(c, VoiceActivity::class.java).putExtra("camera", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            } catch (e: Exception) { DKLog.d("कैमरा: आवाज़ स्क्रीन नहीं खुली: ${e.message}") }
        }
        val perm = c.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        return "Turning the ${if (front) "front" else "back"} camera ON on DK's voice screen" +
            (if (perm) "" else " (the user must first allow the Camera permission)") +
            ". You will get a SYSTEM NOTE when images start - do not describe anything before that. Tell the user briefly."
    }

    fun toggleCamera(c: Context): String = setCamera(c, !(cameraWanted || cameraOn))
    fun flipCamera(c: Context): String = setCamera(c, true, if (cameraFront) "back" else "front")

    fun onCameraStarted(front: Boolean) {
        val was = cameraOn
        cameraOn = true; cameraFront = front; changed()
        DKLog.d("कैमरा चालू (${if (front) "आगे" else "पीछे"})")
        note(if (was) "SYSTEM NOTE: Switched to the ${if (front) "front (selfie)" else "back"} camera."
            else "SYSTEM NOTE: The ${if (front) "front (selfie)" else "back"} camera is ON now. From now on you receive a camera image about once a second. " +
                "Say in one short sentence that you can see now and ask what to look at - unless the user already asked something, then answer that.")
    }

    fun onCameraStopped(why: String) {
        val was = cameraOn
        cameraOn = false; cameraWanted = false; changed()
        if (was) {
            DKLog.d("कैमरा बंद ($why)")
            note("SYSTEM NOTE: The camera is OFF now ($why). You can no longer see through it.")
        }
    }

    // ---------------------------------------------------------------- Live के साथ
    /** DK लाइव बंद = कुछ नहीं दिखाते */
    fun onLiveEnded(@Suppress("UNUSED_PARAMETER") c: Context) {
        if (screenOn || asking) ScreenShareService.instance?.shutdown("DK लाइव बंद") ?: run { asking = false; screenOn = false }
        cameraWanted = false
        changed()
    }

    fun onVisionBroken(c: Context) {
        ScreenShareService.instance?.shutdown("मॉडल तस्वीर नहीं लेता")
        asking = false; screenOn = false; cameraWanted = false; changed()
        main.post { UiLang.toast(c, "यह Gemini Live मॉडल तस्वीर नहीं देख पा रहा — स्क्रीन शेयर / कैमरा बंद किया", android.widget.Toast.LENGTH_LONG).show() }
    }
}
