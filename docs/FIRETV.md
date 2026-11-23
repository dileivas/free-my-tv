# Fire TV (Fire OS)

Free My TV runs on Fire TV devices too, but Fire OS differs from Android TV in two ways that matter: **Amazon keeps the
Home button**, and older sticks are **Android 7.1 (API 25)**. This fork adds both.

Tested on an **AFTMM (Fire TV Stick 4K, 1st gen, 2018)** running **Fire OS 6.7.1.1 / Android 7.1.2 (SDK 25)**.

## What works

- The home screen, the shelf, the shortcuts row, Settings, the screensaver, inputs and the App Store top button.
- The TMDB picks, once a TMDB key is set in *Home Settings › TMDB key*.

## What was changed for Fire OS 6 (API 25)

| Change | Why |
|---|---|
| `build.sh`: `--min-sdk-version 25` | Fire OS 6 is SDK 25 |
| `build.sh`: `d8 --min-api 25` | without it d8 emits **dex 038**, which Android 7.1 refuses to load (`Unrecognized version number`) |
| `Ui.font`: `Typeface.Builder` only on API 26+, `Typeface.createFromAsset` below | `Typeface.Builder` is API 26 |
| `HomeApps.iconTile`: `AdaptiveIconDrawable` moved into a nested class, used on API 26+ only | the class does not exist on API 25 and `instanceof` throws `NoClassDefFoundError` |
| `SleepOverlay`: `NotificationChannel` / `startForegroundService` / `TYPE_APPLICATION_OVERLAY` guarded | all API 26 |

## Taking over the Home button

Fire OS gives `CATEGORY_HOME` to `com.amazon.tv.launcher` (priority 950) and **ignores**
`cmd package set-home-activity` — it answers `Success` and changes nothing. Android TV's way of becoming the home
screen therefore does not work here.

`FireHomeKeeper` is a small foreground service that reads the foreground package from `UsageStats` (every 250 ms) and,
when Amazon's launcher comes to the front, brings our own task back with `moveTaskToFront()` — the same approach
dumbtv uses (MIT). No accessibility service: Fire OS 6.7 refuses third-party ones.

It is **off by default**. Enable it with:

```sh
adb shell appops set io.freemytv GET_USAGE_STATS allow
adb shell am startservice -n io.freemytv/.FireHomeKeeper --ez enable true
```

Disable it with the same command passing `--ez enable false`. The choice is remembered, and a boot receiver starts it
again after a restart.

### What to expect

- **A short flash of Amazon's home screen (~0.4 s)** on every HOME press. Fire OS hands HOME to Amazon first; we can
  only react afterwards. On Fire OS 7/8 the accessibility path can cover it; on Fire OS 6 it cannot.
- **Do not run a second HOME takeover at the same time.** Two keepers fight over HOME and the result is
  unpredictable — turn the other one off first.
- If the home screen crashes three times in five minutes, the built-in Watchdog reverts to the TV's original home
  screen and shows a notice. Reinstalling the app clears that state.

## API 25 fixes found while running with the picks on

| Change | Why |
|---|---|
| `Backdrop.gpu`: `Bitmap.Config.HARDWARE` moved to a nested class, Android 8+ only | the field does not exist on API 25 (`NoSuchFieldError`) and it is reached as soon as a pick has a backdrop |
| `HomeView.startStatus`: `registerDefaultNetworkCallback` is Android 8+; API 25 uses `registerNetworkCallback(NetworkRequest, cb)`; the `catch` is `Throwable` | with **Status icons** on, the home screen crashed on every resume (`NoSuchMethodError` — an `Error`, so `catch (RuntimeException)` never saw it) |

## Fire OS answers UsageStats with nothing

On this device `UsageStatsManager.queryEvents` returns **no events** for a sideloaded app even with
`GET_USAGE_STATS allow`. `FireHomeKeeper` therefore falls back to `queryUsageStats(INTERVAL_DAILY, …)`
(last used package) and then to `getRunningTasks(1)`. All three are tried in that order.

## Shelf and extras in this fork

`HomeApps.SHELF` also lists the apps this house uses, so they show up without picking them one by one:
Jellyfin, Seerr, Kodi, Stremio, Downloader, Unitv and Amazon Music. The shelf shows 8 at a time; the rest are in
*Settings › Shelf*.

`show_memory=true` in `config.properties` adds the free RAM under the clock.

## Homelab card

This fork adds an optional server-health card to the drawer, next to the weather: RAM and disk bars, CPU, temperature,
IO and running containers, from a small JSON endpoint you host yourself (see [tools/homelab](../tools/homelab)).
Set `homelab_url` in `config.properties`. When the server is down the card disappears and the weather card takes its
room back — silent, no error state.

## Limitations

- The shelf starts with the well-known apps only; add yours in *Settings › Shelf* (Jellyfin, Kodi and sideloaded apps
  have to be picked by hand).
- Performance on 2018 sticks (quad A53, 1.5 GB) is lower than the 60 fps this project measures on modern TVs. Test
  with *Install as an app* first, before enabling the takeover.
- `FireHomeActivity` is not exported: launch the app with `adb shell monkey -p io.freemytv 1` (or the app icon), not
  with `am start`.

## Reverting

```sh
adb shell am startservice -n io.freemytv/.FireHomeKeeper --ez enable false
adb uninstall io.freemytv
```

The TV goes back to Amazon's home screen on the next HOME press.
