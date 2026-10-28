# Keep crash stack traces readable.
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# File servers. These libraries look up some of their own classes by reflection
# (MBassador's event handlers, smbj's message types, the DCE/RPC structures), so they are kept whole.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class com.rapid7.** { *; }
-keep class org.apache.commons.net.** { *; }
-keepclassmembers class * { @net.engio.mbassy.listener.Handler *; }
# Bouncy Castle: only the plain crypto classes smbj calls directly are used (no JCA provider).
-dontwarn org.bouncycastle.**
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.**
-dontwarn sun.security.**
-dontwarn java.rmi.**
-dontwarn javax.naming.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
