package com.consel.mediactrl

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun MediaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun AppRoot(discovery: Discovery) {
    val s by Remote.state.collectAsStateWithLifecycle()
    val found by discovery.servers.collectAsStateWithLifecycle()

    // The computer got a new address (DHCP): follow it by its mDNS name.
    LaunchedEffect(found, s.link, s.server) {
        val srv = s.server ?: return@LaunchedEffect
        if (s.link == Link.Lost || s.link == Link.Connecting) {
            found.firstOrNull { it.name == srv.name && it.key != srv.key }?.let { Remote.connect(it) }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            s.server == null -> PickerScreen(found)
            s.link == Link.Connected || (s.link == Link.Lost && s.hostName != null) -> PlayerScreen(s)
            else -> StatusScreen(s)
        }
    }
}

// ------------------------------------------------------------------ picker

@Composable
private fun PickerScreen(found: List<Server>) {
    var address by rememberSaveable { mutableStateOf("") }
    val submit = {
        parseAddress(address)?.let { Remote.connect(it) }
        Unit
    }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.picker_hint), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.on_this_network), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(10.dp))
        if (found.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.searching), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        for (srv in found) {
            Card(
                onClick = { Remote.connect(srv) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_computer), null, Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(srv.name, style = MaterialTheme.typography.titleMedium)
                        Text(srv.host, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.enter_address), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = address,
            onValueChange = { address = it.trim() },
            label = { Text(stringResource(R.string.address_label)) },
            placeholder = { Text("192.168.1.10") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = submit, enabled = parseAddress(address) != null) {
            Text(stringResource(R.string.connect))
        }
    }
}

private fun parseAddress(text: String): Server? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val colon = t.lastIndexOf(':')
    // host:port, but leave bare IPv6 addresses alone
    if (colon > 0 && t.indexOf(':') == colon) {
        val port = t.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val host = t.substring(0, colon)
        return Server(host, host, port)
    }
    return Server(t, t, Remote.DEFAULT_PORT)
}

// ------------------------------------------------------------------ status

@Composable
private fun StatusScreen(s: RemoteState) {
    val name = s.displayName
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(R.drawable.ic_computer), null, Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(s.server?.key ?: "", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        when (s.link) {
            Link.Connecting, Link.Lost -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(if (s.link == Link.Connecting) R.string.connecting else R.string.unreachable),
                    textAlign = TextAlign.Center)
                if (s.link == Link.Lost && s.error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(s.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.unreachable_hint), style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Link.Pending -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.waiting_approval), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.waiting_approval_hint, name), textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Link.Denied -> {
                Text(stringResource(R.string.denied), style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { s.server?.let { Remote.connect(it) } }) {
                    Text(stringResource(R.string.try_again))
                }
            }
            Link.Connected -> {}
        }
        Spacer(Modifier.height(40.dp))
        TextButton(onClick = { Remote.disconnect(forget = true) }) {
            Text(stringResource(R.string.choose_other))
        }
    }
}

// ------------------------------------------------------------------ player

@Composable
private fun PlayerScreen(s: RemoteState) {
    val art by Remote.art.collectAsStateWithLifecycle()
    val pinned by Remote.pinned.collectAsStateWithLifecycle()
    val p = s.current(pinned)
    val bmp = p?.artId?.let { art[it] }
    val accentTarget = remember(bmp) { bmp?.let { dominantColor(it) } ?: Color.Transparent }
    val accent by animateColorAsState(accentTarget, tween(600), label = "accent")
    val enabled = s.link == Link.Connected

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(accent.copy(alpha = 0.55f), MaterialTheme.colorScheme.background))
        )
    ) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TopBar(s, p)
            AnimatedVisibility(!enabled) {
                Text(stringResource(R.string.reconnecting), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(bottom = 8.dp).clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 14.dp, vertical = 6.dp))
            }
            if (s.players.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (pl in s.players) {
                        FilterChip(
                            selected = pl.id == p?.id,
                            onClick = { Remote.pin(pl.id) },
                            label = { Text(pl.identity, maxLines = 1) },
                            leadingIcon = if (pl.playing) {
                                { Icon(painterResource(R.drawable.ic_play), null, Modifier.size(16.dp)) }
                            } else null,
                        )
                    }
                }
            }
            if (p == null) {
                Spacer(Modifier.weight(1f))
                Icon(painterResource(R.drawable.ic_music_note), null, Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.nothing_playing_on, s.displayName), textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(0.15f))
                ArtView(bmp, Modifier.weight(1f, fill = false).widthIn(max = 420.dp).aspectRatio(1f))
                Spacer(Modifier.height(28.dp))
                TrackInfo(p)
                Spacer(Modifier.height(16.dp))
                SeekBar(p, enabled)
                Spacer(Modifier.height(4.dp))
                Controls(p, enabled)
                Spacer(Modifier.weight(0.15f))
            }
            VolumeRow(s, enabled)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TopBar(s: RemoteState, p: PlayerState?) {
    val volumeKeys by Remote.volumeKeys.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_computer), null, Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(s.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(p?.identity ?: stringResource(R.string.connected), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.volume_keys)) },
                    trailingIcon = { Checkbox(checked = volumeKeys, onCheckedChange = null) },
                    onClick = { Remote.setVolumeKeys(!volumeKeys) },
                )
                if (p != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.show_on_computer)) },
                        onClick = { menu = false; Remote.raise() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.choose_other)) },
                    onClick = { menu = false; Remote.disconnect(forget = true) },
                )
            }
        }
    }
}

