# BingeTV Redux

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" align="right" alt="">

Films, series and live TV in one app for Fire TV and Android TV. You set it up from a web page
on your phone or computer, so there's no typing with the remote.

- **Films and series** from the Stremio addons you already use (AIOStreams, AIOMetadata,
  Cinemeta, Torrentio with debrid, OpenSubtitles and the rest): browse their catalogs, pick a
  stream and play.
- **Live TV** from Xtream Codes accounts, with a TV guide, catch-up and several streams per
  channel (this part was [FyrTuner](https://github.com/shiggsy365/FyrTuner)).

BingeTV Redux provides no content of its own. Everything comes from your own IPTV account and the
Stremio addons you add.

## Install

1. On the Fire TV or Android TV, allow apps from unknown sources (on a Fire TV, for the app you'll
   download with, such as Downloader).
2. Download an APK from the
   [latest release](https://github.com/shiggsy365/BingeTV-Redux/releases/latest) and install it:
   `BingeTV-Redux-<version>-armeabi-v7a.apk` suits Fire TV Sticks and most TVs (the smallest),
   `…-arm64-v8a.apk` 64-bit devices, and `BingeTV-Redux-<version>.apk` any of them.
3. Open BingeTV Redux and select the **logo** at the left of its menu bar: profiles along the top,
   settings below. Scan the QR code with your phone (or open the address it shows on any device on
   the same network). The QR code fills in the PIN for you; otherwise type the 4-digit PIN shown on
   the TV.

Updates: about once a week the app checks for a new release and offers to install it. **Check for
updates** (under the logo) checks straight away.

## Setting up (on the web page)

- **Each profile has its own addons and catalogs** (picked at the top of the Addons and Catalogs
  tabs); a new profile starts with a copy of the one in use on the TV.
- **Addons:** paste each addon's manifest URL (or `stremio://` link). Addons are asked in the
  order listed, so put your best metadata addon (e.g. AIOMetadata) above Cinemeta. Configure,
  refresh, reorder, turn off or remove them here. Torrent-only streams aren't played: use a
  debrid service through your addon.
- **Catalogs:** each hub (Home, Movies, TV Shows, Anime, More and your own) is a page on the TV's
  menu bar, with a row for each catalog in it. Move catalogs between hubs, rename, reorder or
  hide them, and choose which catalogs Search uses.
- **Profiles:** each person gets their own picture, watch history, settings and MDBList account.
  Per profile: stream order and filters (highest resolution, hide CAM, keywords), whether Play
  starts the best stream or lists them, Up Next, audio and subtitle languages (best first; the
  player picks matching tracks), Home's rows (Continue Watching, which can include the next
  episode of shows you're watching, Next Up, Watchlist, On Now), trailers on Home, and MDBList.
- **Groups & channels, Logos, Guides, Settings:** live TV, as in FyrTuner: Xtream accounts,
  groups, channel merging and order, logos, XMLTV guides, catch-up, and backup and restore.

Backups hold everything: addons, catalogs, profiles with their history, and live TV. A FyrTuner
backup restores its live TV settings.

The web page is only usable from a browser that has entered the TV's PIN. Settings on the TV can
make a new PIN or forget every browser.

## On the TV

- **Home** and the **hubs** (icons on the menu bar; each hub's icon is chosen on the web page):
  rows of posters, with the focused title's details over its artwork, and ribbons for what's
  paused or watched. A row shows up to 50; its last card, **See all**, opens the whole catalog
  with a genre filter. Details come from your first metadata addon (e.g. AIOMetadata above
  Cinemeta), whichever addon's catalog a title is from.
- **Info pages:** details, seasons and episodes (with what you've watched), the cast (select
  someone to search for them), similar titles (with MDBList), Play or Resume, choose a stream,
  mark watched, the watchlist, the trailer, and remove from Continue Watching or Next Up.
- **Streams:** cards in two columns with badges for quality, HDR, audio and cached, filtered by
  addon, resolution or cached.
- **Player:** the OSD has the title, times, progress (Up to seek, Left/Right to skip), and buttons
  for playback, streams, audio, subtitles (tracks from the video or your subtitle addons, an
  offset to fix lip sync, and a style: colour, size, outline), picture size and speed. If a
  stream fails, the next one is tried. Near the end of an episode, Up Next counts down to the
  next one. The TV switches to the refresh rate that suits the video (50 Hz for UK television),
  where it has one.
- **Search** across all your search catalogs as you type.
- **Live TV:** the guide, and the same OSD with channels, catch-up, Start over and Change stream.
  A stream whose Xtream account is already in use on another TV is skipped, so nobody gets cut
  off.

## Building

```
./gradlew assembleDebug
```

Release builds are signed with the key named in `keystore.properties` (`storeFile`,
`keyAlias`, `storePassword`), which is never committed. GitHub Actions signs them with the
`REDUX_KEYSTORE` (base64 of the keystore) and `REDUX_KEY_PASSWORD` secrets, or the debug key
without them.

Unit tests: `./gradlew testDebugUnitTest`. `tools/make_branding.py` regenerates the icons,
banner and logos from `branding/`.

## Licence

BingeTV Redux is released under the [GNU General Public License v3.0](LICENSE), as it includes
FFmpeg audio decoders from nextlib, which is GPL-3.0.

The live TV side started from Amazon's
[Fire TV Live TV sample app](https://github.com/amzn/ftv-livetv-sample-tv-app); the code that
came from it keeps its Apache 2.0 licence and notices (see [LICENSE-APACHE-2.0](LICENSE-APACHE-2.0)
and [NOTICE](NOTICE)).

## Credits

Playback uses Media3 ExoPlayer with FFmpeg audio decoders from
[nextlib](https://github.com/anilbeesetti/nextlib); images load with Glide; the web page is served
by NanoHTTPD; QR codes come from ZXing.
