# Changelog

All notable changes are listed here. Versions follow [Semantic Versioning](https://semver.org).

## [1.0.2] - 2026-11-26

### Fixed
- Sticker Studio did not show in the share list when an app shares media as a generic file
  ("application/octet-stream"), as many messaging and file apps do.
- The app now finds the real type of a file from its name or its first bytes (MP4/MOV, WebM/MKV,
  GIF, PNG, JPEG, WebP, HEIC, MP3, M4A, Ogg/Opus, WAV, FLAC, AMR), for shared and picked files.
- A file that is not a video, GIF, image or sound shows a clear message.

## [1.0.1] - 2026-11-26

### Changed
- Settings is now a full screen (Sticker info, WhatsApp, Updates, About), not a bottom sheet.
- The phone's Back gesture in Settings also saves the pack name and publisher.

### Added
- About section: made by Kish, links to GitHub, the source code and problem reports.

## [1.0.0] - 2026-11-26

First public release.

### Audio stickers (stickers with sound)
- Make a sticker with sound from a **video** (its own sound or another sound), a **GIF + sound**, or an **image + sound**.
- Square preview that looks like the sticker: **Fit** (whole picture) or **Fill** with a square that you can drag.
- Trim with a range slider and play only the selected part.
- Works with 4K, HEVC, HDR and rotated videos, and with large or HEIC photos.
- Sends to any chat (one tap for "Me" and recent chats) through a one-time linked-device connection (link with a phone code).
- **My audio stickers** library: every sticker you make is saved; hear it, send it again, export it, delete it.
- Pack name and publisher (Settings) are written in the sticker info.
- Share a video, GIF, image or sound from another app: choose *Audio sticker*, a pack, or *New pack*.

### Sticker packs (official WhatsApp sticker-pack API, no link needed)
- Still packs from images, and animated packs from videos and GIFs (clip editor: trim, Fit / Fill, move).
- Numbered slots 1–30, tray icon, 1–3 emojis per sticker.
- **Add to WhatsApp** and **Add changes to WhatsApp**; asks WhatsApp or WhatsApp Business when both are installed.

### App
- New app icon (adaptive, themed on Android 13+).
- Check for updates from GitHub Releases.
- 8 MB APK (arm64).
