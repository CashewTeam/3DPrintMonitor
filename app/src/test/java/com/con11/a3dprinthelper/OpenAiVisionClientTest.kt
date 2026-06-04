package com.con11.a3dprinthelper

import com.con11.a3dprinthelper.network.OpenAiVisionClient
import com.con11.a3dprinthelper.network.PrintStatus
import com.con11.a3dprinthelper.network.VisionStreamUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiVisionClientTest {
    private val client = OpenAiVisionClient()

    @Test
    fun parsesStructuredOutputFromMarkdownFence() {
        val result = client.parseOutputText(
            """
            ```json
            {
              "status": "normal",
              "confidence": 0.92,
              "summary": "打印正常",
              "abnormalReasons": [],
              "shouldNotify": false
            }
            ```
            """.trimIndent()
        )

        assertEquals(PrintStatus.Normal, result.status)
        assertEquals("打印正常", result.summary)
        assertFalse(result.shouldNotify)
    }

    @Test
    fun keepsReadableTextWhenProviderDoesNotReturnJson() {
        val result = client.parseOutputText("暂时无法判断打印状态")

        assertEquals(PrintStatus.Unknown, result.status)
        assertEquals("暂时无法判断打印状态", result.summary)
        assertTrue(result.rawText.isNotBlank())
    }

    @Test
    fun accumulatesResponsesApiStreamingDeltas() {
        val updates = mutableListOf<VisionStreamUpdate>()
        val result = client.parseEventPayloads(
            sequenceOf(
                """{"type":"response.created"}""",
                """{"type":"response.output_text.delta","delta":"{\"status\":\"normal\","}""",
                """{"type":"response.output_text.delta","delta":"\"summary\":\"打印正常\",\"shouldNotify\":false}"}""",
                """{"type":"response.completed"}"""
            ),
            updates::add
        )

        assertEquals(PrintStatus.Normal, result.status)
        assertEquals("打印正常", result.summary)
        assertTrue(updates.any { it is VisionStreamUpdate.TextDelta })
        assertTrue(updates.last() is VisionStreamUpdate.Parsing)
    }
}
