# r8 is on for speed (compose is a lot slower without it), not to hide anything: no renaming,
# so crash logs stay readable.
-dontobfuscate

# stash's own code is small; keep it whole so nothing reflective (workers, room, serialization,
# media session callbacks) can be stripped by mistake. the libraries still get optimized.
-keep class app.stash.** { *; }

# yt-dlp wrapper: jackson mappers + the python/ffmpeg unpacking
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keep class org.apache.commons.io.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.apache.commons.io.**
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**

# NewPipeExtractor: services found by reflection, rhino runs youtube's player js
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn org.jsoup.**
-dontwarn com.grack.nanojson.**
-dontwarn org.schabi.newpipe.extractor.**
