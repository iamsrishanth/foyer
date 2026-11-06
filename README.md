# Foyer

A custom home screen for the projector, built to match the Free My TV preview: a “For You”
headline, library tabs, a wide + two portrait artwork row, a glass app dock, and a pull-up drawer
with inputs, shortcuts and weather. Framework-only Java — **no AndroidX, no Compose, no
third-party libraries** except a vendored QR encoder and the app’s own tiny JSON/HTTP helpers.

```
com.kaera.foyer  v1.0 (versionCode 1)  minSdk 23  targetSdk 31  compileSdk 35
```

## Home screen

- **For You** headline at the reference position, with the server’s own Jellyfin libraries as tabs.
  The requested order is **Shows, Movies, Anime, Dramas**; Collections and Playlists are not tabs.
  Tab labels and order come from `/Library/MediaFolders`, so renaming or adding a library on the
  server is reflected after a refresh without a code change.
- **Three circular controls** at the top right: Pair phone (QR + PIN), Remote status (opens
  Accessibility settings when the global remote service is off), Settings.
- **Media row** at y=131 in the 960×540 design space: one 520×220 wide card and two 160×220
  portrait cards. Titles and metadata are the item’s real Jellyfin fields (type, genres, season
  count for series, year, runtime for movies). Artwork is a backdrop for the wide slot where the
  server has one, otherwise the poster.
- **Glass dock** (920×103 at y=407) filled with the launchable apps and their real banners, with a
  focus scale/ring and horizontal scrolling past the first page.
- **Drawer** (D-pad down from the dock) with sources/HDMI inputs, shortcut charms (All Apps,
  Home Settings, TV Settings, Pair Remote), and a weather card. The reference’s crypto panel is
  intentionally absent; the weather card uses the freed width. The drawer and the home content
  cross-fade/slide with the same spring animation on open and close.
- **No crypto, no market data, no ticker** anywhere in the UI, assets or refresh paths.
- **Focus model**: tabs → media → dock → inputs → shortcuts, with D-pad navigation, Back to close
  the drawer/settings, and a state API that reports the exact zone/index/tab/title for the phone.
- The HOME intent declaration and the vendor forwarder file are unchanged; HOME still returns here.
  The default HOME forwarding is documented below.

## Jellyfin media

Foyer discovers the four libraries with the configured API key and caches each tab separately.

Configure it from the phone (Jellyfin settings) or directly:

    POST /api/jellyfin  {"url":"jellyfin.example.com","key":"<api key>"}

**Cache-first.** The home renders from disk, including while Jellyfin is unreachable:

- `files/jellyfin/media.json` — v3 cache: tab order, library ids, item ids/names/types/years/
  genres/seasons/runtime, backdrop flag.
- `files/jellyfin/poster_<id>.jpg` — poster images, up to 440 px wide.
- `files/jellyfin/wide_<id>.jpg` — backdrop for the wide slot, up to 1280 px wide.

The cache is used immediately at startup; a refresh is only started after the cache has been
applied and only when the last successful fetch is older than **6 hours**. Failures keep the
cached shelf and retry with backoff (2, 4, 8, 16, 30 min, capped); a success resets the backoff.
While offline the footer says **Jellyfin offline · showing cached titles**. A library with no
titles shows **No titles in <library>**, and missing artwork shows **No artwork available** (both
states are device-tested). `https://` is tried first, then `http://`; the working scheme is
remembered.

The API key is only sent in the `Authorization: MediaBrowser Token="…"` header. It is never put in
a URL, a log line, an exception message or a screenshot. URLs are shown to the user (and in
settings) without the key.

### Library tabs

The four tabs come from the server, not from hardcoded `IncludeItemTypes` queries:

- The app requests `/Library/MediaFolders`, falls back to the generic `CollectionFolder` listing if
  the key is not an admin key, and keeps the requested order.
- Items are requested per library with `ParentId`, `Recursive=true`,
  `IncludeItemTypes=Movie,Series`, newest first. BoxSets and folders are filtered out; movie
  libraries are supplemented with `Filters=IsNotFolder` when BoxSets crowd the first page.
