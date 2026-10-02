package com.mukapp.mote.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.io.PipedReader
import java.io.PipedWriter
import java.io.Reader
import java.io.StringReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ShellOutputBufferTest {
    @Test
    fun emptyReaderHasEmptySnapshot() {
        val buffer = ShellOutputBuffer()
        assertEquals("", buffer.snapshot())
        buffer.readFrom(StringReader(""))
        assertEquals("", buffer.snapshot())
    }

    @Test
    fun mixedLineEndingsAndUnterminatedLineArePreserved() {
        val text = "第一\r\n第二\n第三\r末行"
        val buffer = ShellOutputBuffer()
        buffer.readFrom(StringReader(text))
        assertEquals(text, buffer.snapshot())
    }

    @Test
    fun finalNewlineIsPreservedWithoutAddingAnother() {
        val buffer = ShellOutputBuffer()
        buffer.readFrom(StringReader("x\n"))
        assertEquals("x\n", buffer.snapshot())
    }

    @Test
    fun twoMiBUnterminatedOutputKeepsExactTailAcrossFurtherReads() {
        val buffer = ShellOutputBuffer()
        buffer.readFrom(RepeatedCharacterReader('x', 2_097_152, "TAIL-END"))
        var expected = "x".repeat(65_536 - "TAIL-END".length) + "TAIL-END"
        assertEquals(expected, buffer.snapshot())

        repeat(4) { round ->
            val character = ('a'.code + round).toChar()
            val count = 7_777 + round * 1_111
            val suffix = "END-$round"
            buffer.readFrom(RepeatedCharacterReader(character, count, suffix))
            expected = (expected + character.toString().repeat(count) + suffix).takeLast(65_536)
            assertEquals(expected, buffer.snapshot())
        }

        buffer.readFrom(RepeatedCharacterReader('z', 90_000, "LAST"))
        assertEquals("z".repeat(65_536 - 4) + "LAST", buffer.snapshot())
    }

    @Test
    fun exactCapacityAndFirstOverflowKeepCharacterOrder() {
        val buffer = ShellOutputBuffer()
        buffer.readFrom(RepeatedCharacterReader('x', 65_536, ""))
        assertEquals("x".repeat(65_536), buffer.snapshot())
        buffer.readFrom(StringReader("中😀\r\n"))
        assertEquals("x".repeat(65_536 - "中😀\r\n".length) + "中😀\r\n", buffer.snapshot())
    }

    @Test
    fun utf8ChineseAndEmojiAcrossReadBoundariesArePreserved() {
        val text = "x".repeat(4_095) + "😀中文\r\n" + "界".repeat(4_092) + "🙂末尾"
        val buffer = ShellOutputBuffer()
        InputStreamReader(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), Charsets.UTF_8).use {
            buffer.readFrom(it)
        }
        assertEquals(text, buffer.snapshot())
    }

    @Test
    fun unclosedPipePublishesUnterminatedOutputBeforeEof() {
        val buffer = ShellOutputBuffer()
        val reader = PipedReader(16_384)
        val writer = PipedWriter(reader)
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "ShellOutputBufferTest-reader").apply { isDaemon = true }
        }
        val reading = executor.submit { buffer.readFrom(reader) }
        try {
            val text = "没有换行😀".repeat(1_000)
            writer.write(text)
            writer.flush()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var snapshot = buffer.snapshot()
            while (snapshot != text && System.nanoTime() < deadline) {
                Thread.sleep(5)
                snapshot = buffer.snapshot()
            }
            assertEquals(text, snapshot)
            assertFalse("读取任务应仍在等待未关闭的管道，而非 EOF", reading.isDone)

            writer.close()
            reading.get(5, TimeUnit.SECONDS)
            assertEquals(text, buffer.snapshot())
        } finally {
            writer.close()
            reader.close()
            reading.cancel(true)
            executor.shutdownNow()
        }
    }

    private class RepeatedCharacterReader(
        private val character: Char,
        private val repeatedCount: Int,
        suffix: String
    ) : Reader() {
        private val suffix = suffix.toCharArray()
        private var position = 0

        override fun read(chars: CharArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position == repeatedCount + suffix.size) return -1
            val repeated = minOf(length, maxOf(0, repeatedCount - position))
            chars.fill(character, offset, offset + repeated)
            position += repeated
            val suffixCount = minOf(length - repeated, suffix.size - maxOf(0, position - repeatedCount))
            if (suffixCount > 0) {
                val suffixStart = position - repeatedCount
                suffix.copyInto(chars, offset + repeated, suffixStart, suffixStart + suffixCount)
                position += suffixCount
            }
            return repeated + suffixCount
        }

        override fun close() = Unit
    }
}
