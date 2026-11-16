# Started by name through app_process (CLASSPATH=<apk> app_process ... app.retroidsettings.lineage.RootHelper)
-keep class app.retroidsettings.lineage.RootHelper { public static void main(java.lang.String[]); }
# ADB client, TLS pairing and certificate code use reflection / native code
-keep class io.github.muntashirakon.** { *; }
-keep class android.sun.** { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class org.conscrypt.** { *; }
-dontwarn **
