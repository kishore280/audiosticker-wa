# Audio Sticker Maker (Android, Kotlin + Jetpack Compose)

An Android app that makes WhatsApp stickers:

1. **Still sticker packs**: you select images. The app converts each one to a 512×512 WebP (less than 100 KB) and makes the 96×96 tray icon. You push **Add to WhatsApp**.
2. **Animated sticker packs**: you select videos or GIFs. The app converts the first 8 s of each one to an animated WebP (less than 500 KB). You push **Add to WhatsApp**.
3. **Audio stickers** (stickers with sound): you select a video, GIF or image, select a sound and trim it. The app makes the sticker and sends it to any chat. You can then forward it from the normal WhatsApp app.

## How it works

### Sticker packs (1 and 2): the official WhatsApp sticker contract
- `sticker/StickerContentProvider.kt` is a port of the provider in the WhatsApp sample app (`metadata`, `stickers`, `stickers_asset`). It reads packs from the app's files directory, so users can make packs in the app.
- `sticker/WhatsApp.kt` contains the `ENABLE_STICKER_PACK` intent and the "is the pack added" check.
- `sticker/AnimatedWebp.kt` extracts frames with `MediaMetadataRetriever` (video) or `Movie` (GIF), encodes each frame with `Bitmap.compress(WEBP)`, and puts the frames in one animated WebP with a small muxer. It uses no native libraries.
- WhatsApp does not accept mixed packs. Thus you select "Animated pack" when you make a pack.

### Audio stickers (3): a hidden WhatsApp Web session
WhatsApp has **no public API** for sound stickers. The sticker-pack contract has no audio field. A sound sticker is an animated WebP (its EXIF carries `sound-sha256`) plus an Ogg/Opus file, linked in one `StickerMessage` (`audioMessage`). Only a WhatsApp client can send this message.

Thus the app keeps **WhatsApp Web in a hidden WebView** (`audio/WaEngine.kt`):
- You link WhatsApp **one time**. Push **Link WhatsApp**, then **Link with phone number instead**, and type the code in WhatsApp → Settings → Linked devices.
- After that, the native screen (`audio/AudioMakerScreen.kt`) sends the media to the engine through a same-origin local URL. The engine makes the sticker, and the app sends it to the chat that you select.
- The engine is [KaraBala10/whatsapp-audio-stickers](https://github.com/KaraBala10/whatsapp-audio-stickers) (MIT). It is bundled without changes in `app/src/main/assets/engine/` (see `SOURCE.md` and `LICENSE` there). `app_api.js` is the app's small bridge to it.

Steps to make the WebView work reliably:
- Desktop Windows Chrome user agent with the real WebView version, and client hints that say desktop (`UserAgentMetadata`).
- The app blocks the redirect to `www.whatsapp.com` that WhatsApp sends to phone browsers.
- The scripts are injected with `addDocumentStartJavaScript`. `onPageFinished` injects them again as a fallback. WhatsApp's CSP blocks inline `<script>` tags, but not this method.
- At startup, the app checks for WebCodecs Opus, canvas WebP, and ImageDecoder, and tells the user to update Android System WebView if one is missing.
- JS calls have a timeout and fail when the page reloads, so the UI never waits forever.

**Risks:**
- The engine uses internal WhatsApp Web modules, so a WhatsApp Web update can break sending. If this occurs, update the engine files from upstream.
- This is not an official API. Send stickers by hand only, never in bulk.
- A linked device is logged out if the phone stays offline for more than 14 days.

## Build

Requirements: JDK 17+ and the Android SDK (compileSdk 36).

```bash
./gradlew assembleDebug
```

The APK is in `app/build/outputs/apk/debug/`. Change `applicationId` in `app/build.gradle.kts` before you publish the app. Do not use "WhatsApp" in the app name.
