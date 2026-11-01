# Room and Hilt ship their own consumer rules.
-keepattributes *Annotation*
# Archive libraries use reflection / service lookups; keep them intact.
-keep class net.lingala.zip4j.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.xz.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
# Network stacks (sshj, smbj, BouncyCastle, commons-net, okhttp) and PdfBox use reflection / optional deps.
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class com.tom_roush.** { *; }
-keep class org.apache.commons.net.** { *; }
-dontwarn net.schmizz.**
-dontwarn com.hierynomus.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn com.tom_roush.**
-dontwarn com.jcraft.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.el.**
-dontwarn net.engio.**
-dontwarn com.sun.jna.**
-dontwarn java.awt.**
-dontwarn javax.naming.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn com.google.errorprone.**
# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.
-dontwarn sun.security.x509.X509Key
-keep class net.i2p.crypto.eddsa.** { *; }
