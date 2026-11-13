package com.nuvio.tv.core.launcher

/** Main-thread only. Never retain a paused/destroyed Activity or start it again while resumed. */
internal object HomeForegroundDelivery {
    private var owner: Any? = null
    private var receiver: ((Long) -> Unit)? = null

    val isResumed: Boolean get() = receiver != null

    fun attach(owner: Any, receiver: (Long) -> Unit) {
        this.owner = owner
        this.receiver = receiver
    }

    fun detach(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        receiver = null
    }

    fun deliver(pressedAtMs: Long): Boolean {
        val target = receiver ?: return false
        target(pressedAtMs)
        return true
    }
}