- Season counts are looked up for the first few series of each tab only, keeping a library refresh
  bounded. Deeper items are still selectable; their metadata simply omits the season count.

## Moonfin launch contract

Pressing OK on a card opens the **exact item’s details page in Moonfin**, not Moonfin’s home:

    moonfin://item?id=<jellyfin item id>

verified against Moonfin **2.6.0** (`org.moonfin.androidtv`, versionCode 2000020): the manifest
registers the `moonfin` scheme on `.MainActivity`, and its route maps to `/item/<id>` on the
details page. The app sends the Jellyfin item id only.

Moonfin stores its own server UUID (`Uuid().v4()`), which is not exposed by the app; the
`serverId`/`userId` query parameters are therefore not sent. Moonfin resolves the item against its
configured Jellyfin server. This is the only contract limitation; it was verified by opening
`Toxic`, `Krack` (movies) and `Kill la Kill`, `Samurai Champloo` (series) from Foyer and observing
the details pages and launch URLs. If Moonfin is not installed/resolvable, Foyer shows the standard
“app not found” path rather than a blank screen.

## Weather

Open-Meteo at the coordinates configured in `WeatherService` (`LAT`, `LON`, `TIMEZONE`;
the shipped defaults are London, so change those three constants for your own location), Celsius,
with current conditions, high/low, seven hourly points, sunrise and sunset:

    https://api.open-meteo.com/v1/forecast?latitude=<lat>&longitude=<lon>&current=temperature_2m,weather_code&hourly=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset&timezone=<Area%2FCity>&forecast_days=2

The response is cached in `files/weather.json` for **15 minutes**, refreshed in the
background on Foyer start/resume and when the Jellyfin configuration is applied (there is no
continuous timer while the home sits idle), with a 5→30 min failure backoff and a stale marker.
Weather never gates the home or Jellyfin rendering: the card shows the last known data with its
update age, or clears to an unavailable state on failure. The drawer’s “Updated … ago” line comes
from that cache.

The failure path was exercised by temporarily blocking the resolved `api.open-meteo.com`
address at the firewall (runtime-only, removed afterwards): the drawer showed **Weather
unavailable**, the reason and “retries automatically”, while the home and Jellyfin shelf stayed
usable. The block was removed and the live forecast returned on the next start.

## Appearance and background

- Light is the default; Settings → Appearance cycles **Light → Dark → Auto**. Auto uses the
  computed sunrise/sunset at the weather coordinates, not the system’s night mode.
- Settings → Background cycles **Ambient → Photo**. Ambient is the pale backdrop derived from the
  current wide artwork; Photo uses the wallpaper uploaded through the existing phone API
  (`/api/wallpaper`) with a scrim. Both were exercised while checking the settings screen.
- The palette covers the home, tabs, dock, drawer, weather, overlays and dialogs.

## Phone remote and HTTP API

