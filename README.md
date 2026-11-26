<h1 align="center">Sticker Studio</h1>

<p align="center">
  <b>Make WhatsApp stickers with sound on your Android phone.</b><br>
  Video, GIF or photo + any sound → a sticker that plays its sound when someone taps it.<br>
  Also makes normal and animated sticker packs. Free and open source.
</p>

<p align="center">
  <a href="https://github.com/kishore280/audiosticker-wa/releases/latest"><img alt="Download" src="https://img.shields.io/github/v/release/kishore280/audiosticker-wa?label=Download%20APK&style=for-the-badge&color=00a884"></a>
  <img alt="Android 7+" src="https://img.shields.io/badge/Android-7%2B-3ddc84?style=for-the-badge&logo=android&logoColor=white">
  <a href="LICENSE"><img alt="MIT" src="https://img.shields.io/badge/license-MIT-blue?style=for-the-badge"></a>
</p>

<!-- Screenshots: put PNGs in docs/screenshots/ and list them here, for example:
<p align="center"><img src="docs/screenshots/home.png" width="230"> <img src="docs/screenshots/maker.png" width="230"> <img src="docs/screenshots/pack.png" width="230"></p>
-->

> Not affiliated with WhatsApp or Meta. "WhatsApp" is a trademark of WhatsApp LLC.

---

## Features

### 🔊 Audio stickers (stickers with sound)
- **Video** with its own sound, or with another sound file.
- **GIF + sound** or **image + sound**.
- **Square preview = the real sticker.** *Fit* keeps the whole picture; *Fill* makes a square that you **drag** to choose the part.
- **Trim** with two handles, and play only the selected part.
- Reads **4K, HEVC, HDR and rotated** phone videos, and large or HEIC photos.
- **Send** to any chat. One tap for *Me* and your recent chats.
- **My audio stickers:** every sticker that you make is saved. Hear it, send it again, export it (`.webp` + `.ogg`), or delete it.
- Your **pack name and publisher** show in the sticker info in WhatsApp.
- **Share** a video, GIF, image or sound from any app → *Make audio sticker*.

### 🗂️ Sticker packs (no link needed)
- **Still packs** from photos, and **animated packs** from videos and GIFs (trim, Fit / Fill, move).
- Numbered slots 1–30, tray icon, 1–3 emojis per sticker.
- **Add to WhatsApp**, then **Add changes to WhatsApp** after you edit the pack.
- Works with WhatsApp and WhatsApp Business.

### ⚙️ App
- **Check for updates** from GitHub Releases, in the app.
- Small: about **8 MB**. No ads, no tracking, no account, no server.

---

## Install

