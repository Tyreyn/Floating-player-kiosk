# Floating Media Player

A [Kiosk Satellite](https://kiosksatellite.com) plugin that keeps a small [Music Assistant](https://www.music-assistant.io/) player on screen **above other apps**, such as Chrome, YouTube, SmartTube or Stremio.

Kiosk Satellite's built-in floating player belongs to the kiosk window, so it disappears as soon as another app comes to the front. This plugin draws its own system window, which stays visible over whatever app is open. While a Kiosk Satellite screen is in front, the window hides by default, so it never covers the built-in player.

## Features

- Artwork, title, artist and a progress bar.
- Previous, play/pause and next buttons.
- A close button that hides the window without stopping the music. The window returns with the next track or a **Show player window** action.
- Shows only while the player has media loaded, whether it is playing or paused.
- Drag the window by its artwork or title. The position is remembered.

## Actions

Run them from a gesture or the kiosk drawer, or expose them to Home Assistant as buttons in the plugin's subpage.

| Action | Effect |
| --- | --- |
| **Show player window** | Shows the window again after it was closed or hidden. |
| **Hide player window** | Hides the window until **Show player window** runs. The music keeps playing. |
| **Show on the kiosk too** | Turns on **Show on the kiosk too**, for example while the kiosk shows a page without its own media controls. |
| **Show only above other apps** | Turns **Show on the kiosk too** off again. |

## Settings

| Setting | Description |
| --- | --- |
| **Player** | The Music Assistant player the window shows and controls. **Automatic** follows this kiosk's own player. Press **Refresh** to list the players on your Music Assistant server. |
| **Show on the kiosk too** | Also show the window while a Kiosk Satellite screen is in front. Off by default. |
| **Horizontal position** / **Vertical position** | Saved when you drag the window. `-1` places it in the bottom right corner. |

**Automatic** uses the Music Assistant player that stands for this kiosk's own Sendspin player. If your kiosk plays music through another player, such as its AirPlay receiver, pick that player instead.

## Tested setups

Tested on a Lenovo Tab (TB351FU) with Music Assistant playing a Spotify Connect source. Pausing and resuming from the window while another app, such as Chrome, is in front depends on how the music reaches the kiosk:

| How the kiosk receives music | Result |
| --- | --- |
| **AirPlay** receiver | **Recommended.** Pause and resume work above other apps, and the music keeps playing after returning to the kiosk. Track changes are clean. |
| **DLNA** renderer | **Not recommended with this plugin.** Music Assistant stops the DLNA stream instead of pausing it. While Kiosk Satellite is in the background, the stream does not restart on resume, and the Spotify Connect session ends once the kiosk comes back to the front. Commands sent from the Spotify app behave the same, so the plugin cannot work around it. Pausing on the kiosk itself works. |
| **Sendspin** player | Audio dropouts and wavering sound on track changes in our tests. |

To use AirPlay, set the Kiosk Satellite media player to this device with the AirPlay receiver turned on. Music Assistant then adds AirPlay as an output of the kiosk's player and uses it on its own.

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
