package com.nuvio.tv.music

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the TV currently has an output that can carry lossless audio.
 *
 * This is deliberately conservative, and it is not a bit-perfect claim: standard Android audio
 * still passes through the system mixer. It answers the narrower question an addon's
 * `checkValidLossless` asks — can the attached output accept a lossless stream at all?
 *
 * HDMI, USB audio, wired/line-out and docks count. Bluetooth counts only when Android names a
 * lossless codec; plain A2DP (SBC/AAC) does not. TV speakers do not.
 */
object MusicLosslessOutput {
    enum class Kind(val label: String) {
        HDMI("HDMI"),
        USB("USB audio"),
        WIRED("wired output"),
        DOCK("dock"),
        BLUETOOTH("Bluetooth")
    }

    data class State(
        val capable: Boolean = false,
        val via: Kind? = null
    ) {
        val label: String
            get() = when {
                capable && via != null -> "Lossless-capable output: ${via.label}"
                capable -> "Lossless-capable output"
                else -> "Current output can't carry lossless audio"
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val capable: Boolean get() = _state.value.capable

    private var audioManager: AudioManager? = null

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    @Synchronized
    fun init(context: Context) {
        if (audioManager != null) return
        val manager = context.applicationContext
            .getSystemService(AudioManager::class.java) ?: return
        audioManager = manager
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun refresh() {
        val types = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .orEmpty()
            .filter { it.isSink }
            .map { it.type }
            .toSet()
        _state.value = decide(types)
    }

    internal fun decide(outputTypes: Set<Int>): State {
        val via = when {
            outputTypes.any { it in HDMI } -> Kind.HDMI
            outputTypes.any { it in USB } -> Kind.USB
            outputTypes.any { it in WIRED } -> Kind.WIRED
            outputTypes.any { it in DOCK } -> Kind.DOCK
            else -> null
        }
        if (via != null) return State(capable = true, via = via)
        // Without the Nearby-devices permission Android does not name the A2DP codec. On a TV,
        // treat plain Bluetooth as lossy rather than guessing it can carry a lossless stream.
        if (AudioDeviceInfo.TYPE_BLUETOOTH_A2DP in outputTypes) {
            return State(capable = false, via = Kind.BLUETOOTH)
        }
        return State()
    }

    private val WIRED = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE
    )
    private val USB = setOf(
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY
    )
    private val HDMI = setOf(
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        29 // TYPE_HDMI_EARC, API 31
    )
    private val DOCK = setOf(
        AudioDeviceInfo.TYPE_DOCK,
        31 // TYPE_DOCK_ANALOG, API 34
    )
}
