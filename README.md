# YouTube Music Extension for Echo

<p align="center">
  <img src="https://music.youtube.com/img/favicon_144.png" width="96" height="96" alt="YouTube Music Logo" />
</p>

<p align="center">
  A feature-rich YouTube Music extension for <a href="https://github.com/brahmkshatriya/echo"><b>Echo</b></a>.
</p>

<p align="center">
  <a href="https://github.com/ram222-git/echo-youtube-extension/releases/latest"><img src="https://img.shields.io/github/v/release/ram222-git/echo-youtube-extension?color=red&label=Release" alt="Latest Release"></a>
  <a href="https://github.com/ram222-git/echo-youtube-extension/actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/ram222-git/echo-youtube-extension/build.yml?branch=main&label=Build" alt="Build Status"></a>
  <a href="https://github.com/brahmkshatriya/echo"><img src="https://img.shields.io/badge/Echo-Extension-blue" alt="Echo Extension"></a>
</p>

---

## ✨ Features

- **🎵 Full YouTube Music Streaming**: Stream tracks, albums, radios, and playlists directly in Echo.
- **📊 Google Listening History Sync**:
  - Official telemetry integration matching the YouTube Music client (`WEB_REMIX`).
  - Seamlessly records plays to your Google/YouTube Music watch history, Recap, and recommendations once a track plays for 30 seconds.
  - Zero false tracking during background pre-buffering.
  - Configurable in extension settings (*"Send Listening Data to Google"*).
- **👥 Multi-Artist Radio Support**:
  - Clean separation and clickable artist profiles for collaborative tracks (e.g. *"The Kid LAROI & Justin Bieber"* $\rightarrow$ separate artists).
  - Accurate artist radio and similar tracks generation.
- **🎨 High-Quality Artwork & Video Support**:
  - Toggle between high-resolution and bandwidth-saving thumbnails.
  - Optional video stream playback support alongside pure audio formats.
- **🔍 Quick Search & Dynamic Feed**:
  - Fast search suggestions, library synchronization, and personalized recommendations.

---

## 📥 Installation

1. Make sure you have the [Echo Music Player](https://github.com/brahmkshatriya/echo) installed.
2. Go to the **[Latest Release](https://github.com/ram222-git/echo-youtube-extension/releases/latest)** page.
3. Download the `.eapk` (or `.apk`) file.
4. Open the downloaded file with **Echo** to install the extension.

---

## ⚙️ Settings

Inside Echo's Extension Settings for YouTube Music:

| Setting | Description | Default |
|---|---|---|
| **High Thumbnail Quality** | Uses higher-resolution album artwork (uses slightly more data). | `Enabled` |
| **Enable Video** | Displays video streams and background options in quality selection. | `Disabled` |
| **Preload Stream on Song Open** | Resolves the audio stream in the background when a song is opened, so playback starts instantly (uses a little extra data). | `Enabled` |
| **Send Listening Data to Google** | Uploads listening history to YouTube Music to update your recommendations, stats, and Year-in-Review. | `Enabled` |
| **Spoof Video Streams Client** | Spoofs YouTube Innertube client parameters (Auto, visionOS, iOS, TV, MWEB, Web Remix). | `Auto` |
| **PO Token Provider** | How PO Tokens are obtained for age-restricted / account-locked tracks (these are only served to web clients, whose stream URLs the CDN rejects without a token): **On-device** — minted on this phone in a headless WebView, no server needed (recommended, device-verified); **Off** — token-free clients only; **External** — a user-hosted `bgutil-ytdlp-pot-provider` server. On-device falls back to External when a URL is also set. | `On-device` |
| **PO Token Server URL** | Base URL of a `bgutil-ytdlp-pot-provider` compatible server (e.g. `http://192.168.1.10:4416`); entering a URL enables tokens for the External mode. Optional. | *empty* |

### PO Tokens — what requires what

- Normal and signed-in tracks play through **token-free clients** (visionOS / TVHTML5) with **no configuration**.
- **Age-restricted or account-locked tracks** (signed in) need a PO Token on the web-client lane. **On-device** generates it on the phone (anonymous BotGuard attestation; no PC, no server, no API key; no cookies ever leave the device — minting traffic is cookie-less) and is verified working on real devices. If your WebView is too old or minting fails, playback falls back to the token-free clients, and the **External** server remains as a fallback. Tokens are bound to the video being played, cached in memory only, and never stored or logged.

---

## 🛠️ Building from Source

To build the signed release package locally:

```bash
# Clone the repository
git clone https://github.com/ram222-git/echo-youtube-extension.git
cd echo-youtube-extension

# Build the release APK
./gradlew assembleRelease
```

The output will be generated in `app/build/outputs/apk/release/app-release.apk`.

---

## 🙏 Credits & Acknowledgments

- **[Echo](https://github.com/brahmkshatriya/echo)** by [Brahmkshatriya](https://github.com/brahmkshatriya)
- **[YTM-kt](https://gitlab.com/toasterofbread/ytm-kt)** by [Talo (toasterofbread)](https://gitlab.com/toasterofbread) for the underlying YouTube Music API client.