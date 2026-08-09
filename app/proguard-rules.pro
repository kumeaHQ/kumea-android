# Kumea ProGuard / R8 rules.
#
# Release builds aren't a Sprint 0 deliverable, but these rules live here from
# day one. Catching shrinker bugs once minify is turned on (Sprint 1+) is much
# cheaper than debugging a stripped release-build crash later.

# ---------- Retrofit ----------
# Retrofit ships its own consumer rules from 2.x onwards. We add a few belt-and-braces
# entries to cover the kotlinx-serialization converter path which Retrofit's bundled
# rules don't fully address.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# Retain service method parameter types (reflection-based at request build time).
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Ignore Retrofit's annotation processor warning noise.
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# ---------- OkHttp ----------
# OkHttp 4 ships its own consumer rules. These are pure defense in depth for
# the rare case the consumer rule jar isn't picked up.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------- kotlinx-serialization ----------
# Mirror of https://github.com/Kotlin/kotlinx.serialization/blob/master/rules/common.pro
# Reproduced here (not fetched at build time) so the rules don't drift if the upstream URL changes.

# Keep `Companion` object fields of serializable classes.
# Synthetic for class members, see https://github.com/Kotlin/kotlinx.serialization/issues/1900
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both directly registered & via @Serializable) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# @Serializable and @Polymorphic are used at runtime for polymorphic serialization.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Don't warn about kotlinx.serialization runtime classes that R8 can't see in some configurations.
-dontwarn kotlinx.serialization.**

# ---------- Hilt / Dagger ----------
# Hilt ships its own consumer rules. These keep the warning surface quiet and
# defend against edge cases where generated code names get stripped.
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ApplicationComponentManager { *; }
-keep,allowobfuscation @interface dagger.hilt.android.AndroidEntryPoint
-keep,allowobfuscation @interface dagger.hilt.android.HiltAndroidApp
-keep,allowobfuscation @interface dagger.hilt.android.lifecycle.HiltViewModel
-dontwarn com.google.errorprone.annotations.**
-dontwarn dagger.internal.**

# ---------- Room ----------
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }
-dontwarn androidx.room.paging.**

# ---------- DataStore ----------
# DataStore Preferences uses reflection sparingly; the consumer rules in the AAR
# cover it. Belt-and-braces below for the Kotlin metadata it needs.
-keep class androidx.datastore.*.** { *; }

# ---------- Kotlin coroutines ----------
# Avoid R8 stripping classes referenced only via service loader.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
