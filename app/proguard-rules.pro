# ============================================================
#  Boykta VPN — ProGuard Rules
# ============================================================

# ---- Keep line numbers for crash reports -------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- Keep annotations -------------------------------------
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ============================================================
#  Xray / V2Ray Core Classes
# ============================================================
-keep class io.xray.** { *; }
-keep class com.xray.** { *; }
-keep class xray.** { *; }
-keep class libcore.** { *; }
-keep class go.** { *; }
-keep interface go.** { *; }
-dontwarn io.xray.**
-dontwarn com.xray.**
-dontwarn xray.**
-dontwarn libcore.**
-dontwarn go.**

# ============================================================
#  Retrofit & OkHttp
# ============================================================
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# ============================================================
#  Gson / JSON Models
# ============================================================
-keep class com.google.gson.** { *; }
-keepattributes *Annotation*
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
    @com.google.gson.annotations.Expose <fields>;
}
# Keep all data models in the vpn package
-keep class com.boykta.vpn.data.model.** { *; }
-keep class com.boykta.vpn.data.remote.dto.** { *; }
-keep class com.boykta.vpn.data.local.entity.** { *; }
-keepclassmembers class com.boykta.vpn.** {
    @com.google.gson.annotations.SerializedName *;
}

# ============================================================
#  Hilt / Dagger
# ============================================================
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.HiltAndroidApp
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }
-keep @dagger.Module class * { *; }
-keep @dagger.hilt.components.SingletonComponent class * { *; }
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
    @javax.inject.Inject <fields>;
}
-dontwarn dagger.hilt.**
-dontwarn javax.inject.**

# ============================================================
#  Kotlin
# ============================================================
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ============================================================
#  Glide
# ============================================================
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-dontwarn com.bumptech.glide.**

# ============================================================
#  AndroidX / Material Components
# ============================================================
-keep class androidx.** { *; }
-dontwarn androidx.**
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# ============================================================
#  VPN Service & Application
# ============================================================
-keep class com.boykta.vpn.App { *; }
-keep class com.boykta.vpn.service.BoykVpnService { *; }
-keep class com.boykta.vpn.ui.** { *; }

# ============================================================
#  FileProvider
# ============================================================
-keep class androidx.core.content.FileProvider { *; }

# ============================================================
#  Serialization
# ============================================================
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# ============================================================
#  Parcelable
# ============================================================
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ============================================================
#  Enum classes
# ============================================================
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ============================================================
#  Native methods
# ============================================================
-keepclasseswithmembernames class * {
    native <methods>;
}

# ============================================================
#  Suppress common warnings
# ============================================================
-dontwarn sun.misc.Unsafe
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ============================================================
# Room (local DB for .boykta imported configs)
# ============================================================
-keep class androidx.room.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Database class * { *; }

# ============================================================
# Boykta config & model classes (Gson serialization)
# ============================================================
-keep class com.boykta.vpn.config.BoykConfig { *; }
-keepclassmembers class com.boykta.vpn.config.BoykConfig { *; }
-keep class com.boykta.vpn.db.LocalServer { *; }
-keepclassmembers class com.boykta.vpn.model.** { *; }
-keepclassmembers class com.boykta.vpn.api.** { *; }
