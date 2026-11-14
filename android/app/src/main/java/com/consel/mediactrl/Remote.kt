package com.consel.mediactrl

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * The connection to one computer, shared by the activity and ControlService.
 * JSON lines over TCP; see PROTOCOL.md.
 */
object Remote {
    const val DEFAULT_PORT = 47800
    private const val TAG = "MediaCtrl"
    private const val PING_MS = 25_000L
    private const val READ_TIMEOUT_MS = 70_000
    /** Give up (and drop the notification) after this long unreachable with no UI open. */
    private const val GIVE_UP_MS = 120_000L
    private const val ART_KEEP = 12

    private lateinit var prefs: SharedPreferences
    private lateinit var app: Context
    lateinit var deviceId: String
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = _state.asStateFlow()
    private val _art = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val art: StateFlow<Map<String, Bitmap>> = _art.asStateFlow()
    private val _pinned = MutableStateFlow<String?>(null)
    val pinned: StateFlow<String?> = _pinned.asStateFlow()
    private val _volumeKeys = MutableStateFlow(true)
    /** Phone volume keys change the computer's volume. */
    val volumeKeys: StateFlow<Boolean> = _volumeKeys.asStateFlow()

    private var job: Job? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var outbox: Channel<String>? = null
    @Volatile private var uiVisible = false
    @Volatile private var localVolumeAt = 0L
    private val volumeRequests = MutableStateFlow<Float?>(null)

    fun init(context: Context) {
        app = context.applicationContext
        prefs = app.getSharedPreferences("mediactrl", Context.MODE_PRIVATE)
        deviceId = prefs.getString("device_id", null)
            ?: UUID.randomUUID().toString().also { prefs.edit { putString("device_id", it) } }
        _volumeKeys.value = prefs.getBoolean("volume_keys", true)
        _state.value = RemoteState(server = null)
        // Slider drags produce dozens of values a second; send at most ~16/s, always the latest.
        scope.launch {
            volumeRequests.filterNotNull().collect { v ->
                send(JSONObject().put("t", "sysvol").put("volume", v.toDouble()))
                delay(60)
            }
        }
    }

    val deviceName: String
        get() = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    val savedServer: Server?
        get() {
            val host = prefs.getString("host", null) ?: return null
            return Server(prefs.getString("name", host) ?: host, host, prefs.getInt("port", DEFAULT_PORT))
        }

    fun current(): PlayerState? = _state.value.current(_pinned.value)

    // ---------------------------------------------------------------- lifecycle

    fun connect(server: Server) {
        stopConnection()
        prefs.edit {
            putString("host", server.host); putInt("port", server.port); putString("name", server.name)
        }
        _pinned.value = null
        _state.value = RemoteState(server = server, link = Link.Connecting)
        job = scope.launch { connectionLoop(server) }
        ControlService.start(app)
    }

