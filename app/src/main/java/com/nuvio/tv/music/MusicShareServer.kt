package com.nuvio.tv.music

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.util.withAppLocale
import android.net.Uri
import android.os.Build
import android.util.Log
import com.music.bitchord.data.model.Song
import com.nuvio.tv.core.server.DeviceIpAddress
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URLConnection
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class MusicShareState(
    val running: Boolean = false,
    val url: String? = null,
    val error: String? = null
)

/** Starts/stops the on-LAN page that lets a computer browse and download local tracks. */
object MusicShareServerManager {
    private val _state = MutableStateFlow(MusicShareState())
    val state: StateFlow<MusicShareState> = _state.asStateFlow()

    private var server: MusicShareServer? = null

    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        val token = UUID.randomUUID().toString().replace("-", "").take(12)
        val candidate = MusicShareServer(app, token)
        runCatching {
            candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            server = candidate
            val ip = DeviceIpAddress.get(app)
            if (ip.isNullOrBlank()) {
                _state.value = MusicShareState(
                    running = true,
                    url = null,
                    error = app.withAppLocale().getString(R.string.music_share_no_network)
                )
            } else {
                _state.value = MusicShareState(
                    running = true,
                    url = "http://$ip:${candidate.listeningPort}/m/$token/"
                )
            }
        }.onFailure {
            Log.w("MusicShare", "Could not start share server: ${it.message}")
            runCatching { candidate.stop() }
            _state.value = MusicShareState(error = it.message ?: app.withAppLocale().getString(R.string.music_share_start_failed))
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.stop() }
        server = null
        _state.value = MusicShareState()
    }
}

/**
 * A tiny HTTP server for the local network.
 *
 * Every URL carries a random token, so another device on the LAN can only reach it with the
 * address shown on the TV. It serves one file per track, an optional artwork thumbnail, and a
 * streamed ZIP of the whole library.
 */
