-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class com.mukapp.mote.data.model.** { *; }
-keep class com.mukapp.mote.tools.LocalAiTools { *; }
-keep class com.mukapp.mote.tools.ShellProcessManager { *; }

-keepclassmembers class * {
    public <methods>;
}

-dontwarn org.json.**
