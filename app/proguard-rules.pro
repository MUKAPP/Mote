-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class com.mukapp.mote.data.model.** { *; }
-keep class com.mukapp.mote.tools.LocalAiTools { *; }
-keep class com.mukapp.mote.tools.ShellProcessManager { *; }
-keep class com.mukapp.mote.ui.markdown.MarkdownGrammarLocator { *; }
-keep class com.mukapp.mote.ui.markdown.prism.** { *; }
-keep class io.ratex.** { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

-keepclassmembers class * {
    public <methods>;
}

-dontwarn org.json.**
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.ParametersAreNonnullByDefault
-dontwarn javax.annotation.WillClose

-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
