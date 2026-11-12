# BingeTV Redux

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" align="right" alt="">

Films, series and live TV in one app for Fire TV and Android TV. Everything is set up from a web
page on your phone or computer, so there's no typing with the remote.

- **Films and series** from the Stremio addons you already use (AIOStreams, AIOMetadata,
  Cinemeta, Torrentio with debrid, OpenSubtitles and the rest): browse their catalogs, pick a
  stream and play.
- **Live TV** from Xtream Codes accounts, with a TV guide, catch-up and several streams per
  channel (this part was [FyrTuner](https://github.com/shiggsy365/FyrTuner)).
- **Radio and podcasts:** internet radio from the free radio-browser.info directory, and podcasts
  from their feeds, kept in step with your other podcast apps through an oPodSync (gPodder)
  server if you have one.

BingeTV Redux provides no content of its own. Everything comes from your own IPTV account, the
Stremio addons you add, and the public radio and podcast directories.

[<img src="https://github.com/shiggsy365/AIOStreamsKODI/blob/main/.github/support_me_on_kofi_red.png?raw=true">](https://ko-fi.com/shiggsy365)

| | |
|---|---|
| ![Home](screenshots/01-home.jpg)<br>Home, with the next episode to watch in Continue Watching | ![Movies hub](screenshots/02-movies.jpg)<br>Movies hub |
| ![Film info page](screenshots/03-movie-info.jpg)<br>Film info page, with the cast | ![Series info page](screenshots/04-series-info.jpg)<br>Series info page, with its episodes |
| ![Streams](screenshots/05-streams.jpg)<br>Streams, with badges for quality, HDR and audio | ![TV Shows hub](screenshots/06-tv-hub.jpg)<br>TV Shows hub |
| ![See all](screenshots/07-see-all.jpg)<br>A whole catalog, with a genre filter | ![TV guide](screenshots/08-guide.jpg)<br>Live TV guide, with catch-up |
| ![A podcast](screenshots/09-podcast.jpg)<br>A podcast's page | ![Podcasts](screenshots/10-podcasts.jpg)<br>Podcasts, with your subscriptions |

## Install

1. On the Fire TV or Android TV, allow apps from unknown sources (on a Fire TV, for the app you'll
   download with, such as Downloader).
2. Download an APK from the
   [latest release](https://github.com/shiggsy365/BingeTV-Redux/releases/latest) and install it:
   - `BingeTV-Redux-<version>-armeabi-v7a.apk`: Fire TV Sticks and most TVs (the smallest)
   - `BingeTV-Redux-<version>-arm64-v8a.apk`: 64-bit devices
   - `BingeTV-Redux-<version>.apk`: any of them
3. Open BingeTV Redux and select the **logo** at the left of its menu bar, then **Settings** under
   the profiles. Scan the QR code with your phone, or open the address it shows on any device on
   the same network. The QR code fills in the PIN for you; otherwise type the 4-digit PIN shown on
   the TV.

About once a week the app checks for a new release and offers to install it. **Check for
updates** (in Settings) checks straight away.

## On the TV

- **Home and hubs:** each icon on the menu bar is a hub (Home, Movies, TV Shows and any you make)
  with a row for each of its catalogs, shown one at a time along the bottom of the screen, with
  dots for where you are among them. The focused title's logo, details and artwork fill the rest,
  and ribbons and progress bars show what's paused or watched. Home can also have Continue
  Watching (including the next episode of shows you're watching, once it has aired or airs within
  the week), Next Up, your Watchlist and what's on live TV now.
- **See all:** a row's last card opens the whole catalog as a grid, with a genre filter.
- **Info pages:** all on one screen. Play or Resume, then buttons to choose a stream, mark
  watched (with undo), the watchlist, the trailer, the cast (select someone to search for them)
  and similar titles (with MDBList). A show has tabs for its seasons, with each episode's air date
  and what you've watched.
- **Streams:** a list with the best stream for your TV first (in your profile's audio language,
  then the best quality your screen and connection can manage), with badges for quality, HDR and
  audio, filtered by addon, resolution or cached. The focused one opens up with all its details.
  Streams appear as each addon answers.
- **Player:** the OSD has the title, times, progress (Up to seek, Left/Right to skip) and buttons
  for streams, audio, subtitles (tracks from the video or your subtitle addons, an offset to fix
  lip sync, and a style), picture size and speed. If a stream fails, the next one is tried. Near
  the end of an episode, Up Next counts down to the next one (from the start of the credits,
  where they're known), and after a few episodes in a row with no button pressed it asks "Are you
  still watching?" first (how many is a profile setting). While an intro, recap or credits play,
  a "Skip intro" chip skips them
  (times from IntroDB, and AniSkip for anime); episodes also have a "Skip 85 s" button. Before
  playback starts, the TV
  switches to the refresh rate that suits the video (50 Hz for UK television) where it has one,
  so there's no flash once it's playing.
- **Search** across all your search catalogs, the live TV guide (what's on now, coming up, or on
  catch-up), podcasts and radio stations as you type, with your
  recent searches and IMDb's suggestions as chips under the box.
- **Radio:** favourite stations, the most popular in your country and worldwide, and genres.
  Stations keep playing while you browse (back to them from ♪ on the menu bar), and the remote's
  play/pause works on any screen.
- **Podcasts:** Continue listening, the latest episodes of your podcasts, your subscriptions and
  Apple's top podcasts. Each podcast's page has Subscribe, Mark listened and its episodes, with
  the focused one's notes; episodes carry on where you left them, and Menu marks one played.
- **Live TV:** the guide, with the focused programme's picture behind it and its channel groups
  along the top, opens on the first channel. The player has the same OSD, plus channels, catch-up,
  Start over and Change stream. A stream whose Xtream account is already in use on another TV is skipped, so
  nobody gets cut off.
- **VPN for live TV:** the **VPN** switch at the left of the guide's groups turns a WireGuard VPN
  on or off for live TV. Only the live and catch-up streams go through it; everything else in the
  app, and every other app on the TV, carries on without it. The first time, Android asks you to
  allow the connection. Android runs one VPN at a time, so this disconnects any other VPN app.

## Setting up (on the web page)

| | |
|---|---|
| ![Addons](screenshots/web-01-addons.jpg)<br>Addons | ![Catalogs and hubs](screenshots/web-02-catalogs.jpg)<br>Catalogs and hubs |
| ![Playback and languages](screenshots/web-03-playback.jpg)<br>A profile's playback settings | ![Profiles](screenshots/web-04-profiles.jpg)<br>Profiles |
| ![Groups and channels](screenshots/web-05-channels.jpg)<br>Live TV groups and channels | ![Podcasts](screenshots/web-06-podcasts.jpg)<br>Podcasts, with a sync server |

<sub>Addresses, keys and names in these screenshots are placeholders.</sub>

- **Each profile has its own addons and catalogs**, picked at the top of the Addons and Catalogs
  pages. A new profile starts with a copy of the one in use on the TV.
- **Addons:** paste each addon's manifest URL (or `stremio://` link). Addons are asked in the
  order listed, so put your best metadata addon (e.g. AIOMetadata) above Cinemeta. Configure,
  refresh, reorder, turn off or remove them here. Torrent-only streams aren't played: use a
  debrid service through your addon.
- **Catalogs:** each hub is a page on the TV's menu bar, with a row for each catalog in it. Move
  catalogs between hubs, rename, reorder or hide them, and choose which ones Search uses.
- **Profiles:** each person gets their own picture, watch history, settings and MDBList account.
  Per profile: stream order and filters (highest resolution, file size, hide CAM, keywords),
  whether Play starts the best stream or lists them, Up Next, audio and subtitle languages (best
  first), Home's rows, trailers on Home, live TV on or off, and MDBList.
- **Live TV** (groups and channels, logos, guides): Xtream accounts, channel groups, merging and
  order, logos, XMLTV guides and catch-up. Under Accounts, **VPN for live TV** takes a
  WireGuard config (a `.conf` file) from your VPN provider: Surfshark, Mullvad, Proton,
  Windscribe and most others offer one under manual or router setup. It isn't in backups.
- **Radio and Podcasts:** turn them on or off, find stations to add to favourites, subscribe to
  podcasts (by search or feed URL), and set a gPodder-compatible sync server (oPodSync,
  Nextcloud gPodder Sync) and an optional Podcast Index key.
- **Backup and restore:** a backup holds everything, including every profile with its history,
  favourite stations, podcasts and recent searches, and live TV. A FyrTuner backup restores its live TV settings.

**The web page is local to your home network.** It's served by the app on the TV itself, not from
a website, so it only opens on devices connected to the same network as the TV. What you enter
there (addon links, Xtream logins, your MDBList key) is saved on the TV and isn't sent to us or
any other server. The app only uses those details to contact the services they belong to.

The page also only works in a browser that has entered the TV's PIN. Settings on the TV can make a
new PIN or forget every browser.

## Building

```
./gradlew assembleDebug
```

Release builds (`./gradlew assembleRelease`) make one APK per ABI plus a universal one. They are
signed with the key named in `keystore.properties` (`storeFile`, `keyAlias`, `storePassword`),
which is never committed. GitHub Actions signs them with the `REDUX_KEYSTORE` (base64 of the
keystore) and `REDUX_KEY_PASSWORD` secrets, or with the debug key without them.

Unit tests: `./gradlew testDebugUnitTest`. `tools/make_branding.py` regenerates the icons, banner
and logos from `branding/`.

## Licence

BingeTV Redux is released under the [GNU General Public License v3.0](LICENSE), as it includes
FFmpeg audio decoders from nextlib, which is GPL-3.0.

The live TV side started from Amazon's
[Fire TV Live TV sample app](https://github.com/amzn/ftv-livetv-sample-tv-app). The code that came
from it keeps its Apache 2.0 licence and notices (see [LICENSE-APACHE-2.0](LICENSE-APACHE-2.0) and
[NOTICE](NOTICE)).

The app's text is set in [Figtree](https://github.com/erikdkennedy/figtree), under the SIL Open
Font License 1.1 (see [Figtree-OFL.txt](app/src/main/assets/licenses/Figtree-OFL.txt)).

The menu bar's Home, Films, TV, Live TV, Radio, Podcasts and Search icons are from
[Solar](https://www.figma.com/community/file/1166831539721848736) by 480 Design, under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

Automatic subtitle sync (`SubtitleAlign.java` and `SubtitleSync.java`) is ported from
[AIOStreams](https://github.com/Viren070/AIOStreams), and those files keep its AGPL-3.0 licence
(see [LICENSE-AGPL-3.0](LICENSE-AGPL-3.0)).

## Credits

Playback uses Media3 ExoPlayer with FFmpeg audio decoders from
[nextlib](https://github.com/anilbeesetti/nextlib). Images load with Glide, the web page is served
by NanoHTTPD and QR codes come from ZXing. Live TV's VPN uses WireGuard's
[tunnel library](https://git.zx2c4.com/wireguard-android/) and OkHttp.

**Support the ecosystem:**

[<img src="https://github.com/shiggsy365/AIOStreamsKODI/blob/main/.github/support_me_on_kofi_red.png?raw=true">](https://ko-fi.com/shiggsy365)