    /** Stop. With [forget], the next launch shows the computer picker instead of reconnecting. */
    fun disconnect(forget: Boolean = false) {
        stopConnection()
        _state.value = RemoteState(server = null)
        _art.value = emptyMap()
        if (forget) prefs.edit { remove("host"); remove("port"); remove("name") }
    }

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (visible && _state.value.server == null) savedServer?.let { connect(it) }
    }

    fun setVolumeKeys(enabled: Boolean) {
        _volumeKeys.value = enabled
        prefs.edit { putBoolean("volume_keys", enabled) }
    }

    fun pin(playerId: String?) {
        _pinned.value = playerId
    }

    private fun stopConnection() {
        job?.cancel()
        job = null
        socket?.let { runCatching { it.close() } }
        socket = null
        outbox = null
    }

    private suspend fun connectionLoop(server: Server) {
        var backoff = 1_000L
        var lostSince = 0L
        while (currentCoroutineContext().isActive) {
            var welcomed = false
            val error = try {
                welcomed = session(server)
                "Connection closed"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            if (!currentCoroutineContext().isActive) return
            if (_state.value.link == Link.Denied) return
            if (welcomed) {
                backoff = 1_000L
                lostSince = 0L
            }
            val now = SystemClock.elapsedRealtime()
            if (lostSince == 0L) lostSince = now
            Log.i(TAG, "connection to ${server.key} ended: $error")
            _state.update { it.copy(link = Link.Lost, error = error) }
            if (!uiVisible && now - lostSince > GIVE_UP_MS) {
                Log.i(TAG, "giving up on ${server.key} until the app is opened again")
                job = null
                _state.value = RemoteState(server = null)
                return
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(if (uiVisible) 4_000L else 15_000L)
        }
    }

    /** One TCP connection. Returns whether the computer accepted us. */
    private suspend fun session(server: Server): Boolean = withContext(Dispatchers.IO) {
        val sock = Socket()
        socket = sock
        var welcomed = false
        val box = Channel<String>(Channel.UNLIMITED)
        try {
            sock.tcpNoDelay = true
            sock.keepAlive = true
            sock.connect(InetSocketAddress(server.host, server.port), 5_000)
            sock.soTimeout = READ_TIMEOUT_MS
            outbox = box
            val writer = launch {
                try {
                    val w = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8))
                    for (line in box) {
                        w.write(line)
                        w.write("\n")
                        if (box.isEmpty) w.flush()
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    runCatching { sock.close() }
                }
            }
            val pinger = launch {
                while (true) {
                    delay(PING_MS)
                    box.trySend("""{"t":"ping"}""")
                }
            }
            box.trySend(
                JSONObject().put("t", "hello").put("v", 1).put("id", deviceId)
                    .put("name", deviceName).toString()
            )
            try {
                val reader = sock.getInputStream().bufferedReader(Charsets.UTF_8)
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    when (handle(JSONObject(line))) {
                        Event.Welcome -> welcomed = true
                        Event.Denied -> break
                        Event.Other -> {}
                    }
                }
            } finally {
                writer.cancel()
                pinger.cancel()
            }
            welcomed
        } finally {
            if (outbox === box) outbox = null
            box.close()
            runCatching { sock.close() }
        }
    }

    private enum class Event { Welcome, Denied, Other }

    private fun handle(o: JSONObject): Event {
        when (o.optString("t")) {
            "welcome" -> {
                _state.update {
                    it.copy(link = Link.Connected, hostName = o.optString("host").ifEmpty { null }, error = null)
                }
                return Event.Welcome
            }
            "pending" -> _state.update {
                it.copy(link = Link.Pending, peerName = o.optString("host").ifEmpty { it.peerName })
            }
            "denied" -> {
                _state.update { it.copy(link = Link.Denied, players = emptyList()) }
                return Event.Denied
            }
            "players" -> {
                val now = SystemClock.elapsedRealtime()
                val arr = o.getJSONArray("players")
                val players = (0 until arr.length()).map { PlayerState.parse(arr.getJSONObject(it), now) }
                val active = if (o.isNull("active")) null else o.optString("active")
                _state.update { it.copy(players = players, activeId = active) }
                if (_pinned.value != null && players.none { it.id == _pinned.value }) _pinned.value = null
            }
            "sysvol" -> {
                // While the user drags, our own echoes would make the slider jump back.
                if (SystemClock.elapsedRealtime() - localVolumeAt > 500 && !o.isNull("volume")) {
                    _state.update {
                        it.copy(sysVolume = o.getDouble("volume").toFloat(), muted = o.optBoolean("muted"))
                    }
                }
            }
            "art" -> {
                val id = o.getString("id")
                val bytes = Base64.decode(o.getString("data"), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bmp ->
                    _art.update { old ->
                        val m = LinkedHashMap(old)
                        m.remove(id)
                        m[id] = bmp
                        while (m.size > ART_KEEP) m.remove(m.keys.first())
                        m
                    }
                }
            }
        }
        return Event.Other
    }

    private fun send(o: JSONObject) {
        outbox?.trySend(o.toString())
    }

    // ---------------------------------------------------------------- commands

    fun command(action: String) {
        val p = current() ?: return
        send(JSONObject().put("t", "cmd").put("player", p.id).put("action", action))
        // Flip play/pause right away; the computer's echo follows within ~100 ms.
        val newStatus = when (action) {
            "play_pause" -> if (p.playing) "Paused" else "Playing"
            "play" -> "Playing"
            "pause" -> "Paused"
            else -> null
        }
        if (newStatus != null) {
            val now = SystemClock.elapsedRealtime()
            replacePlayer(p.id) { it.copy(status = newStatus, positionUs = it.positionAt(now), stampMs = now) }
        }
    }

    fun seek(positionUs: Long) {
        val p = current() ?: return
        send(JSONObject().put("t", "seek").put("player", p.id).put("position", positionUs))
        replacePlayer(p.id) { it.copy(positionUs = positionUs, stampMs = SystemClock.elapsedRealtime()) }
    }

    fun setShuffle(on: Boolean) {
        val p = current() ?: return
        send(JSONObject().put("t", "set").put("player", p.id).put("prop", "shuffle").put("value", on))
        replacePlayer(p.id) { it.copy(shuffle = on) }
    }

    fun setLoop(mode: String) {
        val p = current() ?: return
        send(JSONObject().put("t", "set").put("player", p.id).put("prop", "loop").put("value", mode))
        replacePlayer(p.id) { it.copy(loop = mode) }
    }

    fun raise() {
        command("raise")
    }

    fun setSysVolume(v: Float) {
        val vol = v.coerceIn(0f, 1f)
        localVolumeAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(sysVolume = vol, muted = if (vol > 0f) false else it.muted) }
        volumeRequests.value = vol
    }

    fun stepVolume(delta: Float) {
        val cur = _state.value.sysVolume ?: return
        setSysVolume(((cur + delta) * 100).let { Math.round(it) / 100f })
    }

    fun setMuted(muted: Boolean) {
        localVolumeAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(muted = muted) }
        send(JSONObject().put("t", "sysvol").put("muted", muted))
    }

    private fun replacePlayer(id: String, f: (PlayerState) -> PlayerState) {
        _state.update { s -> s.copy(players = s.players.map { if (it.id == id) f(it) else it }) }
    }
}
