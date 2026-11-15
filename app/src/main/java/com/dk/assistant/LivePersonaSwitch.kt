package com.dk.assistant

/**
 * 15 नवंबर 2026 (मालिक): "जो डीके का स्वभाव है, वह जैसे ही चेंज होगा, तो उसी टाइम तुरंत चेंज होना चाहिए."
 * चलते DK Live में स्वभाव / संबोधन बदला = DK चुप होते ही (बोलना / सुना हुआ जवाब / tool पूरा) नया सत्र.
 * Pure (JVM test).
 */
object LivePersonaSwitch {
    fun due(connKey: String, nowKey: String, turnActive: Boolean, playing: Boolean, toolsIdle: Boolean): Boolean =
        connKey.isNotEmpty() && nowKey.isNotEmpty() && connKey != nowKey && !turnActive && !playing && toolsIdle
}
