package com.northwalessolutions.netprobe.engine.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * How a screen shows an [AppMessage] (Director bug report 2026-10-22). A screen that shows a message consumes it: the dismiss happens
 * when the message is taken, not when its snackbar ends, because a snackbar that is cut short (the Director leaves the screen) never
 * reaches code placed after it, and the message would then be shown again on every later visit. [show] runs on [scope], which the
 * screen keeps across the change of [AppMessages.current] that the dismiss itself causes.
 */
object AppMessageDelivery {
    fun consume(scope: CoroutineScope, message: AppMessage, dismiss: (Long) -> Unit, show: suspend (String) -> Unit) {
        dismiss(message.id)
        scope.launch { show(message.text) }
    }
}
