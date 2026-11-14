package com.consel.mediactrl

import org.json.JSONObject

/** A computer running mediactrl-server. [name] is its mDNS service name. */
data class Server(val name: String, val host: String, val port: Int) {
    val key: String get() = "$host:$port"
}

enum class Link {
    /** First connection attempt in progress. */
    Connecting,
    /** Connected; waiting for the user to click Allow on the computer. */
    Pending,
    Connected,
    /** The computer refused this phone. */
    Denied,
    /** Connection dropped or could not be made; retrying. */
    Lost,
}

/** One MPRIS player on the computer. Positions are microseconds. */
data class PlayerState(
    val id: String,
    val identity: String,
    val status: String,
    val title: String,
    val artist: String,
    val album: String,
    val lengthUs: Long,
    val positionUs: Long,
    /** SystemClock.elapsedRealtime() when [positionUs] was valid. */
    val stampMs: Long,
    val rate: Double,
    val canControl: Boolean,
    val canPlay: Boolean,
    val canPause: Boolean,
    val canNext: Boolean,
    val canPrev: Boolean,
    val canSeek: Boolean,
    val shuffle: Boolean?,
    val loop: String?,
    val artId: String?,
) {
    val playing: Boolean get() = status == "Playing"

    fun positionAt(nowMs: Long): Long {
        var p = positionUs
        if (playing) p += ((nowMs - stampMs).coerceAtLeast(0) * 1000.0 * rate).toLong()
        if (lengthUs > 0) p = p.coerceAtMost(lengthUs)
        return p.coerceAtLeast(0)
    }

    companion object {
        fun parse(o: JSONObject, stampMs: Long) = PlayerState(
            id = o.getString("id"),
            identity = o.optString("identity"),
            status = o.optString("status", "Stopped"),
            title = o.optString("title"),
            artist = o.optString("artist"),
            album = o.optString("album"),
            lengthUs = o.optLong("length"),
            positionUs = o.optLong("position"),
            stampMs = stampMs,
            rate = o.optDouble("rate", 1.0).takeIf { !it.isNaN() } ?: 1.0,
            canControl = o.optBoolean("canControl", true),
            canPlay = o.optBoolean("canPlay"),
            canPause = o.optBoolean("canPause"),
            canNext = o.optBoolean("canNext"),
            canPrev = o.optBoolean("canPrev"),
            canSeek = o.optBoolean("canSeek"),
            shuffle = if (o.has("shuffle") && !o.isNull("shuffle")) o.getBoolean("shuffle") else null,
            loop = if (o.has("loop") && !o.isNull("loop")) o.getString("loop") else null,
            artId = if (o.isNull("art")) null else o.optString("art").ifEmpty { null },
        )
    }
}

data class RemoteState(
    val server: Server? = null,
    val link: Link = Link.Connecting,
    /** Set once the computer has accepted us during this connect(); survives reconnects. */
    val hostName: String? = null,
    val error: String? = null,
    /** The computer's own name, as sent while waiting for approval. */
    val peerName: String? = null,
    val players: List<PlayerState> = emptyList(),
    val activeId: String? = null,
    val sysVolume: Float? = null,
    val muted: Boolean = false,
) {
    /** The player to show: the user's pick, else the one the computer considers active. */
    fun current(pinned: String?): PlayerState? =
        players.firstOrNull { it.id == pinned }
            ?: players.firstOrNull { it.id == activeId }
            ?: players.firstOrNull()

    val displayName: String get() = hostName ?: peerName ?: server?.name ?: ""
}
