package com.mukapp.mote.ui.markdown

sealed class MdBlock {
    abstract val startOffset: Int
    abstract val endOffset: Int

    data class Heading(
        val level: Int,
        val text: String,
        override val startOffset: Int,
        override val endOffset: Int
    ) : MdBlock()

    data class CodeBlock(
        val language: String,
        val code: String,
        val closed: Boolean,
        override val startOffset: Int,
        override val endOffset: Int
    ) : MdBlock()

    data class UnorderedList(
        val items: List<List<MdBlock>>,
        override val startOffset: Int,
        override val endOffset: Int
    ) : MdBlock()

    data class OrderedList(
        val items: List<List<MdBlock>>,
        override val startOffset: Int,
        override val endOffset: Int
    ) : MdBlock()

    data class TaskList(
        val items: List<Pair<TaskItem, List<MdBlock>>>,
        override val startOffset: Int,
        override val endOffset: Int
    ) : MdBlock()

    data class Blockquote(
        val children: List<MdBlock>,
        override val startOffset: Int,
        override val endOffset: Int
    ) : MdBlock()

    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
        override val startOffset: Int,
        override val endOffset: Int
    ) : MdBlock()

    data class Paragraph(
        val text: String,
        override val startOffset: Int,
        override val endOffset: Int
    ) : MdBlock()

    data class HorizontalRule(
        override val startOffset: Int,
        override val endOffset: Int
    ) : MdBlock()
}

data class TaskItem(val checked: Boolean, val text: String)

sealed class InlineElement {
    data class Text(val content: String) : InlineElement()
    data class Bold(val children: List<InlineElement>) : InlineElement()
    data class Italic(val children: List<InlineElement>) : InlineElement()
    data class Strikethrough(val children: List<InlineElement>) : InlineElement()
    data class Superscript(val children: List<InlineElement>) : InlineElement()
    data class Subscript(val children: List<InlineElement>) : InlineElement()
    data class InlineCode(val content: String) : InlineElement()
    data class Link(val text: String, val url: String) : InlineElement()
    data class AutoLink(val url: String) : InlineElement()
}
