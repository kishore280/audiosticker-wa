#!/usr/bin/env python3
"""Applies patches/whatsmeow-audio-sticker.patch to a whatsmeow copy (argv[1]).

whatsmeow does not unwrap Message.audioStickerMessage (field 134) when it sets the stanza
type/mediatype, so a sound sticker would go out as type "text". This adds the same unwrap
that whatsmeow already does for lottieStickerMessage. MPL-2.0: the patch is published here.
"""
import sys, pathlib
p = pathlib.Path(sys.argv[1]) / "send.go"
s = p.read_text()
for fn in ("getTypeFromMessage", "getMediaTypeFromMessage"):
    old = f"\tcase msg.LottieStickerMessage != nil:\n\t\treturn {fn}(msg.LottieStickerMessage.Message)\n"
    new = old + f"\tcase msg.AudioStickerMessage != nil:\n\t\treturn {fn}(msg.AudioStickerMessage.Message)\n"
    if new in s:
        continue
    if s.count(old) != 1:
        sys.exit(f"patch failed: pattern for {fn} not found once (whatsmeow changed?)")
    s = s.replace(old, new)
p.write_text(s)
print("whatsmeow patched")
