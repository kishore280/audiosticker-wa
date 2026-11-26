# Go engine (gomobile): the native code calls these Java classes and methods by name through JNI.
-keep class go.** { *; }
-keep class com.audiostickerwa.waengine.** { *; }
-keep interface com.audiostickerwa.waengine.** { *; }
# The content provider is read by WhatsApp by class name (declared in the manifest, kept by default).
