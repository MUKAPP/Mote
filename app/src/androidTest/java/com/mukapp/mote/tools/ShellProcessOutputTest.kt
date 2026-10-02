package com.mukapp.mote.tools

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ShellProcessOutputTest {
    @Test
    fun twoMiBUnterminatedStdoutHasBoundedTailAndIndependentStderr() {
        val command = "chunk='${"x".repeat(4_096)}'; " +
            "i=0; while [ \"\$i\" -lt 512 ]; do printf '%s' \"\$chunk\"; i=\$((i + 1)); done; " +
            "printf '%s' 'TAIL-END'; printf '%s' 'ERROR-END' >&2"
        val id = ShellProcessManager.start(command)
        try {
            val entry = requireNotNull(ShellProcessManager.getProcess(id))
            assertTrue("Shell 应正常退出", entry.process.waitFor(15, TimeUnit.SECONDS))
            assertEquals(0, entry.process.exitValue())
            assertTrue("退出后的 stdout/stderr 应完成读取", entry.streamsDrained.await(5, TimeUnit.SECONDS))
            assertEquals("x".repeat(65_536 - "TAIL-END".length) + "TAIL-END", entry.snapshotStdout())
            assertEquals("ERROR-END", entry.snapshotStderr())
            assertTrue(entry.isComplete)
        } finally {
            try {
                ShellProcessManager.stop(id)
            } finally {
                ShellProcessManager.remove(id)
            }
        }
    }

    @Test
    fun runningProcessPublishesTenThousandUnterminatedCharactersBeforeEof() {
        val expected = "x".repeat(10_000)
        val command = "chunk='${"x".repeat(1_000)}'; " +
            "i=0; while [ \"\$i\" -lt 10 ]; do printf '%s' \"\$chunk\"; i=\$((i + 1)); done; " +
            "printf 'READY\\n' >&2; exec sleep 3"
        val id = ShellProcessManager.start(command)
        try {
            val entry = requireNotNull(ShellProcessManager.getProcess(id))
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (entry.process.isAlive && SystemClock.elapsedRealtime() < deadline) {
                if (entry.snapshotStderr() == "READY\n" && entry.snapshotStdout() == expected) break
                SystemClock.sleep(5)
            }
            assertEquals("READY\n", entry.snapshotStderr())
            assertTrue("无末尾换行的输出必须在 Shell 仍运行时可查询", entry.process.isAlive)
            val status = ShellProcessManager.getStatus(id, maxOutputChars = 10_000)
            assertTrue(status.getBoolean("running"))
            assertEquals(expected, status.getString("stdout"))
            assertEquals("READY\n", status.getString("stderr"))
            assertFalse(status.getBoolean("outputComplete"))
        } finally {
            try {
                ShellProcessManager.stop(id)
            } finally {
                ShellProcessManager.remove(id)
            }
        }
    }
}