private class MusicShareServer(
    private val context: Context,
    private val token: String
) : NanoHTTPD(0) {

    override fun serve(session: IHTTPSession): Response {
        val prefix = "/m/$token"
        val uri = session.uri
        if (!uri.startsWith(prefix)) return forbidden()

        val path = uri.removePrefix(prefix).trimStart('/')
        val tracks = LocalMusicRepository.tracks.value
        return runCatching {
            when {
                path.isEmpty() -> webPlayerPage(tracks)
                path == "zip" -> zipResponse(tracks)
                path.startsWith("file/") -> fileResponse(
                    tracks,
                    path.substringAfter("file/"),
                    session.headers["range"]
                )
                path.startsWith("art/") -> artResponse(tracks, path.substringAfter("art/"))
                else -> notFound()
            }
        }.getOrElse {
            Log.w("MusicShare", "request $uri failed: ${it.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain; charset=utf-8",
                context.withAppLocale().getString(R.string.music_share_read_failed)
            )
        }
    }

    private fun webPlayerPage(tracks: List<Song>): Response {
        val res = context.withAppLocale()
        val deviceName = Build.MODEL?.takeIf { it.isNotBlank() }
            ?: res.getString(R.string.music_share_this_device)
        val trackCount = res.resources.getQuantityString(
            R.plurals.music_share_track_count,
            tracks.size,
            tracks.size
        )
        val pageTitle = res.getString(R.string.music_share_page_title)
        val subtitle = escape(res.getString(R.string.music_share_track_count_on_device, trackCount, deviceName))
        val downloadAll = res.getString(R.string.music_share_download_all)
        val searchHint = res.getString(R.string.music_share_search_hint)
        val noMatch = res.getString(R.string.music_share_no_match)
        val noLocal = res.getString(R.string.music_share_no_local)
        val footer = res.getString(R.string.music_share_footer)
        val lang = res.resources.configuration.locales[0].language
        val ui = JSONObject()
            .put("play", res.getString(R.string.music_share_play))
            .put("download", res.getString(R.string.music_share_download))
            .put("shuffle", res.getString(R.string.music_share_shuffle))
            .put("previous", res.getString(R.string.music_share_previous))
            .put("next", res.getString(R.string.music_share_next))
            .put("repeatOff", res.getString(R.string.music_share_repeat_off))
            .put("repeatOne", res.getString(R.string.music_share_repeat_one))
            .put("repeatAll", res.getString(R.string.music_share_repeat_all))
            .put("volume", res.getString(R.string.music_share_volume))
            .put("chooseTrack", res.getString(R.string.music_share_choose_track))
            .put("nowArtist", res.getString(R.string.music_share_now_artist))
            .put("noMatch", noMatch)
            .put("noLocal", noLocal)
            .toString().replace("</", "<\\/")
        val json = JSONArray().apply {
            tracks.forEachIndexed { index, song ->
                put(
                    JSONObject()
                        .put("i", index)
                        .put("title", song.title)
                        .put("artist", song.artist)
                        .put("album", song.albumName.orEmpty())
                        .put("duration", song.durationText.orEmpty())
                        .put("art", if (!song.thumbnailUrl.isNullOrBlank()) "art/$index" else "")
                        .put("file", "file/$index")
                )
            }
        }.toString().replace("</", "<\\/")

        val html = """
            <!doctype html>
            <html lang="$lang">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>${escape(pageTitle)}</title>
              <style>
                :root { color-scheme: dark; --bg:#07070a; --surface:#15151c; --surface2:#20202a;
                        --line:#2a2a36; --text:#f5f5f7; --muted:#a6a6b2; --dim:#74747f; --accent:#0a84ff; }
                * { box-sizing: border-box; }
                html, body { margin:0; height:100%; }
                body { background:var(--bg); color:var(--text); font-family:system-ui,-apple-system,"Segoe UI",sans-serif; }
                main { max-width:980px; margin:0 auto; padding:24px 18px 150px; }
                header { display:flex; flex-wrap:wrap; align-items:center; gap:12px; padding-bottom:16px;
                         border-bottom:1px solid var(--line); }
                .brand { font-size:28px; font-weight:800; letter-spacing:-.03em; }
                .sub { color:var(--muted); font-size:13px; margin-right:auto; }
                #search { background:var(--surface); border:1px solid var(--line); color:var(--text);
                          border-radius:999px; padding:9px 15px; min-width:220px; outline:none; }
                #search:focus { border-color:var(--accent); }
                .zip { color:#071018; background:var(--accent); text-decoration:none; font-weight:700;
                       font-size:12px; padding:9px 14px; border-radius:999px; white-space:nowrap; }
                ul { list-style:none; margin:14px 0 0; padding:0; }
                li { display:grid; grid-template-columns:34px 46px minmax(0,1fr) auto 30px; gap:12px;
                     align-items:center; padding:8px 6px; border-bottom:1px solid #1b1b24; border-radius:8px; }
                li:hover, li.on { background:var(--surface); }
                li.on { box-shadow:inset 3px 0 0 var(--accent); }
                .rowPlay { border:0; background:transparent; color:var(--text); font-size:15px; cursor:pointer;
                           width:30px; height:30px; border-radius:50%; }
                .rowPlay:hover { background:var(--surface2); }
                .art, .nowArt { overflow:hidden; border-radius:8px; background:linear-gradient(135deg,#242433,#101015);
                                display:flex; align-items:center; justify-content:center; }
                .art { width:46px; height:46px; }
                .art img, .nowArt img { width:100%; height:100%; object-fit:cover; }
                .note { color:var(--dim); font-size:20px; }
                .meta { min-width:0; }
                .title { font-weight:600; font-size:14px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
                .sub2 { color:var(--muted); font-size:12px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
                .dur { color:var(--dim); font-size:12px; }
                .dl { color:var(--dim); text-decoration:none; font-size:18px; width:28px; text-align:center; }
                .dl:hover { color:var(--accent); }
                .empty { color:var(--muted); padding:30px 0; }
                footer { margin-top:22px; color:var(--dim); font-size:12px; line-height:1.6; }
                #player { position:fixed; left:0; right:0; bottom:0; background:rgba(12,12,17,.96);
                          border-top:1px solid var(--line); backdrop-filter:blur(18px);
                          padding:10px 18px 12px; display:grid; grid-template-columns:56px minmax(0,1fr) auto;
                          grid-template-areas:"art meta controls" "seek seek seek" "vol vol vol";
                          gap:8px 14px; align-items:center; }
                .nowArt { grid-area:art; width:56px; height:56px; }
                .nowMeta { grid-area:meta; min-width:0; }
                #nowTitle { font-weight:700; font-size:14px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
                #nowArtist { color:var(--muted); font-size:12px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
                .controls { grid-area:controls; display:flex; align-items:center; gap:4px; }
                .controls button { border:0; background:transparent; color:var(--text); font-size:18px;
                                   width:38px; height:38px; border-radius:50%; cursor:pointer; }
                .controls button:hover, .controls button.on { background:var(--surface2); color:var(--accent); }
                #play { background:var(--text); color:var(--bg); width:44px; height:44px; font-size:18px; }
                #play:hover { background:var(--accent); color:#fff; }
                .seek { grid-area:seek; display:flex; align-items:center; gap:10px; }
                .seek span { color:var(--dim); font-size:11px; min-width:34px; text-align:center; }
                input[type=range] { -webkit-appearance:none; appearance:none; height:4px; border-radius:2px;
                                    background:#3a3a48; outline:none; }
                input[type=range]::-webkit-slider-thumb { -webkit-appearance:none; width:12px; height:12px;
                                                          border-radius:50%; background:var(--text); cursor:pointer; }
                #seek { flex:1; }
                #vol { grid-area:vol; width:120px; justify-self:end; }
                #audio { display:none; }
                @media (max-width:620px) {
                  header { align-items:stretch; }
                  #search { flex:1 1 100%; order:5; }
                  li { grid-template-columns:30px 42px minmax(0,1fr) auto; }
                  .dur { display:none; }
                  #player { grid-template-columns:48px minmax(0,1fr); grid-template-areas:"art meta" "controls controls" "seek seek" "vol vol"; }
                  .controls { justify-content:center; }
                  #vol { justify-self:center; width:100%; }
                }
              </style>
            </head>
            <body>
              <main>
                <header>
                  <div>
                    <div class="brand">${escape(pageTitle)}</div>
                    <div class="sub">$subtitle</div>
                  </div>
                  <input id="search" type="search" placeholder="${escape(searchHint)}" autocomplete="off">
                  <a class="zip" href="zip">${escape(downloadAll)}</a>
                </header>
                <ul id="list"></ul>
                <p id="empty" class="empty" hidden>${escape(noMatch)}</p>
                <footer>${escape(footer)}</footer>
              </main>

              <div id="player">
                <div id="nowArt" class="nowArt"><span class="note">&#9834;</span></div>
                <div class="nowMeta">
                  <div id="nowTitle">${escape(res.getString(R.string.music_share_choose_track))}</div>
                  <div id="nowArtist">${escape(res.getString(R.string.music_share_now_artist))}</div>
                </div>
                <div class="controls">
                  <button id="shuffle" title="${escape(res.getString(R.string.music_share_shuffle))}">&#128256;</button>
                  <button id="prev" title="${escape(res.getString(R.string.music_share_previous))}">&#9198;</button>
                  <button id="play" title="${escape(res.getString(R.string.music_share_play))}">&#9654;</button>
                  <button id="next" title="${escape(res.getString(R.string.music_share_next))}">&#9197;</button>
                  <button id="repeat" title="${escape(res.getString(R.string.music_share_repeat_off))}">&#128259;</button>
                </div>
                <div class="seek">
                  <span id="cur">0:00</span>
                  <input id="seek" type="range" min="0" max="100" value="0" step="0.1">
                  <span id="dur">0:00</span>
                </div>
                <input id="vol" type="range" min="0" max="1" value="1" step="0.01" title="${escape(res.getString(R.string.music_share_volume))}">
              </div>
              <audio id="audio" preload="metadata"></audio>

              <script>
                var TRACKS = $json;
                var T = $ui;
                var list = document.getElementById('list');
                var empty = document.getElementById('empty');
                var search = document.getElementById('search');
                var audio = document.getElementById('audio');
                var nowArt = document.getElementById('nowArt');
                var nowTitle = document.getElementById('nowTitle');
                var nowArtist = document.getElementById('nowArtist');
                var playBtn = document.getElementById('play');
                var prevBtn = document.getElementById('prev');
                var nextBtn = document.getElementById('next');
                var shuffleBtn = document.getElementById('shuffle');
                var repeatBtn = document.getElementById('repeat');
                var seek = document.getElementById('seek');
                var cur = document.getElementById('cur');
                var dur = document.getElementById('dur');
                var vol = document.getElementById('vol');
                var visible = TRACKS.slice();
                var currentId = null;
                var playing = false;
                var shuffle = false;
                var repeatAll = false;
                var repeatOne = false;
                var dragging = false;

                function esc(value) {
                  return String(value == null ? '' : value)
                    .replace(/&/g, '&amp;').replace(/</g, '&lt;')
                    .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
                }
                function fmt(seconds) {
                  if (!isFinite(seconds) || seconds < 0) return '0:00';
                  var total = Math.floor(seconds);
                  return Math.floor(total / 60) + ':' + String(total % 60).padStart(2, '0');
                }
                function art(t) {
                  return t.art ? '<img src="' + t.art + '" alt="" loading="lazy">' : '<span class="note">&#9834;</span>';
                }
                function trackById(id) {
                  for (var i = 0; i < TRACKS.length; i++) if (TRACKS[i].i === id) return TRACKS[i];
                  return null;
                }
                function visibleIndex() {
                  for (var i = 0; i < visible.length; i++) if (visible[i].i === currentId) return i;
                  return -1;
                }
                function render() {
                  var q = search.value.trim().toLowerCase();
                  visible = TRACKS.filter(function(t) {
                    return !q || (t.title + ' ' + t.artist + ' ' + t.album).toLowerCase().indexOf(q) >= 0;
                  });
                  var html = '';
                  for (var i = 0; i < visible.length; i++) {
                    var t = visible[i];
                    var on = t.i === currentId;
                    html += '<li data-i="' + i + '" class="' + (on ? 'on' : '') + '">' +
                      '<button class="rowPlay" title="' + esc(T.play) + '">' + (on && playing ? '&#10074;&#10074;' : '&#9654;') + '</button>' +
                      '<div class="art">' + art(t) + '</div>' +
                      '<div class="meta"><div class="title">' + esc(t.title) + '</div>' +
                      '<div class="sub2">' + esc(t.artist) + (t.album ? ' &middot; ' + esc(t.album) : '') + '</div></div>' +
                      '<div class="dur">' + esc(t.duration) + '</div>' +
                      '<a class="dl" href="' + t.file + '" download title="' + esc(T.download) + '">&#8681;</a></li>';
                  }
                  list.innerHTML = html;
                  empty.textContent = TRACKS.length === 0 ? T.noLocal : T.noMatch;
                  empty.hidden = visible.length !== 0;
                }
                function updateBar() {
                  var t = currentId === null ? null : trackById(currentId);
                  nowTitle.textContent = t ? t.title : T.chooseTrack;
                  nowArtist.textContent = t ? t.artist : T.nowArtist;
                  nowArt.innerHTML = t ? art(t) : '<span class="note">&#9834;</span>';
                  playBtn.innerHTML = playing ? '&#10074;&#10074;' : '&#9654;';
                  if (t) {
                    var length = isFinite(audio.duration) ? audio.duration : 0;
                    seek.max = Math.max(1, length);
                    if (!dragging) seek.value = audio.currentTime || 0;
                    cur.textContent = fmt(audio.currentTime || 0);
                    dur.textContent = fmt(length);
                  }
                }
                function playIndex(i) {
                  if (i < 0 || i >= visible.length) return;
                  var t = visible[i];
                  currentId = t.i;
                  audio.src = t.file;
                  audio.play();
                  updateBar();
                  render();
                }
                function toggle() {
                  if (currentId === null) { playIndex(0); return; }
                  if (audio.paused) audio.play(); else audio.pause();
                }
                function next() {
                  var at = visibleIndex();
                  if (shuffle && visible.length > 1) {
                    var pick;
                    do { pick = Math.floor(Math.random() * visible.length); } while (pick === at);
                    playIndex(pick);
                    return;
                  }
                  if (at < visible.length - 1) { playIndex(at + 1); return; }
                  if (repeatAll) { playIndex(0); return; }
                  playing = false;
                  updateBar();
                  render();
                }
                function previous() {
                  var at = visibleIndex();
                  if (at > 0) playIndex(at - 1);
                  else if (visible.length) playIndex(visible.length - 1);
                }
                function updateRepeat() {
                  repeatBtn.classList.toggle('on', repeatAll || repeatOne);
                  repeatBtn.innerHTML = repeatOne ? '&#128257;' : '&#128259;';
                  repeatBtn.title = repeatOne ? T.repeatOne : (repeatAll ? T.repeatAll : T.repeatOff);
                }
                list.addEventListener('click', function(event) {
                  if (event.target.closest('.dl')) return;
                  var row = event.target.closest('li');
                  if (!row) return;
                  playIndex(parseInt(row.getAttribute('data-i'), 10));
                });
                search.addEventListener('input', render);
                playBtn.addEventListener('click', toggle);
                nextBtn.addEventListener('click', next);
                prevBtn.addEventListener('click', previous);
                shuffleBtn.addEventListener('click', function() {
                  shuffle = !shuffle;
                  shuffleBtn.classList.toggle('on', shuffle);
                });
                repeatBtn.addEventListener('click', function() {
                  if (!repeatAll && !repeatOne) repeatAll = true;
                  else if (repeatAll) { repeatAll = false; repeatOne = true; }
                  else { repeatOne = false; }
                  updateRepeat();
                });
                seek.addEventListener('pointerdown', function() { dragging = true; });
                seek.addEventListener('pointerup', function() { dragging = false; });
                seek.addEventListener('input', function() {
                  if (isFinite(audio.duration)) audio.currentTime = parseFloat(seek.value);
                });
                vol.addEventListener('input', function() { audio.volume = parseFloat(vol.value); });
                audio.addEventListener('play', function() { playing = true; updateBar(); render(); });
                audio.addEventListener('pause', function() { playing = false; updateBar(); render(); });
                audio.addEventListener('timeupdate', updateBar);
                audio.addEventListener('loadedmetadata', updateBar);
                audio.addEventListener('ended', function() {
                  if (repeatOne) { audio.currentTime = 0; audio.play(); }
                  else next();
                });
                document.addEventListener('keydown', function(event) {
                  if (event.target && event.target.tagName === 'INPUT') return;
                  if (event.code === 'Space') { event.preventDefault(); toggle(); }
                  else if (event.code === 'ArrowRight' && isFinite(audio.duration)) {
                    audio.currentTime = Math.min(audio.duration, audio.currentTime + 5);
                  } else if (event.code === 'ArrowLeft') {
                    audio.currentTime = Math.max(0, audio.currentTime - 5);
                  }
                });
                render();
                updateBar();
              </script>
            </body>
            </html>
        """.trimIndent()

        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            html
        ).apply {
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun fileResponse(
        tracks: List<Song>,
        indexText: String,
        rangeHeader: String?
    ): Response {
        val track = tracks.getOrNull(indexText.toIntOrNull() ?: -1) ?: return notFound()
        val stream = openStream(track) ?: return notFound()
        val length = contentLength(track)
        val mime = mimeType(track)
        val name = downloadName(track)
        val range = if (length > 0) parseRange(rangeHeader, length) else null

        if (range != null) {
            runCatching { skipFully(stream, range.first) }
            val remaining = (range.second - range.first + 1).coerceAtLeast(0)
            return newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                mime,
                stream,
                remaining
            ).apply {
                addHeader(
                    "Content-Range",
                    "bytes ${range.first}-${range.first + remaining - 1}/$length"
                )
                addHeader("Accept-Ranges", "bytes")
                addHeader("Content-Disposition", "inline; filename=\"$name\"")
                addHeader("Cache-Control", "no-store")
            }
        }

        return if (length > 0) {
            newFixedLengthResponse(Response.Status.OK, mime, stream, length).apply {
                addHeader("Accept-Ranges", "bytes")
                addHeader("Content-Disposition", "inline; filename=\"$name\"")
                addHeader("Cache-Control", "no-store")
            }
        } else {
            newChunkedResponse(Response.Status.OK, mime, stream).apply {
                addHeader("Content-Disposition", "inline; filename=\"$name\"")
                addHeader("Cache-Control", "no-store")
            }
        }
    }

    private fun artResponse(tracks: List<Song>, indexText: String): Response {
        val track = tracks.getOrNull(indexText.toIntOrNull() ?: -1) ?: return notFound()
        val art = track.thumbnailUrl?.let(Uri::parse) ?: return notFound()
        val stream = when (art.scheme?.lowercase(Locale.ROOT)) {
            "file" -> art.path?.let { File(it) }?.takeIf { it.isFile }?.let { FileInputStream(it) }
            "content" -> runCatching { context.contentResolver.openInputStream(art) }.getOrNull()
            else -> null
        } ?: return notFound()
        val mime = when (art.scheme?.lowercase(Locale.ROOT)) {
            "content" -> context.contentResolver.getType(art) ?: "image/jpeg"
            else -> URLConnection.guessContentTypeFromName(art.path) ?: "image/jpeg"
        }
        val length = contentLength(art)
        return if (length > 0) {
            newFixedLengthResponse(Response.Status.OK, mime, stream, length)
        } else {
            newChunkedResponse(Response.Status.OK, mime, stream)
        }
    }

    private fun zipResponse(tracks: List<Song>): Response {
        val output = PipedOutputStream()
        val input = PipedInputStream(64 * 1024)
        output.connect(input)
        Thread({
            runCatching {
                ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                    val names = HashSet<String>()
                    tracks.forEachIndexed { index, track ->
                        val stream = openStream(track) ?: return@forEachIndexed
                        var name = downloadName(track)
                        if (!names.add(name)) {
                            name = "${index + 1}-$name"
                            names.add(name)
                        }
                        zip.putNextEntry(ZipEntry(name))
                        stream.use { it.copyTo(zip, 64 * 1024) }
                        zip.closeEntry()
                    }
                    zip.finish()
                }
            }
        }, "music-share-zip").apply { isDaemon = true }.start()

        return newChunkedResponse(Response.Status.OK, "application/zip", input).apply {
            addHeader("Content-Disposition", "attachment; filename=\"nuvio-music.zip\"")
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun openStream(track: Song): InputStream? {
        val uriText = track.localUri ?: return null
        val uri = Uri.parse(uriText)
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "file" -> runCatching { FileInputStream(File(uri.path ?: return null)) }.getOrNull()
            "content" -> runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            else -> track.localPath
                ?.let { runCatching { FileInputStream(File(it)) }.getOrNull() }
        }
    }

    private fun contentLength(track: Song): Long {
        val uri = track.localUri?.let(Uri::parse)
        if (uri?.scheme == "content") {
            return runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull()?.takeIf { it >= 0 } ?: -1L
        }
        val path = track.localPath ?: uri?.path
        return path?.let { File(it).length() }?.takeIf { it > 0 } ?: -1L
    }

    private fun contentLength(uri: Uri): Long {
        if (uri.scheme == "content") {
            return runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: -1L
        }
        return uri.path?.let { File(it).length() }?.takeIf { it > 0 } ?: -1L
    }

    private fun mimeType(track: Song): String {
        val uri = track.localUri?.let(Uri::parse)
        if (uri?.scheme == "content") {
            context.contentResolver.getType(uri)?.let { return it }
        }
        val path = track.localPath ?: uri?.path
        val guessed = path?.let { URLConnection.guessContentTypeFromName(it) }
        return guessed ?: "audio/mpeg"
    }

    private fun downloadName(track: Song): String {
        val safeTitle = track.title
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "track" }
        val extension = track.localPath
            ?.substringAfterLast('.', "")
            ?.takeIf { it.length in 1..5 }
            ?: when {
                mimeType(track).contains("flac") -> "flac"
                mimeType(track).contains("mp4") || mimeType(track).contains("m4a") -> "m4a"
                mimeType(track).contains("ogg") -> "ogg"
                mimeType(track).contains("wav") -> "wav"
                else -> "mp3"
            }
        return "$safeTitle.$extension"
    }

    private fun parseRange(header: String?, length: Long): Pair<Long, Long>? {
        if (header == null || !header.startsWith("bytes=") || length <= 0) return null
        val spec = header.removePrefix("bytes=").substringBefore(',').trim()
        if (spec.isEmpty()) return null
        val startText = spec.substringBefore('-').trim()
        val endText = spec.substringAfter('-', "").trim()
        return if (startText.isEmpty()) {
            val suffix = endText.toLongOrNull() ?: return null
            if (suffix <= 0) return null
            val start = (length - suffix).coerceAtLeast(0)
            start to (length - 1)
        } else {
            val start = startText.toLongOrNull() ?: return null
            if (start !in 0 until length) return null
            val end = endText.toLongOrNull()?.coerceIn(start, length - 1) ?: (length - 1)
            start to end
        }
    }

    private fun skipFully(stream: InputStream, count: Long) {
        var left = count
        val buffer = ByteArray(8 * 1024)
        while (left > 0) {
            val skipped = stream.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (skipped <= 0) return
            left -= skipped
        }
    }

    private fun notFound(): Response = newFixedLengthResponse(
        Response.Status.NOT_FOUND,
        "text/plain; charset=utf-8",
        "Not found."
    )

    private fun forbidden(): Response = newFixedLengthResponse(
        Response.Status.FORBIDDEN,
        "text/plain; charset=utf-8",
        "Forbidden."
    )

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
