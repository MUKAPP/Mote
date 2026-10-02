package com.mukapp.mote.tools

import java.io.Reader

internal class ShellOutputBuffer {
    private var storage: CharArray? = null
    private var start = 0
    private var size = 0

    fun readFrom(reader: Reader) {
        val chars = CharArray(4_096)
        while (true) {
            val count = reader.read(chars, 0, chars.size)
            if (count < 0) return
            if (count > 0) append(chars, count)
        }
    }

    private fun append(chars: CharArray, count: Int) = synchronized(this) {
        val buffer = storage ?: CharArray(Capacity).also { storage = it }
        val writeIndex = (start + size) % Capacity
        val firstCount = minOf(count, Capacity - writeIndex)
        chars.copyInto(buffer, writeIndex, 0, firstCount)
        if (firstCount < count) {
            chars.copyInto(buffer, 0, firstCount, count)
        }

        val overflow = maxOf(0, size + count - Capacity)
        start = (start + overflow) % Capacity
        size = minOf(Capacity, size + count)
    }

    fun snapshot(): String = synchronized(this) {
        val buffer = storage ?: return@synchronized ""
        if (start + size <= Capacity) {
            String(buffer, start, size)
        } else {
            val snapshot = CharArray(size)
            val firstCount = Capacity - start
            buffer.copyInto(snapshot, 0, start, Capacity)
            buffer.copyInto(snapshot, firstCount, 0, size - firstCount)
            String(snapshot)
        }
    }

    private companion object {
        const val Capacity = 65_536
    }
}
