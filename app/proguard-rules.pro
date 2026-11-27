-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.mbta.app.model.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
