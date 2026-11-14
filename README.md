# Media Control

Control this computer's media players (Chrome, Firefox, Spotify, mpv… anything
that speaks MPRIS) and its system volume from an Android phone on the local
network.

- `server/mediactrl-server`: Python + GLib, no extra packages. Follows every
  MPRIS player over D-Bus and the default PipeWire sink (wpctl/pactl), serves
  JSON lines on TCP **47800**, and announces itself over mDNS as `_mediactrl._tcp`.
- `android/` (`com.consel.mediactrl`, Kotlin/Compose, minSdk 31): finds the
  computer automatically and shows art, title, seek bar, play/pause/next/previous,
  shuffle/repeat and the PC volume. A foreground service mirrors the player into a
  MediaSession, so the notification shade and lock screen controls work. While the
  computer is playing, the phone's volume keys change the computer's volume
  (toggle in the ⋮ menu).

## Server

    server/install.sh              # copies to ~/.local/bin, enables the systemd user unit
    server/install.sh --firewall   # also opens tcp/47800 + udp/5353 to private ranges in ufw (sudo)

    systemctl --user status mediactrl-server
    journalctl --user -u mediactrl-server -f
    mediactrl-server devices       # approved phones
    mediactrl-server forget ID|all

Pairing: the first time a phone connects, a desktop notification asks to
**Allow** or **Deny** it. Approved phones live in `~/.config/mediactrl/devices.json`.
After a Deny, that phone is refused for 10 minutes. Only private, loopback and
link-local peer addresses are accepted. `--no-pairing` skips the approval prompt.

When Plasma's browser integration mirrors a browser (`kde:pid` in its metadata),
the browser's own, poorer MPRIS entry is hidden, as KDE's media applet does.

## Android app

Needs a full JDK 17–21 (with javac) and the Android SDK (platform 36).

    cd android
    ./gradlew assembleRelease      # ~2 MB, signed with the debug key
    adb install -r app/build/outputs/apk/release/app-release.apk

The release build skips the lintVital pass.

Testing over USB without the firewall rule: `adb reverse tcp:47800 tcp:47800`,
then enter `127.0.0.1` in the app.

If the computer stays unreachable for 2 minutes while the app is closed, the
service stops and the notification goes away. Opening the app reconnects.

## Protocol (v1)

One JSON object per line, UTF-8, over TCP. Positions and lengths are microseconds.

Phone → computer

| message | meaning |
|---|---|
| `{"t":"hello","v":1,"id":"<uuid>","name":"Xperia"}` | first message; id is stable per install |
| `{"t":"ping"}` | every 25 s; the server drops clients silent for 75 s |
| `{"t":"cmd","player":ID,"action":A}` | A = play_pause, play, pause, next, previous, stop, raise |
| `{"t":"seek","player":ID,"position":us}` | absolute seek |
| `{"t":"set","player":ID,"prop":P,"value":V}` | P = volume (0–1), shuffle (bool), loop ("None"/"Track"/"Playlist") |
| `{"t":"sysvol","volume":0.5}` / `{"t":"sysvol","muted":true}` | default sink volume / mute |

Computer → phone

| message | meaning |
|---|---|
| `{"t":"welcome","v":1,"host":"name"}` | accepted; state follows |
| `{"t":"pending","host":"name"}` / `{"t":"denied"}` | waiting for Allow / refused (then closed) |
| `{"t":"players","active":ID,"players":[…]}` | full list on every change |
| `{"t":"sysvol","volume":0.72,"muted":false}` | on every change |
| `{"t":"art","id":"…","mime":"image/jpeg","data":"<base64>"}` | sent once per client before a `players` message that references it |
| `{"t":"pong"}` | |

Each player object has: id (MPRIS bus name), identity, status
(Playing/Paused/Stopped), title, artist, album, length, position, rate,
canControl, canPlay, canPause, canNext, canPrev, canSeek and art (id or null).
Optional fields are volume, shuffle and loop. `position` is valid when the
message is sent, and clients extrapolate it while status is Playing. The server
polls Position every 3 s and pushes it only after a jump (seek) larger than 0.8 s.