@Composable
private fun ArtView(bmp: Bitmap?, modifier: Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier.shadow(18.dp, shape).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(bmp, animationSpec = tween(350), label = "art") { b ->
            if (b != null) {
                Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_music_note), null, Modifier.size(96.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        }
    }
}

@Composable
private fun TrackInfo(p: PlayerState) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            p.title.ifBlank { p.identity },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000),
        )
        val sub = listOf(p.artist, p.album.takeIf { it != p.title } ?: "").filter { it.isNotBlank() }
            .joinToString("  ·  ")
        if (sub.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(sub, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SeekBar(p: PlayerState, enabled: Boolean) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(p.playing) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            if (!p.playing) break
            delay(250)
        }
    }
    var drag by remember { mutableStateOf<Float?>(null) }
    val len = p.lengthUs
    val pos = p.positionAt(maxOf(now, p.stampMs))
    if (len <= 0) {
        Text(formatTime(pos), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        return
    }
    val frac = drag ?: (pos.toFloat() / len).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = frac,
            onValueChange = { drag = it },
            onValueChangeFinished = {
                drag?.let { Remote.seek((it * len).toLong()) }
                drag = null
            },
            enabled = enabled && p.canSeek,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime((frac * len).toLong()), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(formatTime(len), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Controls(p: PlayerState, enabled: Boolean) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (p.shuffle != null) {
                IconButton(onClick = { Remote.setShuffle(!p.shuffle) }, enabled = enabled) {
                    Icon(painterResource(R.drawable.ic_shuffle), stringResource(R.string.shuffle),
                        tint = if (p.shuffle) on else off)
                }
            }
        }
        IconButton(onClick = { Remote.command("previous") }, enabled = enabled && p.canPrev,
            modifier = Modifier.size(64.dp)) {
            Icon(painterResource(R.drawable.ic_skip_previous), stringResource(R.string.previous),
                Modifier.size(40.dp))
        }
        FilledIconButton(
            onClick = { Remote.command("play_pause") },
            enabled = enabled && (p.canPlay || p.canPause),
            modifier = Modifier.size(84.dp),
            colors = IconButtonDefaults.filledIconButtonColors(),
        ) {
            Icon(painterResource(if (p.playing) R.drawable.ic_pause else R.drawable.ic_play),
                stringResource(if (p.playing) R.string.pause else R.string.play), Modifier.size(46.dp))
        }
        IconButton(onClick = { Remote.command("next") }, enabled = enabled && p.canNext,
            modifier = Modifier.size(64.dp)) {
            Icon(painterResource(R.drawable.ic_skip_next), stringResource(R.string.next), Modifier.size(40.dp))
        }
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (p.loop != null) {
                val next = when (p.loop) { "None" -> "Playlist"; "Playlist" -> "Track"; else -> "None" }
                IconButton(onClick = { Remote.setLoop(next) }, enabled = enabled) {
                    Icon(painterResource(if (p.loop == "Track") R.drawable.ic_repeat_one else R.drawable.ic_repeat),
                        stringResource(R.string.repeat), tint = if (p.loop == "None") off else on)
                }
            }
        }
    }
}

@Composable
private fun VolumeRow(s: RemoteState, enabled: Boolean) {
    val vol = s.sysVolume ?: return
    val silent = s.muted || vol <= 0f
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.8f))
            .padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { Remote.setMuted(!s.muted) }, enabled = enabled) {
            Icon(painterResource(if (silent) R.drawable.ic_volume_off else R.drawable.ic_volume_up),
                stringResource(R.string.mute))
        }
        Slider(
            value = vol,
            onValueChange = { Remote.setSysVolume(it) },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (s.muted) stringResource(R.string.muted) else "${(vol * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.End,
            color = if (s.muted) MaterialTheme.colorScheme.error else LocalContentColor.current,
            modifier = Modifier.width(56.dp).clickable(enabled = enabled) { Remote.setMuted(!s.muted) },
        )
    }
}

private fun formatTime(us: Long): String {
    val total = (us / 1_000_000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Average colour of the art, pushed towards a usable saturation for the backdrop. */
private fun dominantColor(b: Bitmap): Color {
    val px = Bitmap.createScaledBitmap(b, 8, 8, true)
    var r = 0L; var g = 0L; var bl = 0L
    for (y in 0 until 8) for (x in 0 until 8) {
        val c = px.getPixel(x, y)
        r += (c shr 16) and 0xff; g += (c shr 8) and 0xff; bl += c and 0xff
    }
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV((r / 64).toInt(), (g / 64).toInt(), (bl / 64).toInt(), hsv)
    hsv[1] = (hsv[1] * 1.3f).coerceAtMost(0.85f)
    hsv[2] = hsv[2].coerceIn(0.35f, 0.8f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}
