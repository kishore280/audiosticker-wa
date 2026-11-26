# Audio Sticker Maker (Android, Kotlin + Jetpack Compose)

An Android app that makes WhatsApp stickers:

1. **Still sticker packs**: you select images. The app converts each one to a 512×512 WebP (less than 100 KB) and makes the 96×96 tray icon. You push **Add to WhatsApp**.
2. **Animated sticker packs**: you select videos or GIFs. The app converts the first 8 s of each one to an animated WebP (less than 500 KB). You push **Add to WhatsApp**.
3. **Audio stickers** (stickers with sound). You can also **share** a video, GIF, image or sound from another app to "Make audio sticker": you select a video, GIF or image, select a sound and trim it. The app makes the sticker on the phone and sends it to any chat. Then you can forward it from the normal WhatsApp app.

## How it works

### Sticker packs (1 and 2): the official WhatsApp sticker contract
- `sticker/StickerContentProvider.kt` is a port of the provider in the WhatsApp sample app (`metadata`, `stickers`, `stickers_asset`). It reads packs from the app's files directory, so users can make packs in the app.
- `sticker/WhatsApp.kt` contains the `ENABLE_STICKER_PACK` intent and the "is the pack added" check.
- `sticker/AnimatedWebp.kt` extracts frames with `MediaMetadataRetriever` (video) or `Movie` (GIF), encodes each frame with `Bitmap.compress(WEBP)`, and puts the frames in one animated WebP with a small muxer. It uses no native libraries.
- WhatsApp does not accept mixed packs. Thus you select "Animated pack" when you make a pack.

### Audio stickers (3): own WhatsApp engine (whatsmeow), no browser
WhatsApp has **no public API** for sound stickers, and the sticker-pack contract has no audio field. A sound sticker is one message:

```
Message.audioStickerMessage (134) → FutureProofMessage.message → Message.stickerMessage
  { WebP media, isAnimated, premium = 0, emojis, audioMessage { Ogg/Opus media, ptt = false, seconds } }
```
The WebP's EXIF (TIFF tag `0x5741`) holds JSON with `"sound-sha256"` = base64 SHA-256 of the Ogg file.

How the app makes and sends it:
- **Engine:** `engine/` is a small Go package over [whatsmeow](https://github.com/tulir/whatsmeow) (MPL-2.0), the same library that [wacli](https://github.com/openclaw/wacli) uses. `engine/build.sh` builds it with gomobile into `app/libs/waengine.aar`, and Kotlin wraps it in `audio/StickerEngine.kt`. The app is a **linked device** of your WhatsApp. You link it one time with a phone code: WhatsApp shows a notification on the same phone, and you type the code there.
- **whatsmeow patch:** whatsmeow does not unwrap `audioStickerMessage` when it sets the stanza type. `engine/patch_whatsmeow.py` adds the same unwrap that whatsmeow already does for `lottieStickerMessage`. `build.sh` applies it to a copy of the module.
- **Sound:** `audio/AudioPipeline.kt` decodes the selected part to mono 48 kHz PCM with MediaCodec and encodes it to Opus with the MediaCodec Opus encoder (Android 10+). `audio/OggOpus.kt` writes the Ogg file.
- **Picture:** `sticker/AnimatedWebp.kt` makes frames, encodes the animated WebP (less than 950 KB), and adds the EXIF block.
- **Tests:** `OggOpusTest` and `AnimatedWebpTest`. With real Opus packets and WebP frames from Chromium, the Ogg file decodes to the exact length, and the animated WebP decodes to the correct frames with the EXIF block.

**Risks:**
- whatsmeow is a third-party client. WhatsApp can ban accounts that use third-party clients. Send stickers by hand only, never in bulk, and test with a second number first.
- If WhatsApp changes the protocol, update whatsmeow (`go get go.mau.fi/whatsmeow@main` in `engine/`) and run `engine/build.sh` again.

## Build

Requirements: JDK 17+ and the Android SDK (compileSdk 36). The engine AAR is already in `app/libs`.

```bash
./gradlew assembleDebug
```

To build the engine again (needs Go 1.26+, the Android NDK and python3):

```bash
engine/build.sh
```

The APK is in `app/build/outputs/apk/debug/`. For a small APK (about 8 MB: R8 shrinking, arm64 only, compressed engine library):

```bash
./gradlew assembleRelease
```

The release build is signed with the debug key so you can side-load it. Use your own key before you publish. Change `applicationId` in `app/build.gradle.kts` before you publish the app. Do not use "WhatsApp" in the app name.
