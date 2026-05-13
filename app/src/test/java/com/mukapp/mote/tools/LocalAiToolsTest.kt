package com.mukapp.mote.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText

class LocalAiToolsTest {

    @Test
    fun readFileUsesExplicitLineRangeWhenFirstLinesIsAlsoProvided() {
        val file = createTempLineFile(lineCount = 130)

        val result = readFile(
            JSONObject()
                .put("description", "读取中间行")
                .put("path", file.path)
                .put("first_lines", 21)
                .put("start_line", 100)
                .put("end_line", 120)
        )

        assertEquals(true, result.getBoolean("ok"))
        assertEquals(100, result.getInt("start"))
        assertEquals(120, result.getInt("end"))
        assertEquals(21, result.getInt("lines"))
        assertTrue(result.getString("content").contains("100: 第100行"))
        assertTrue(result.getString("content").contains("120: 第120行"))
        assertFalse(result.getString("content").contains("1: 第1行"))
    }

    @Test
    fun readFileTreatsZeroFirstLinesAsPlaceholderWhenLineRangeIsProvided() {
        val file = createTempLineFile(lineCount = 130)

        val result = readFile(
            JSONObject()
                .put("description", "读取中间行")
                .put("path", file.path)
                .put("first_lines", 0)
                .put("start_line", 100)
                .put("end_line", 120)
        )

        assertEquals(true, result.getBoolean("ok"))
        assertEquals(100, result.getInt("start"))
        assertEquals(120, result.getInt("end"))
        assertEquals(21, result.getInt("lines"))
        assertTrue(result.getString("content").startsWith("100: 第100行"))
    }

    @Test
    fun readFileStillRejectsZeroFirstLinesWithoutLineRange() {
        val file = createTempLineFile(lineCount = 10)

        val error = assertThrows(IllegalArgumentException::class.java) {
            LocalAiTools.readFile(
                JSONObject()
                    .put("description", "读取文件头")
                    .put("path", file.path)
                    .put("first_lines", 0)
                    .toString()
            )
        }

        assertEquals("first_lines 必须大于 0。", error.message)
    }

    @Test
    fun readFileRejectsNonPositiveFirstLinesWhenNoLineRangeIsProvided() {
        val file = createTempLineFile(lineCount = 10)

        val error = assertThrows(IllegalArgumentException::class.java) {
            LocalAiTools.readFile(
                JSONObject()
                    .put("description", "读取文件头")
                    .put("path", file.path)
                    .put("first_lines", -1)
                    .toString()
            )
        }

        assertEquals("first_lines 必须大于 0。", error.message)
    }

    @Test
    fun readFileRejectsTooLargeLineRangeEvenWhenFirstLinesIsSmall() {
        val file = createTempLineFile(lineCount = 500)

        val error = assertThrows(IllegalArgumentException::class.java) {
            LocalAiTools.readFile(
                JSONObject()
                    .put("description", "读取过大的中间范围")
                    .put("path", file.path)
                    .put("first_lines", 1)
                    .put("start_line", 1)
                    .put("end_line", 401)
                    .toString()
            )
        }

        assertEquals("单次读取行数不能超过 400。", error.message)
    }

    @Test
    fun readFileNormalizesZeroStartLineToFirstLine() {
        val file = createTempLineFile(lineCount = 10)

        val result = readFile(
            JSONObject()
                .put("description", "读取文件头")
                .put("path", file.path)
                .put("start_line", 0)
                .put("end_line", 2)
        )

        assertEquals(true, result.getBoolean("ok"))
        assertEquals(1, result.getInt("start"))
        assertEquals(2, result.getInt("end"))
        assertEquals(2, result.getInt("lines"))
        assertTrue(result.getString("content").startsWith("1: 第1行"))
    }

    @Test
    fun readFileReadsFirstLinesWhenNoLineRangeIsProvided() {
        val file = createTempLineFile(lineCount = 10)

        val result = readFile(
            JSONObject()
                .put("description", "读取文件头")
                .put("path", file.path)
                .put("first_lines", 3)
        )

        assertEquals(true, result.getBoolean("ok"))
        assertEquals(1, result.getInt("start"))
        assertEquals(3, result.getInt("end"))
        assertEquals(3, result.getInt("lines"))
        assertTrue(result.getString("content").contains("1: 第1行"))
        assertTrue(result.getString("content").contains("3: 第3行"))
        assertFalse(result.getString("content").contains("4: 第4行"))
    }

    private fun readFile(arguments: JSONObject): JSONObject {
        return JSONObject(LocalAiTools.readFile(arguments.toString()))
    }

    private fun createTempLineFile(lineCount: Int): File {
        val path = createTempFile(prefix = "mote-read-file-test", suffix = ".txt")
        path.writeText(
            (1..lineCount).joinToString(separator = "\n") { lineNumber ->
                "第${lineNumber}行"
            },
            Charsets.UTF_8
        )
        return path.toFile().apply { deleteOnExit() }
    }
}
