-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class com.mukapp.mote.data.model.** { *; }
-keep class com.mukapp.mote.tools.LocalAiTools { *; }
-keep class com.mukapp.mote.tools.ShellProcessManager { *; }
-keep class com.mukapp.mote.ui.markdown.MarkdownGrammarLocator { *; }
-keep class com.mukapp.mote.ui.markdown.prism.** { *; }

-keepclassmembers class * {
    public <methods>;
}

-dontwarn org.json.**
