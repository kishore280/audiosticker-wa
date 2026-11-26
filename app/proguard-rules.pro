# Go engine (gomobile): the native code calls these Java classes and methods by name through JNI.
-keep class go.** { *; }
-keep class com.kish.wastickers.waengine.** { *; }
-keep interface com.kish.wastickers.waengine.** { *; }
# The content provider is read by WhatsApp by class name (declared in the manifest, kept by default).
