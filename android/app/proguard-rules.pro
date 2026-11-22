# kotlinx.serialization — @Serializable 클래스의 serializer 보존
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.jarvis.core.network.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.jarvis.core.network.**$$serializer { *; }

# Vosk / JNA — 네이티브 라이브러리가 리플렉션으로 접근
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
