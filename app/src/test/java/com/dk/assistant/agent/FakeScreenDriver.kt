package com.dk.assistant.agent

import com.dk.assistant.agent.core.DriverResult
import com.dk.assistant.agent.core.ScreenDriver
import com.dk.assistant.agent.core.ScreenObservation
import com.dk.assistant.agent.core.UiElement

/**
 * test के लिए नकली फ़ोन: स्क्रीनें नाम से, element पर click = अगली स्क्रीन (onClick).
 * हर capture नया observationId; पुराने पर click = STALE.
 */
class FakeScreenDriver(
    var pkg: String,
    screens: Map<String, List<UiElement>>,
    var current: String,
) : ScreenDriver {
    val screens = screens.toMutableMap()
    /** (स्क्रीन, element का label) -> अगली स्क्रीन */
    val onClick = mutableMapOf<Pair<String, String>, String>()
    val clicks = mutableListOf<String>()
    val typed = mutableListOf<String>()
    var gestureTaps = 0
    var clickWorks = true
    var captureCount = 0
    var onCapture: (FakeScreenDriver) -> Unit = {}
    private var id = 0L
    private var lastId = -1L
    private val texts = mutableMapOf<Pair<String, Int>, String>()

    override suspend fun capture(): ScreenObservation {
        captureCount++
        onCapture(this)
        lastId = ++id
        val els = (screens[current] ?: emptyList()).map { e -> texts[current to e.id]?.let { e.copy(text = it) } ?: e }
        return ScreenObservation(lastId, pkg, els)
    }

    private fun go(e: UiElement) {
        clicks += e.label
        onClick[current to e.label]?.let { current = it }
    }

    override suspend fun click(obs: ScreenObservation, e: UiElement): DriverResult {
        if (obs.observationId != lastId) return DriverResult.STALE
        if (!clickWorks) return DriverResult.FAILED
        go(e); return DriverResult.OK
    }

    override suspend fun tapAt(x: Int, y: Int): DriverResult {
        gestureTaps++
        val e = (screens[current] ?: emptyList()).firstOrNull { x in it.bounds.left..it.bounds.right && y in it.bounds.top..it.bounds.bottom }
            ?: return DriverResult.FAILED
        go(e); return DriverResult.OK
    }

    override suspend fun setText(obs: ScreenObservation, e: UiElement, text: String): DriverResult {
        if (obs.observationId != lastId) return DriverResult.STALE
        typed += text
        texts[current to e.id] = text
        return DriverResult.OK
    }

    override suspend fun scroll(down: Boolean) = DriverResult.OK
    override suspend fun back(): DriverResult = DriverResult.OK
    override suspend fun pause(ms: Long) {}
}
