package com.nuvio.tv.youtube

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liskovsoft.mediaserviceinterfaces.data.SponsorSegment
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * Skips the parts of a YouTube video that SponsorBlock's community has marked (sponsors, intros and
 * so on). One per playback: it fetches the video's segments once, then [onPosition] is told where
 * playback is and either jumps past a segment you've chosen to skip, or, if you'd rather be asked,
 * puts up a card ([SponsorBlockUi]) that skips when you press OK.
 */
class SponsorBlockSession(context: Context, private val videoId: String) {
    private companion object {
        const val TAG = "SponsorBlock"
        /** Don't jump for a sliver at the very end of a segment. */
        const val MIN_REMAINING_MS = 400L
        val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "yt-sponsorblock").apply { isDaemon = true } }
    }

    private val app = context.applicationContext
    private val enabled = YouTubeSettings.sponsorBlockEnabled(app)

    @Volatile private var segments: List<SponsorSegment> = emptyList()
    private val skipped = HashSet<Long>()

    init {
        SponsorBlockUi.clear()
        if (enabled) {
            val categories = YouTubeSettings.sponsorCategoriesOn(app)
            if (categories.isNotEmpty()) {
                worker.execute {
                    runCatching {
                        GlobalPreferences.instance(app)
                        val found = YouTubeServiceManager.instance().mediaItemService
                            .getSponsorSegments(videoId, categories).orEmpty()
                            .filter { it.action == null || it.action == SponsorSegment.ACTION_SKIP }
                            .sortedBy { it.startMs }
                        segments = found
                        Log.i(TAG, "$videoId: ${found.size} segment(s)")
                    }.onFailure { Log.w(TAG, "couldn't load segments for $videoId", it) }
                }
            }
        }
    }

    /**
     * Called as playback moves. If [positionMs] is inside a segment you've chosen to skip, [seek] jumps to its
     * end, or in ask mode a card is shown until you press OK or the segment passes. Each segment is skipped
     * once, so rewinding into one to watch it isn't fought.
     */
    fun onPosition(positionMs: Long, seek: (Long) -> Unit) {
        val list = segments
        if (list.isEmpty()) return
        val hit = list.firstOrNull {
            positionMs >= it.startMs && positionMs < it.endMs - MIN_REMAINING_MS && it.startMs !in skipped
        }
        if (YouTubeSettings.sponsorAsk(app)) {
            if (hit == null) {
                SponsorBlockUi.clear()
                return
            }
            SponsorBlockUi.show(labelOf(hit)) {
                skipped += hit.startMs
                SponsorBlockUi.clear()
                seek(hit.endMs)
            }
            return
        }
        hit ?: return
        skipped += hit.startMs
        if (YouTubeSettings.sponsorNotice(app)) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(app, "Skipped: ${labelOf(hit)}", Toast.LENGTH_SHORT).show()
            }
        }
        seek(hit.endMs)
    }

    private fun labelOf(segment: SponsorSegment) =
        YouTubeSettings.sponsorCategories.firstOrNull { it.first == segment.category }?.second ?: "segment"
}

/** What the "ask me first" card is showing, and what pressing OK on it does. */
object SponsorBlockUi {
    private val _label = MutableStateFlow<String?>(null)
    val label: StateFlow<String?> = _label.asStateFlow()

    @Volatile private var accept: (() -> Unit)? = null

    fun show(label: String, onAccept: () -> Unit) {
        accept = onAccept
        _label.value = label
    }

    fun clear() {
        accept = null
        _label.value = null
    }

    val isShowing: Boolean get() = _label.value != null

    /** Skips the segment on screen. Returns whether there was one. */
    fun accept(): Boolean {
        val action = accept ?: return false
        action()
        return true
    }
}

/** The card that asks whether to skip a marked segment; OK on the remote says yes. */
@Composable
fun BoxScope.SponsorSkipCard() {
    val label by SponsorBlockUi.label.collectAsState()
    val text = label ?: return
    Column(
        Modifier
            .align(Alignment.BottomStart)
            .padding(start = 32.dp, bottom = 110.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xE6111114))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(stringResource(R.string.onetube_skip_prompt, text), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.onetube_press_ok), color = Color(0xFFBBBBBB), fontSize = 12.sp)
    }
}
