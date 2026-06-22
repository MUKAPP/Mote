package com.mukapp.mote.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatViewModelTitleTest {

    @Test
    fun normalizeTitleTruncatesLongGeneratedTitleLocally() {
        val title = ConversationTitleFormatter.normalize("abcdefghijklmnopqrstuvwxy")

        assertEquals("abcdefghijklmnopqrstuvwx...", title)
    }

    @Test
    fun normalizeTitleTrimsWrapperPunctuationAndWhitespace() {
        val title = ConversationTitleFormatter.normalize("\n  “旅行计划。”  \t")

        assertEquals("旅行计划", title)
        assertTrue(title.length <= ConversationTitleFormatter.MaxLength)
    }
}
