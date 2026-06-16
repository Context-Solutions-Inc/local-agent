# Default Android proguard rules.
# Project-specific rules added as we discover them in M5/M6.

-keep class com.contextsolutions.localagent.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
