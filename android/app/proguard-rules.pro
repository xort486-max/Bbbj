# kotlinx.serialization — @Serializable 클래스의 serializer 보존
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.jarvis.core.network.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.jarvis.core.network.**$$serializer { *; }
