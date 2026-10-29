# BingeTV Redux

Live TV and Stremio addons in one app for Fire TV and Android TV, set up from a web page on your
phone or computer.

- **Live TV** from Xtream Codes accounts, with a TV guide, catch-up and several streams per
  channel (from [FyrTuner](https://github.com/shiggsy365/FyrTuner)).
- **Films and series** from the Stremio addons you already use: add them by manifest URL, then
  browse their catalogs, pick a stream and play.

BingeTV Redux provides no content of its own. Everything comes from your own IPTV account and the
Stremio addons you add.

*BingeTV Redux is in early development.*

## Setup

1. Install the APK on your Fire TV or Android TV.
2. Open BingeTV Redux. Settings shows the web address, a QR code and a PIN.
3. Open the address on a phone or computer on the same network and enter the PIN.

Moving from FyrTuner? Make a backup in FyrTuner's web page (Settings → Backup) and restore it in
BingeTV Redux's: your accounts, groups, channels, logos and guides come across. The two apps
can be installed side by side.

## Building

```
./gradlew assembleDebug
```

Release builds are signed with the key named in `keystore.properties` (`storeFile`,
`keyAlias`, `storePassword`), which is never committed. Without it they use the debug key.

`tools/make_branding.py` regenerates the icons, banner and logos from `branding/`.

## Credits

The live TV side started from Amazon's
[Fire TV Live TV sample app](https://github.com/amzn/ftv-livetv-sample-tv-app) and keeps its
Apache 2.0 licence (see [LICENSE](LICENSE) and [NOTICE](NOTICE)). Playback uses ExoPlayer, and
the web page is served by NanoHTTPD.
