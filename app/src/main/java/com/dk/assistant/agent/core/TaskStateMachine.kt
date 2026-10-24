package com.dk.assistant.agent.core

/** हर काम (task) की हालत; सिर्फ़ तय रास्तों से बदलती है */
enum class ExecPhase {
    IDLE, OPENING_APP, OBSERVING, RESOLVING_TARGET, VALIDATING_TARGET, EXECUTING_ACTION, WAITING_FOR_UI,
    VERIFYING, NEXT_STEP, WAITING_FOR_CONFIRMATION, WAITING_FOR_USER, COMPLETED, FAILED, CANCELLED;

    val terminal get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

class TaskStateMachine(initial: ExecPhase = ExecPhase.IDLE) {
    var phase: ExecPhase = initial
        private set
    val history = mutableListOf(initial)

    companion object {
        private val ALLOWED: Map<ExecPhase, Set<ExecPhase>> = mapOf(
            ExecPhase.IDLE to setOf(ExecPhase.OPENING_APP, ExecPhase.OBSERVING),
            ExecPhase.OPENING_APP to setOf(ExecPhase.OBSERVING, ExecPhase.WAITING_FOR_UI),
            ExecPhase.WAITING_FOR_UI to setOf(ExecPhase.OBSERVING, ExecPhase.VERIFYING),
            ExecPhase.OBSERVING to setOf(ExecPhase.RESOLVING_TARGET, ExecPhase.WAITING_FOR_USER, ExecPhase.WAITING_FOR_CONFIRMATION, ExecPhase.NEXT_STEP, ExecPhase.VERIFYING),
            ExecPhase.RESOLVING_TARGET to setOf(ExecPhase.VALIDATING_TARGET, ExecPhase.OBSERVING, ExecPhase.NEXT_STEP),
            ExecPhase.VALIDATING_TARGET to setOf(ExecPhase.EXECUTING_ACTION, ExecPhase.RESOLVING_TARGET, ExecPhase.WAITING_FOR_CONFIRMATION, ExecPhase.COMPLETED),
            ExecPhase.EXECUTING_ACTION to setOf(ExecPhase.WAITING_FOR_UI, ExecPhase.VERIFYING, ExecPhase.NEXT_STEP, ExecPhase.OBSERVING),
            ExecPhase.VERIFYING to setOf(ExecPhase.NEXT_STEP, ExecPhase.OBSERVING, ExecPhase.WAITING_FOR_USER, ExecPhase.COMPLETED),
            ExecPhase.NEXT_STEP to setOf(ExecPhase.OBSERVING, ExecPhase.WAITING_FOR_CONFIRMATION, ExecPhase.COMPLETED),
            ExecPhase.WAITING_FOR_CONFIRMATION to setOf(ExecPhase.OBSERVING),
            ExecPhase.WAITING_FOR_USER to setOf(ExecPhase.OBSERVING),
        )

        fun allowed(from: ExecPhase, to: ExecPhase): Boolean {
            if (from.terminal) return false
            // गलती / रद्द किसी भी चालू हालत से
            if (to == ExecPhase.FAILED || to == ExecPhase.CANCELLED) return true
            // लॉग-इन/CAPTCHA आदि किसी भी कदम पर आ सकते हैं
            if (to == ExecPhase.WAITING_FOR_USER) return from != ExecPhase.WAITING_FOR_USER
            return to in (ALLOWED[from] ?: emptySet())
        }
    }

    /** गलत रास्ता = false (हालत नहीं बदलती) */
    fun go(to: ExecPhase): Boolean {
        if (!allowed(phase, to)) return false
        phase = to; history += to
        return true
    }
}
