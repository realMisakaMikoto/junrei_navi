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
# AMap is loaded only after the explicit privacy gate has prepared the SDK.
-keep class com.amap.api.maps.MapsInitializer { public *; }
