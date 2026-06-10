# Live Captions

A real-time offline speech-to-text overlay app for Android. Captions appear as a floating bar over any screen — no internet required, nothing leaves your device.

Built with Kotlin, Jetpack Compose, Vosk, and a three-window WindowManager overlay architecture that mirrors the feature set of the companion Go/Wails desktop application.

---

## Features

- **Live captioning** — real-time speech-to-text using Vosk offline models, no cloud dependency
- **Transparent overlay** — floats over any app, draggable, always on top
- **10 languages** — English, Spanish, French, German, Italian, Portuguese, Russian, Chinese, Japanese, Hindi
- **Device audio capture** — optional MediaProjection mode captures playback from other apps (where permitted by the OS)
- **Transcript history** — scrollable log of everything transcribed this session
- **Recording** — start/stop recording with 60-second auto-save to a temp file, final save to Documents/Transcriptions
- **File transcription** — pick an audio file and transcribe it offline
- **Draggable UI** — caption bar and settings pill are independently draggable
- **No data collection** — all processing is on-device

---

## Screenshots
<img width="480" height="1066" alt="1000003752" src="https://github.com/user-attachments/assets/ccdce742-5494-4a98-8b0f-a1690cf56387" />
<img width="480" height="1066" alt="1000003751" src="https://github.com/user-attachments/assets/2a34bed0-cc98-41be-a4e3-44611c17199e" />
<img width="480" height="1066" alt="1000003750" src="https://github.com/user-attachments/assets/2f34fed3-8a18-4996-8d08-124939fc3301" />

---

## Architecture

Three independent `WindowManager` overlay windows:

| Window | Size | Purpose |
|---|---|---|
| Caption bar | `MATCH_PARENT × WRAP_CONTENT` | Live caption text, level meter, controls |
| Settings pill | `WRAP_CONTENT × WRAP_CONTENT` | Hamburger tab docked to right edge, draggable |
| Settings drawer | `MATCH_PARENT × MATCH_PARENT` | Full-height panel, `FLAG_NOT_TOUCHABLE` when closed |

Audio pipeline: `AudioRecord` → `AudioDSP` noise gate → `Channel` → `VoskEngine.acceptChunkSync` → `CaptionViewModel` → `StateFlow` → Compose UI

The read loop and inference loop are decoupled via a `Channel<FloatArray>` so `AudioRecord.read()` is never blocked by Vosk processing.

---

## Requirements

- Android 10 (API 29) or higher for device audio capture
- Android 8 (API 26) or higher for the overlay service
- Microphone permission
- "Display over other apps" permission (granted manually in Settings)
- Internet on first launch only (to download the ~50MB language model)

---

## Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Microphone capture |
| `SYSTEM_ALERT_WINDOW` | Draw the overlay over other apps |
| `FOREGROUND_SERVICE` | Keep the overlay alive |
| `FOREGROUND_SERVICE_MICROPHONE` | Foreground service mic access (API 34+) |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Foreground service device audio (API 34+) |
| `INTERNET` | Download Vosk language model on first run |
| `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE` | File transcription |

---

## Building

```bash
git clone https://github.com/YOUR_USERNAME/live-captions-android.git
cd live-captions-android
```

Open in Android Studio (Hedgehog or newer), let Gradle sync, then run on a physical device. The emulator microphone is typically muted by default and will produce no captions.

### First launch

1. Grant microphone permission
2. Grant "Display over other apps" in the Settings screen that opens automatically
3. The app downloads the English model (~50MB) on first run
4. The overlay appears once the model is ready

---

## Device audio capture

Tapping the speaker icon switches from microphone to device audio via `AudioPlaybackCapture`. This works for apps that allow capture (many media players, browsers, games). Apps that opt out — Netflix, Spotify, phone calls, most DRM content — will produce silence; the app shows a message explaining this after 6 seconds.

This is an OS-enforced privacy boundary. It cannot be worked around by a third-party app.

---

## Language models

Models are downloaded from [Alphacephei](https://alphacephei.com/vosk/models) on first use and stored in the app's private files directory. No storage permission is required for this. Models are not deleted when the app is updated.

| Language | Model | Size |
|---|---|---|
| English | vosk-model-small-en-us-0.15 | ~40MB |
| Spanish | vosk-model-small-es-0.42 | ~39MB |
| French | vosk-model-small-fr-0.22 | ~41MB |
| German | vosk-model-small-de-0.15 | ~45MB |
| Italian | vosk-model-small-it-0.22 | ~48MB |
| Portuguese | vosk-model-small-pt-0.3 | ~31MB |
| Russian | vosk-model-small-ru-0.22 | ~45MB |
| Chinese | vosk-model-small-cn-0.22 | ~42MB |
| Japanese | vosk-model-small-ja-0.22 | ~48MB |
| Hindi | vosk-model-small-hi-0.22 | ~42MB |

---

## Desktop companion

The desktop version of this app is built with Go and Wails and shares the same feature set. See [live-captions-desktop](https://github.com/YOUR_USERNAME/live-captions-desktop).

---

## Tech stack

- Kotlin + Jetpack Compose
- [Vosk](https://github.com/alphacep/vosk-android-demo) — offline speech recognition
- WindowManager overlay (three independent windows)
- Kotlin Coroutines + StateFlow
- MediaProjection + AudioPlaybackCapture (device audio)
- Android foreground service with microphone + mediaProjection types

---

## License

MIT
