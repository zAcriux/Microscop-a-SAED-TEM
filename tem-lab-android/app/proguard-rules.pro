# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.temlab.**$$serializer { *; }
-keepclassmembers class com.temlab.** { *** Companion; }
-keepclasseswithmembers class com.temlab.** { kotlinx.serialization.KSerializer serializer(...); }