1. Download **`StickerStudio-x.y.z.apk`** from the [latest release](https://github.com/kishore280/audiosticker-wa/releases/latest) on your phone.
2. Open it. If Android asks, allow *Install unknown apps* for your browser or file manager.
3. Open **Sticker Studio**.

Requirements: Android 7 or newer, a 64-bit phone (almost all phones since 2017).
Audio stickers need **Android 10 or newer** (for the Opus audio encoder). Sticker packs work on Android 7+.

To check the file, compare its SHA-256 with the `.sha256` file in the release.

---

## How to make an audio sticker

1. Tap **Make an audio sticker**.
2. Select **Video**, **GIF** or **Image**, and then the file.
3. For a GIF or an image, select a sound. For a video, its own sound is used (or tap *Use other sound*).
4. Select **Fit** or **Fill** (drag the picture), and drag the trim handles. 10 seconds or less works best.
5. Tap **Create sticker**. Tap the sticker to hear it.
6. Tap **Me** or a chat. Open WhatsApp: the sticker plays its sound, and you can forward it to any chat.

**First time only: link WhatsApp.** Type your phone number with the country code and tap *Get link code*. In WhatsApp, open **Settings → Linked devices → Link a device → Link with phone number instead**, and type the code. Keep Sticker Studio open in the background while you do this (it shows a small notification).

---

## How it works (and why the link is necessary)

WhatsApp's public sticker API for Android (the one sticker-pack apps use) **has no audio**. A sticker with sound is a special message: an animated WebP plus an Ogg/Opus sound in one `StickerMessage`. Only a WhatsApp client can send it.

So Sticker Studio does two things:

| Part | How |
|---|---|
| **Sticker packs** | The official WhatsApp sticker-pack contract (a `ContentProvider` and the `ENABLE_STICKER_PACK` intent). No link. |
| **Make the audio sticker** | On the phone: the video is decoded with `MediaCodec` to 512 px frames, the frames go into an animated WebP (less than 950 KB) with WhatsApp's sticker info (EXIF, including `sound-sha256`), and the sound goes to Opus/Ogg. |
| **Send the audio sticker** | The app is a **linked device** of your WhatsApp, like WhatsApp Web, through [whatsmeow](https://github.com/tulir/whatsmeow). It sends `audioStickerMessage → stickerMessage { audioMessage }`, the same layout that WhatsApp clients send. |

### Privacy
- All conversion happens on your phone. There is no server.
- The link session is kept only in the app's private storage. *Settings → Unlink* removes it, and so does WhatsApp → Linked devices.
- The app reads your chat list only to show the *Send to* list. It does not read, store or upload your messages.
- The only other network request is the update check to `api.github.com`.

### ⚠️ Important: risk
The linked-device connection is **not an official WhatsApp API**. WhatsApp can limit or ban accounts that use unofficial clients. Use audio stickers by hand, for your own chats, **never for bulk or automated messages**. If you can, test with a second number first. Sticker packs use the official API and do not have this risk.

---

## FAQ

**Is it on Google Play?** Not now. Download the APK from Releases. The in-app update check tells you when a new version is available.

**Does it work with WhatsApp Business?** Sticker packs: yes. Audio stickers: link the Business number with the same steps.

**"Logging in…" does not stop when I link.** Keep Sticker Studio running (do not swipe it away) while you type the code. Then tap *New code* and try again.

**A video gives an error.** The message tells you the codec and the size. Videos with DRM (for example streaming downloads) cannot be read. Convert the video to H.264 MP4 and try again.

**Where are my stickers?** In the app (*My audio stickers*). *Export* copies them to `Downloads/AudioStickers`.

---

## Build from source

Requirements: JDK 17, Android SDK (compileSdk 36). The WhatsApp engine (`app/libs/waengine.aar`) is already built.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit tests
./gradlew assembleRelease      # small APK; signed with RELEASE_* env vars, else with the debug key
```

Rebuild the Go engine (Go 1.26+, Android NDK, python3):

```bash
engine/build.sh
```

### Project structure

```
app/src/main/java/com/kish/wastickers/
  audio/     audio stickers: maker screen, MediaCodec audio → Opus/Ogg, library, engine wrapper
  sticker/   sticker packs: ContentProvider, WebP encoder and animated-WebP muxer, video frame reader
  settings/  sticker names, update check, settings sheet
  ui/        home screen, pack screen, clip editor
engine/      Go package over whatsmeow (link by phone code, chats, send sound sticker) → gomobile AAR
```

### Release a new version

1. Change `appVersion` in `app/build.gradle.kts` (for example `1.1.0`) and add a `## [1.1.0]` section to `CHANGELOG.md`.
2. Commit, then push a tag: `git tag v1.1.0 && git push origin v1.1.0`.
   (Or *Actions → Release → Run workflow*.)
3. GitHub Actions builds, signs and publishes the release with the APK. The app's update check finds it.

The release workflow needs 4 repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Always use the same key: Android installs an update only when the signing key is the same.

---

## Credits

- [whatsmeow](https://github.com/tulir/whatsmeow) (MPL-2.0) — WhatsApp multi-device protocol in Go.
- [wacli](https://github.com/openclaw/wacli), [KaraBala10/whatsapp-audio-stickers](https://github.com/KaraBala10/whatsapp-audio-stickers) and [alkhrzmy/wa-sound-sticker](https://github.com/alkhrzmy/wa-sound-sticker) — research on the sound-sticker format.
- The [WhatsApp sticker sample app](https://github.com/WhatsApp/stickers) — sticker-pack contract.

## License

[MIT](LICENSE). Third-party licenses are listed at the end of the LICENSE file.
