# Compose
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# Keep our app classes that are referenced from manifest
-keep class com.plwd.audiochannelguard.AudioGuardApp
-keep class com.plwd.audiochannelguard.MainActivity
-keep class com.plwd.audiochannelguard.AudioGuardService
-keep class com.plwd.audiochannelguard.AudioFixTile
-keep class com.plwd.audiochannelguard.BootReceiver
-keep class com.plwd.audiochannelguard.ServiceGuard
