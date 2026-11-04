package xyz.cncnlp.soundwave

/** Pure, bounded decisions for ScriptRuntime lifecycle recovery. */
internal object ScriptRuntimeHealthPolicy {
    const val MAX_INIT_ATTEMPTS = 3

    fun nextInitAttempt(previousAttempts: Int): Int? =
        if (previousAttempts < MAX_INIT_ATTEMPTS) previousAttempts + 1 else null

    /** A timeout proves a stuck synchronous handler only when the handler never returned. */
    fun shouldRebuildAfterTimeout(handlerReturned: Boolean, isDownload: Boolean): Boolean =
        !handlerReturned && !isDownload
}
