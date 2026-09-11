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
| **Send Listening Data to Google** | Uploads listening history to YouTube Music to update your recommendations, stats, and Year-in-Review. | `Enabled` |

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