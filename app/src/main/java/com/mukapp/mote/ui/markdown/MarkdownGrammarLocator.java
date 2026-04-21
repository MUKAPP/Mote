package com.mukapp.mote.ui.markdown;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.mukapp.mote.ui.markdown.prism.Prism_c;
import com.mukapp.mote.ui.markdown.prism.Prism_clike;
import com.mukapp.mote.ui.markdown.prism.Prism_cpp;
import com.mukapp.mote.ui.markdown.prism.Prism_java;
import com.mukapp.mote.ui.markdown.prism.Prism_javascript;
import com.mukapp.mote.ui.markdown.prism.Prism_json;
import com.mukapp.mote.ui.markdown.prism.Prism_kotlin;
import com.mukapp.mote.ui.markdown.prism.Prism_markup;
import com.mukapp.mote.ui.markdown.prism.Prism_python;
import com.mukapp.mote.ui.markdown.prism.Prism_sql;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.noties.prism4j.GrammarLocator;
import io.noties.prism4j.Prism4j;

public class MarkdownGrammarLocator implements GrammarLocator {

    private static final Set<String> LANGUAGES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "clike",
            "c",
            "cpp",
            "java",
            "javascript",
            "js",
            "json",
            "jsonp",
            "kotlin",
            "markup",
            "xml",
            "html",
            "mathml",
            "svg",
            "python",
            "sql"
    )));

    @Nullable
    @Override
    public Prism4j.Grammar grammar(@NonNull Prism4j prism4j, @NonNull String language) {
        switch (language) {
            case "clike":
                return Prism_clike.create(prism4j);
            case "c":
                return Prism_c.create(prism4j);
            case "cpp":
            case "c++":
                return Prism_cpp.create(prism4j);
            case "java":
                return Prism_java.create(prism4j);
            case "javascript":
            case "js":
                return Prism_javascript.create(prism4j);
            case "json":
            case "jsonp":
                return Prism_json.create(prism4j);
            case "kotlin":
                return Prism_kotlin.create(prism4j);
            case "markup":
            case "xml":
            case "html":
            case "mathml":
            case "svg":
                return Prism_markup.create(prism4j);
            case "python":
                return Prism_python.create(prism4j);
            case "sql":
                return Prism_sql.create(prism4j);
            default:
                return null;
        }
    }

    @NonNull
    @Override
    public Set<String> languages() {
        return LANGUAGES;
    }
}
