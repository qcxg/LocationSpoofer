# Xposed / libxposed entry points are loaded outside normal Android component discovery.
-keep class de.robv.android.xposed.** { *; }
-keep interface de.robv.android.xposed.** { *; }
-keep class io.github.libxposed.** { *; }
-keep interface io.github.libxposed.** { *; }
-keep class com.shiraka.locatiobprovid.xposed.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keep class kotlinx.coroutines.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
-keep @kotlinx.serialization.Serializable class * {
    *;
}

# Runtime config and provider models are read through reflection or cross-process contracts.
-keep class com.shiraka.locatiobprovid.utils.ConfigManager { *; }
-keep class com.shiraka.locatiobprovid.utils.LSPosedManager { *; }
-keep class com.shiraka.locatiobprovid.provider.** { *; }
-keep class com.shiraka.locatiobprovid.data.** { *; }
-keepclassmembers class com.shiraka.locatiobprovid.data.** { *; }

# General safety for Android lifecycle
-keep class * extends android.app.Application { *; }
-keep class * extends android.app.Activity { *; }
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.ContentProvider { *; }
