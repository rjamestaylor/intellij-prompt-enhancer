package com.liability.claudeenhancer

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelClientTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String

    @BeforeEach fun setUp() {
        server  = MockWebServer()
        server.start()
        baseUrl = server.url("/v1/messages").toString()
    }

    @AfterEach fun tearDown() { server.shutdown() }

    // ── Anthropic path ────────────────────────────────────────────────────────

    @Test fun `anthropic happy path returns extracted text`() {
        server.enqueue(MockResponse().setBody("""
            {"id":"msg_01","content":[{"type":"text","text":"Enhanced: do the thing."}],
             "model":"claude-haiku-4-5-20251001","stop_reason":"end_turn"}
        """.trimIndent()).setHeader("Content-Type", "application/json"))

        val client = makeClient(ApiStyle.ANTHROPIC)
        val result = client.enhance("do the thing", "")

        assertEquals("Enhanced: do the thing.", result)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.body.readUtf8().contains("claude-haiku"))
        assertEquals("2023-06-01", req.getHeader("anthropic-version"))
    }

    @Test fun `anthropic passes context in user message`() {
        server.enqueue(MockResponse().setBody(anthropicOkBody("ok")).setHeader("Content-Type", "application/json"))

        val client = makeClient(ApiStyle.ANTHROPIC)
        client.enhance("fix bug", "## Git context\nBranch: main")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("fix bug"))
        assertTrue(body.contains("Git context"))
    }

    @Test fun `anthropic 401 throws ModelCallException`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid key"}}"""))

        val client = makeClient(ApiStyle.ANTHROPIC)
        assertThrows<ModelCallException> { client.enhance("x", "") }
    }

    @Test fun `anthropic malformed response throws`() {
        server.enqueue(MockResponse().setBody("""{"content":[]}""").setHeader("Content-Type", "application/json"))

        val client = makeClient(ApiStyle.ANTHROPIC)
        assertThrows<ModelCallException> { client.enhance("x", "") }
    }

    // ── OpenAI-compat path ────────────────────────────────────────────────────

    @Test fun `openai-compat happy path returns content`() {
        // Endpoint is normalised from /v1/messages → /v1/chat/completions
        val compat = MockWebServer().also { it.start() }
        val compatUrl = compat.url("/v1/chat/completions").toString()
            .replace("/chat/completions", "/messages")  // normalisation test

        compat.enqueue(MockResponse().setBody(openAiOkBody("OpenAI enhanced")).setHeader("Content-Type", "application/json"))

        val state = EnhancerState(
            endpoint = compatUrl,
            model    = "qwen3:8b",
            apiStyle = ApiStyle.OPENAI_COMPAT,
        )
        val result = ModelClient(state, "test-key").enhance("test prompt", "")

        assertEquals("OpenAI enhanced", result)
        val req = compat.takeRequest()
        assertTrue(req.body.readUtf8().contains("system"))
        compat.shutdown()
    }

    @Test fun `openai-compat passes Authorization header`() {
        val compat = MockWebServer().also { it.start() }
        compat.enqueue(MockResponse().setBody(openAiOkBody("ok")).setHeader("Content-Type", "application/json"))

        val state = EnhancerState(
            endpoint = compat.url("/v1/messages").toString(),
            apiStyle = ApiStyle.OPENAI_COMPAT,
        )
        ModelClient(state, "my-secret-key").enhance("x", "")

        assertEquals("Bearer my-secret-key", compat.takeRequest().getHeader("Authorization"))
        compat.shutdown()
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private val textPattern = """"text"\s*:\s*"((?:[^"\\]|\\.)*)""""

    @Test fun `jsonString escapes special characters`() {
        val client = makeClient(ApiStyle.ANTHROPIC)
        assertEquals("\"hello\\nworld\"",  client.jsonString("hello\nworld"))
        assertEquals("\"say \\\"hi\\\"\"", client.jsonString("say \"hi\""))
        assertEquals("\"back\\\\slash\"",  client.jsonString("back\\slash"))
    }

    @Test fun `jsonString round-trips through extractJsonString`() {
        val client = makeClient(ApiStyle.ANTHROPIC)
        val inputs = listOf(
            "simple text",
            "line1\nline2",
            """say "hello" there""",
            """path\to\file""",
            """escaped \" quote""",                  // backslash + quote
            """double\\backslash""",                 // two backslashes
            """mixed\nnewline and \\n literal""",    // real newline vs literal \n
            "tab\there",
            "emoji \uD83D\uDE80 rocket",
        )
        for (input in inputs) {
            val json = """{"text": ${client.jsonString(input)}}"""
            val result = client.extractJsonString(json, textPattern)
            assertEquals(input, result, "Round-trip failed for: ${input.replace("\n", "\\n")}")
        }
    }

    @Test fun `extractJsonString finds first match`() {
        val client = makeClient(ApiStyle.ANTHROPIC)
        val json = """{"text": "hello\nworld"}"""
        val result = client.extractJsonString(json, textPattern)
        assertEquals("hello\nworld", result)
    }

    @Test fun `extractJsonString handles nested escaped quotes`() {
        val client = makeClient(ApiStyle.ANTHROPIC)
        val json = """{"text": "say \"hello\" there"}"""
        val result = client.extractJsonString(json, textPattern)
        assertEquals("say \"hello\" there", result)
    }

    @Test fun `extractJsonString distinguishes literal backslash-n from newline`() {
        val client = makeClient(ApiStyle.ANTHROPIC)
        // JSON \\n = literal backslash + n  (not a newline)
        val json = """{"text": "path\\nname"}"""
        val result = client.extractJsonString(json, textPattern)
        assertEquals("path\\nname", result)
    }

    @Test fun `extractJsonString handles escaped backslash at end`() {
        val client = makeClient(ApiStyle.ANTHROPIC)
        val json = """{"text": "trailing\\"}"""
        val result = client.extractJsonString(json, textPattern)
        assertEquals("trailing\\", result)
    }

    @Test fun `extractJsonString returns null when no match`() {
        val client = makeClient(ApiStyle.ANTHROPIC)
        assertNull(client.extractJsonString("{}", textPattern))
    }

    // ── CLI binary resolution ────────────────────────────────────────────────

    @Test fun `cli throws on empty output`() {
        server.enqueue(MockResponse()) // unused — CLI doesn't hit HTTP

        val state = EnhancerState(
            apiStyle = ApiStyle.CLI,
            claudePath = "/usr/bin/true",   // exits 0 but produces no output
        )
        val client = ModelClient(state, "")
        assertThrows<ModelCallException> { client.enhance("test", "") }
    }

    @Test fun `cli throws on non-executable path`() {
        val state = EnhancerState(
            apiStyle = ApiStyle.CLI,
            claudePath = "/nonexistent/binary",
        )
        val client = ModelClient(state, "")
        assertThrows<ModelCallException> { client.enhance("test", "") }
    }

    @Test fun `cli throws on non-zero exit`() {
        val state = EnhancerState(
            apiStyle = ApiStyle.CLI,
            claudePath = "/usr/bin/false",   // exits 1
        )
        val client = ModelClient(state, "")
        assertThrows<ModelCallException> { client.enhance("test", "") }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeClient(style: ApiStyle) = ModelClient(
        EnhancerState(endpoint = baseUrl, apiStyle = style, model = "claude-haiku-4-5-20251001"),
        "test-api-key"
    )

    private fun anthropicOkBody(text: String) =
        """{"id":"x","content":[{"type":"text","text":"$text"}],"model":"m","stop_reason":"end_turn"}"""

    private fun openAiOkBody(content: String) =
        """{"choices":[{"message":{"role":"assistant","content":"$content"}}]}"""
}
