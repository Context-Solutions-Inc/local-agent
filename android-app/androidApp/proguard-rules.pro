# Default Android proguard rules.
# Project-specific rules added as we discover them in M5/M6.

-keep class com.contextsolutions.localagent.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# play-services-tflite-gpu's GpuDelegate references org.tensorflow.lite.Delegate,
# but org.tensorflow:tensorflow-lite-api is excluded from all Android configs so
# litert's bundled org.tensorflow.lite.* stays canonical (hard invariant #18).
# This GPU-delegate path is dead at runtime (classifier/embedder use litert CPU
# XNNPACK; LiteRT-LM GPU uses play-services' own delegate), so suppress the
# R8 missing-class warning rather than re-adding the excluded dependency.
-dontwarn org.tensorflow.lite.Delegate

# Room (used internally by WorkManager for its WorkDatabase). The generated
# *_Impl databases are instantiated reflectively via their no-arg constructor
# (RoomDatabase.getGeneratedImplementation -> getDeclaredConstructor().newInstance()).
# proguard-android-optimize.txt strips that unused-looking constructor, so launch
# crashed with NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []
# (androidx.startup.InitializationProvider -> WorkManagerInitializer). Keep the
# no-arg ctor of every RoomDatabase subclass so reflection can build it.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# LiteRT native runtimes (classifier/embedder = litert #18; Gemma LLM = litertlm).
# Their .so code reaches back into Java via JNI FindClass/GetMethodID using the
# fully-qualified class names — R8 can't see those string references, so it shrank
# LiteRtException, and CompiledModel_nativeCreateFromFile aborted the process with
#   litert_jni_common.h: Check failed: ex_class != nullptr
#   Failed to find LiteRtException class
# during classifier warm-up (before the chat screen renders). Keep both packages'
# names + members so every JNI lookup resolves. (Build-time R8 can't catch this;
# only an on-device launch does — see hard invariant #70 / #40.)
-keep class com.google.ai.edge.litert.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }

# Secure Gateway relay SDK. Its model types (com.contextsolutions.securegateway.core.auth.QrPayload,
# the link frames, auth responses) are (de)serialized by Jackson via reflection —
# constructors, Kotlin @Metadata, and polymorphic @JsonSubTypes info. R8 obfuscated
# them, so relay pairing failed at "decode qr payload" with
#   Cannot construct instance of <obfuscated> (no Creators, like default constructor,
#   exist): abstract types either need to be mapped to concrete types ...
# which the UI surfaced as "gateway unreachable" (the gateway is never even contacted).
# Keep the SDK names + members + ctors so Jackson can build them; Signature keeps the
# generic types (e.g. the QR's Map<String,String> endpoints). Same class of break as
# the litert JNI keep above (R8 can't see reflective construction) — hard invariant #70.
-keep class com.contextsolutions.securegateway.** { *; }
-keepattributes Signature

# JNA + lazysodium — the relay SDK's Crypto signs/encrypts via lazysodium, which
# calls native libsodium through JNA. JNA's native dispatcher (libjnidispatch.so)
# reaches back into Java via JNI to read com.sun.jna.Pointer's `peer` field (and
# other Structure/Library members) BY NAME; R8 renamed them, so pairing failed:
#   UnsatisfiedLinkError: Can't obtain peer field ID for class com.sun.jna.Pointer
#   -> NoClassDefFoundError: com.contextsolutions.securegateway.core.Crypto  (static init failed)
# at MobileClient.<init> / AndroidKeystoreKeyStore.loadOrCreateIdentity. These are
# JNA's documented ProGuard rules plus a keep for lazysodium's JNA bindings.
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keep class com.goterl.** { *; }
-dontwarn java.awt.**

# SQLCipher (M1 — net.zetetic:sqlcipher-android). The encrypted DB is opened through
# net.zetetic.database.sqlcipher.SupportFactory, whose native libsqlcipher.so reaches back
# into Java via JNI BY NAME (SQLiteConnection, SQLiteDatabase, native callbacks). Same class
# of break as the litert/JNA keeps above — R8 can't see JNI string lookups, so an obfuscated
# class/member SIGABRTs at first DB open (release only; debug never exercises R8). Keep the
# whole package + members. Hard invariant #70.
-keep class net.zetetic.** { *; }
-dontwarn net.zetetic.**
