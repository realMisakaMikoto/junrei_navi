# Navigation SDK 7.8.0 requires these optimizations to remain disabled.
-optimizations !class/merging/horizontal
-optimizations !class/merging/vertical

# The SDK registry reflectively creates package-private implementations. Keep
# the reflector in its original package so R8 cannot break package access.
-keep class com.google.android.libraries.navigation.internal.als.ax { *; }

# The Navigation SDK map runtime loads this implementation through reflection.
# R8 must retain both the class name and its public zero-argument constructor.
-keepclassmembers class com.google.android.gms.maps.internal.CreatorImpl {
    public <init>();
}

# The Navigation SDK map renderer creates shader programs with
# Class.newInstance(). Its embedded rules allow mapcore members to be shrunk,
# so retain the zero-argument constructors reached only through reflection.
-keepclasseswithmembers class * extends com.google.android.libraries.geo.mapcore.renderer.ej {
    <init>();
}
# AMap's native renderer resolves Java classes, callbacks and fields by name.
# Follow the vendor's 3D map (5.0+) and bundled location/search SDK rules:
# https://lbs.amap.com/api/android-sdk/guide/create-project/dev-attention
-keep class com.amap.api.maps.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.amap.api.trace.** { *; }
-keep class com.amap.api.location.** { *; }
-keep class com.amap.api.fence.** { *; }
-keep class com.amap.api.services.** { *; }

# The pinned combined JAR references these absent optional GNSS/APS helpers.
# This app uses AndroidLocationProvider, not AMap location/soft-RTK APIs.
# Keep the exclusions exact; enabling those APIs requires their dependencies.
-dontwarn com.amap.ams.gnss.GnssSoftLocator
-dontwarn net.jafama.FastMath