The existing pairing/token model is unchanged. Pair with the QR or the six-digit PIN; tokens stay in
the phone’s `localStorage` and in the app’s private SharedPreferences.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/` | – | the phone web app |
| GET | `/api/state` | – | `{pin, sel, cols, ip, port, a11y, input{helper}, focus{zone,index,tab,tabIndex,label,drawer}, tiles[], all[]}` |
| POST | `/api/pair` | PIN | `{pin}` → `{token}` |
| POST | `/api/cmd` | token | `{token, cmd}` where cmd ∈ `left right up down ok` (also `enter`/`center`); `cmd:"noop"` is the pairing probe |
| POST | `/api/launch` | token | `{token, id}` |
| POST | `/api/home` | token | `{token}` |
| POST | `/api/tiles` | token | `{token, order:"pkg,pkg", hidden:"pkg"}` |
| POST | `/api/volume` | token | `{token, dir:"up"|"down"|"mute"}` |
| POST | `/api/global` | token | `{token, action:"back"|"home"|"recents"}` |
| POST | `/api/jellyfin` | token | `{token, url, key}` |
| POST | `/api/wallpaper` | token in query | raw image bytes, `?token=…` |
| POST | `/api/wallpaper/clear` | token | `{token}` |
| POST | `/api/pin` | token | `{token}` → rotates the PIN |

`tiles` is the ordered dock; `all` is every launchable app with a `hidden` flag. `focus.tab` is the
library key (`shows`, `movies`, `anime`, `dramas`) and `focus.tabIndex` its position. Use
`/api/global` for Back/Home/Recents; `/api/cmd` handles the D-pad and OK. Risky launchers (e.g.
iOSCast/Miracast) still confirm on the TV; the phone’s `/api/launch` keeps its direct-launch
behaviour.

`/api/cmd` answers `{ok, cmd, sel, status, route, ms, target?, reason?}`:

| `status` | `route` | Meaning |
|---|---|---|
| `injected` | `system` | A real key event was sent and the system delivered it to the window that owns input (Foyer or any other app). `target` names the foreground package. |
| `foyer` | `foyer` | The input helper is down, so Foyer applied the command to its own focus model — only ever when Foyer provably owns input. |
| `unavailable` | `none` | Refused: the helper is down and Foyer is not in front. Nothing was touched — no hidden selection change, no launch. `reason` says why. |
| `unknown` | `none` | The request reached the helper but no confirmation came back, so the key may or may not have landed. Never replayed. |
| `rejected` | – | Unknown command; `ok:false`. |

`ok` stays `true` for every recognised command, because the bundled web remote paints its selection
from `sel` and must not treat a momentarily unavailable backend as a lost pairing; `status` carries
the outcome, and `/api/state`'s `input.helper` reports the result of a cached (≤4 s) authenticated
`ping` — it says the helper is reachable and accepts the token, not that a key press has been proven
to move another app.
`cmd:"noop"` always answers `ok:true` for a valid token, even with the launcher Activity gone or the
input helper down, so neither client has to re-pair over a recoverable problem.

## Cross-app D-pad and OK (input helper)

D-pad and OK reach **whatever app is on screen**, not just Foyer. On this Android 12 projector that
needs a separately provisioned helper, because:

- an ordinary app cannot inject key events — `INJECT_EVENTS` is signature-only, and
  `run-as com.kaera.foyer input keyevent 20` fails with
  `SecurityException: Injecting to another application requires INJECT_EVENTS permission`;
- accessibility is no substitute: `GLOBAL_ACTION_DPAD_*` only exists from API 33, and YouTube TV
  (Cobalt) and Spotify TV publish no usable accessibility tree (one opaque focused node, no
  clickable nodes), so node actions cannot drive them.

`tools/provision-input-helper.ps1` starts `tools/foyer-inputd.sh` in the adb shell uid — the only
identity allowed to inject without root — listening on **127.0.0.1:8486** only. The helper accepts
exactly `up down left right center ping`, runs `/system/bin/input keyevent` (a proper down+up pair),
and checks a token the Foyer app keeps at
`/sdcard/Android/data/com.kaera.foyer/files/input-helper.token` (readable by the shell uid, not by
other apps). No `/system` file is touched and no root is used.

Injection is the primary route **even while Foyer is in front**: the system delivers the key to the
window that owns input, and Foyer’s own `dispatchKeyEvent` turns it into the same `HomeScreen` call
the remote used to make, so hidden state cannot move behind another app.

A key is only ever injected once. The reply distinguishes *not delivered* from *sent but
unconfirmed*: a refused connection may be retried or applied to Foyer’s own model, while a request
that reached the helper and then lost its reply is reported as `status:"unknown"` and is never
replayed — that is what keeps one press from becoming two.

```powershell
# once per projector boot, and again after `adb root`/`adb unroot` (an APK install does not need it):
.\tools\provision-input-helper.ps1
.\tools\provision-input-helper.ps1 -Status
```

Check it from the TV side: `GET /api/state` → `"input": {"helper": true}`. The phone app appends
`· input helper offline` to its status line when it is false, and a direction press then reports
`unavailable` instead of pretending. Foyer itself keeps working: with the helper down, D-pad/OK still
navigate Foyer whenever Foyer owns the screen.

Troubleshooting:

| Symptom | Cause / fix |
|---|---|
| `input.helper:false`, commands say `input helper is not running` | helper not started (projector rebooted, or `adb root`/`adb unroot` restarted adbd) → re-run the provisioning script |
| `input.helper:false` while the daemon is running | token mismatch: the token file was replaced after the helper started → re-run the script (it restarts the daemon) |
| `input helper is not provisioned` | token file missing → open Foyer once (it writes it at service start), then re-run the script |
| `input helper rejected the token` | token file was replaced while the helper held an old copy → re-run the script |
| Helper is up but an app ignores directions | that app has no focusable control in that direction (a boundary); compare with the projector’s own remote |

## Build

Needs an Android SDK, a JDK and Gradle (SDK 35, JDK 21 and Gradle 8.7 were used here).

```bash
cd ~/foyer
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug --no-daemon
# app/build/outputs/apk/debug/app-debug.apk
```

## Install

```bash
adb connect <projector-ip>:5555
adb install -r app-debug.apk
adb shell settings put secure enabled_accessibility_services com.kaera.foyer/.FoyerAccessibility
adb shell settings put secure accessibility_enabled 1
adb shell am start -n com.kaera.foyer/.MainActivity
```

Android clears the accessibility binding on every update and on `am force-stop`; re-run the two
settings commands and confirm with `GET /api/state` → `"a11y":true`. The amber **Remote off** chip
disappears when the service is bound.

Cross-app D-pad needs the input helper as well. Installing an APK does not stop it (verified: same
PID before and after `adb install -r`), but a projector reboot or an adbd restart does, so after a
TV update finish with:

```powershell
.\tools\provision-input-helper.ps1
```

and confirm `GET /api/state` → `"input":{"helper":true}`. Accessibility and the helper are
independent: D-pad/OK work while accessibility is off, but Back/Home/Recents need it.

## Security

- Binds `0.0.0.0:8484` on the LAN.
- Mutating calls require the bearer token; wrong PIN → 403, missing token → 401.
- The wallpaper upload takes its token from the query string because the body is binary — that
  token can therefore appear in a proxy or server log. Acceptable on a home LAN; worth knowing.
- Plain HTTP, no TLS. Fine on a home LAN, not for anything routable from outside.
- The Jellyfin API key is private to the app; `/api/state` and the settings screen expose the
  server address but not the key.

## Foyer is the default HOME screen

This device does not pick HOME by intent priority. `com.newlink.nlprovision` receives the HOME key
and forwards to whichever package is named in a private file:

    /data/data/com.newlink.nlprovision/wtp.memory.home
    format: <package>;<activity>

Current value:

    com.kaera.foyer;com.kaera.foyer.MainActivity

`cmd package set-home-activity` and `cmd role add-role-holder` both report success on this build but
do not change where HOME lands — only the file does. It needs root (`adb root` works: userdebug
build), and the file must stay `system:system 0600` with its `system_app_data_file` label or the
forwarder cannot read it. Writing in place with `>` keeps the label.

### Revert to Detox

    adb root
    adb shell "printf 'com.ansub.tvhome;com.ansub.tvhome.MainActivity' > /data/data/com.newlink.nlprovision/wtp.memory.home"
    adb shell "chown system:system /data/data/com.newlink.nlprovision/wtp.memory.home"
    adb reboot

Read the current value before overwriting it if you may want to revert later.

## Known limitations

- **Moonfin server id**: see the contract section; the item id is exact, but Moonfin’s internal
  server UUID is not available to send.
- **Input helper provisioning**: cross-app D-pad stops working after a projector reboot and after
  `adb root`/`adb unroot` (adbd restarts); an APK install does *not* stop it (verified). Re-run
  `tools/provision-input-helper.ps1`. `/api/state`'s `input.helper` and the phone's status line both
  say when it is down, and Foyer's own navigation keeps working.
- **The helper is a shell-uid process, not a system app.** It injects only the five D-pad keys,
  binds 127.0.0.1 only (checked in `/proc/net/tcp`, not just claimed), and authenticates with a
  per-install token in Foyer's private external directory. Any other app that could read that
  directory could also inject those five keys; no system permission or root is granted, and nothing
  survives a reboot.
- **Vendor RAM manager** (`persist.lr.ram_manager_enable`, `persist.sys.lr.freeram_low=102400`): while
  YouTube is playing, this firmware force-kills background apps, including Foyer, despite its
  foreground service (`oom adj 50`) — observed twice during testing. When that happens the phone
  reports “No answer from <ip>”, and the same kill also drops Foyer from the enabled accessibility
  services (Back/Home stop until the TV shows it again). Reopen Foyer (physical HOME) to recover.
  Independent of the D-pad routing change.
- **Activity-destroyed-with-service-alive** could not be produced on this firmware: Foyer is the
  vendor HOME app and the forwarder restarts its Activity immediately. `/api/cmd` and `noop` no
  longer read `MainActivity.instance`, so the path is decoupled in code, but only backgrounding and
  process death were exercised on the device.

- **Season counts** are intentionally limited to the first few series per library refresh; deeper
  cards show the rest of their metadata without the season count.
- **Weather failure UI** was tested by temporarily blocking the resolved Open-Meteo address:
  the drawer showed “Weather unavailable”, the technical reason and “retries automatically”,
  while the home/Jellyfin shelf stayed usable. The block was runtime-only, has been removed,
  and the live forecast returned. The Jellyfin outage/cache path was tested separately with an
  invalid key.
- **Missing Moonfin** is handled by the package-resolve path, but uninstalling Moonfin was outside
  the allowed changes, so that branch was code-reviewed rather than device-tested.
- The reference’s social/crypto stats row is not recreated; the three top controls, headline, tabs,
  artwork row and dock are. The tabs are the requested four Jellyfin libraries instead of the
  preview’s Explore/Movies/Shows.
- Wallpaper is one image; there is no slideshow or per-time-of-day switching.
- The reference video is 960×540; the runnable app is 1920×1080 (`DesignLayout` scales by 2).
  Screenshots taken before the projector’s keystone was reset show the vendor warp; later
  screenshots are un-warped.

## Third-party code

`src/main/java/io/nayuki/qrcodegen/` is the [QR Code generator library](https://www.nayuki.io/page/qr-code-generator-library)
by Project Nayuki, MIT licensed, vendored unmodified so the build needs no dependencies.

## Phone transport policy (added 27 Oct 2026, later session)

The phone remote now has two ways to reach the projector's screen. This app owns the Wi-Fi half.

**Wi-Fi (this app).** `POST /api/cmd` and friends hand keys to the shell-uid input helper described
above. It routes input into whichever app owns the screen, and it is the only transport that can
launch apps, list them, hold the Jellyfin key and change the wallpaper.

**Bluetooth HID (the phone app).** The phone registers itself as a Bluetooth HID keyboard plus a
consumer-control device; the projector's Bluetooth stack delivers the keys itself. Nothing on this
side is involved: no HTTP server, no accessibility service, no helper process. This is the fallback
for the case documented above where the vendor RAM manager kills Foyer during playback.

What that means for this codebase:

- `/api/cmd` remains the place where a Wi-Fi-routed key lands, and its `status` field is still the
  only truth (`injected | foyer | unavailable | unknown | rejected`). `ok: true` alone is not
  proof of delivery and the phone no longer treats it as such.
- `InputRoute.dispatch` is now bounded by `BUDGET_MS = 2800` with a deadline-checked retry, and
  `helperAvailable` probes at roughly 2.5 s worst case instead of 3.7 s. The phone's own command
  timeout is 4.5 s, so "the projector refused" and "the projector never answered" stay
  distinguishable, and a key that really landed is not reported as a failure (which used to invite a
  second press).
- `noop` is still answered before anything else, so a dead Activity or a dead helper cannot force a
  re-pair, and the phone's reconnect probe stays side-effect free.
- Back/Home over Wi-Fi still need the accessibility service; over Bluetooth they do not, because the
  consumer-page usages 0x0224 / 0x0223 arrive as KEYCODE_BACK / KEYCODE_HOME.
- Mute works over Wi-Fi only: the projector's audio stack ignores KEYCODE_VOLUME_MUTE coming from an
  input device, although the kernel does raise KEY_MUTE.

Nothing on the TV side gained a Bluetooth dependency, and the launcher's own navigation, focus model,
pairing, discovery and Jellyfin behaviour are unchanged.
