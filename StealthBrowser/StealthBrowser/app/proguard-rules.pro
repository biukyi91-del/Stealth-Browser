# Keep Hilt-generated and reflection-dependent classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# Extension manifest model classes are deserialized from untrusted JSON
-keep class com.stealth.browser.extensions.model.** { *; }

# Room
-keep class androidx.room.RoomDatabase { *; }
