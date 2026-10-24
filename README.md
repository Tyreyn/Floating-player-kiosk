# Floating Media Player

A [Kiosk Satellite](https://kiosksatellite.com) plugin that keeps a small [Music Assistant](https://www.music-assistant.io/) player on screen **above other apps**, such as Chrome, YouTube, SmartTube or Stremio.

Kiosk Satellite's built-in floating player belongs to the kiosk window, so it disappears as soon as another app comes to the front. This plugin draws its own system window, which stays visible over whatever app is open. While a Kiosk Satellite screen is in front, the window hides by default, so it never covers the built-in player.

## Features

- Artwork, title, artist and a progress bar.
- Previous, play/pause and next buttons.
- Shows only while the player has media loaded, whether it is playing or paused.
- Drag the window by its artwork or title. The position is remembered.
- **Show player window** and **Hide player window** actions for gestures, the kiosk drawer or Home Assistant.

## Settings

| Setting | Description |
| --- | --- |
| **Player** | The Music Assistant player the window shows and controls. **Automatic** follows this kiosk's own player. Press **Refresh** to list the players on your Music Assistant server. |
| **Show on the kiosk too** | Also show the window while a Kiosk Satellite screen is in front. Off by default. |
| **Horizontal position** / **Vertical position** | Saved when you drag the window. `-1` places it in the bottom right corner. |

**Automatic** uses the Music Assistant player that stands for this kiosk's own Sendspin player. If your kiosk plays music through another player, such as its DLNA renderer, pick that player instead.

## Requirements

- Kiosk Satellite with Plugin Manager (plugin SDK 1) on Android 8.0 or newer.
- Music Assistant set up in **Settings > Media Player > Music Assistant**.
- The **Display over other apps** permission for Kiosk Satellite. Kiosk Satellite usually holds it already. If the plugin page reports that it is missing, grant it in **Android Settings > Apps > Kiosk Satellite > Display over other apps**.

## Data and privacy

- All player state and every command go through Kiosk Satellite's own Music Assistant connection. The plugin never sees your Music Assistant token.
- The plugin downloads the artwork from the image address Music Assistant reports for the current track.
- Nothing else leaves the device. The plugin does not talk to Home Assistant or any other service.

## Limitations

- The window is a regular Android overlay window that the plugin adds from inside the Kiosk Satellite process. The plugin SDK officially documents only overlays inside the kiosk window, so a future Kiosk Satellite or Android release may restrict this approach.
- The progress bar shows the position but does not seek.

## Installation

### Community Plugins (Recommended)

Floating Media Player is listed in [Community Plugins](https://kiosksatellite.com/plugins/). Use the button below to open it in your Kiosk Satellite and install it.

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=Tyreyn/Floating-player-kiosk)

Or search for `Floating Media Player` in **Settings > Plugin Manager > Browse plugins** in Kiosk Satellite.

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/Tyreyn/Floating-player-kiosk` and choose **Preview**.
3. Review the plugin and choose **Trust and install**.
4. Enable **Floating Media Player** with the switch on its row.

## Building

The plugin is built from the [Kiosk Satellite plugin template](https://github.com/jxlarrea/kiosk-satellite-plugins). Releases are built and published by the Release workflow when a `v*` tag is pushed.

```sh
python3 tools/test.py
python3 tools/build.py --android-platform 35
```

## License

[Apache-2.0](LICENSE). This project is not affiliated with Music Assistant or Kiosk Satellite.
