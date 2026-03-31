package com.liability.claudeenhancer

import com.intellij.openapi.diagnostic.logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val LOG = logger<ModelClient>()

/**
 * Thin HTTP client for prompt enhancement.
 *
 * Supports two wire formats:
 *  - [ApiStyle.ANTHROPIC]      → POST /v1/messages  (Anthropic Messages API)
 *  - [ApiStyle.OPENAI_COMPAT]  → POST /v1/chat/completions  (OpenAI-compatible, e.g. Ollama)
 *
 * JSON is built/parsed by hand to avoid bundling an extra library.
 * Responses are simple enough that manual string extraction is reliable.
 */
class ModelClient(
    private val state: EnhancerState,
    private val apiKey: String,
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /**
     * Enhance [rawPrompt] using the configured model.
     * [context] is optional project context appended to the user message.
     * Returns the enhanced prompt text.
     * Throws on HTTP errors or unparseable responses.
     */
    fun enhance(rawPrompt: String, context: String): String {
        val userContent = buildUserContent(rawPrompt, context)
        LOG.debug("Calling ${state.apiStyle} API at ${state.endpoint} with model ${state.model}")
        return when (state.apiStyle) {
            ApiStyle.ANTHROPIC     -> callAnthropic(userContent)
            ApiStyle.OPENAI_COMPAT -> callOpenAiCompat(userContent)
            ApiStyle.CLI           -> callCli(userContent)
        }
    }

    // ── Request builders ──────────────────────────────────────────────────────

    private fun callAnthropic(userContent: String): String {
        val body = """
            {
              "model": ${jsonString(state.model)},
              "max_tokens": 1024,
              "system": ${jsonString(state.systemPrompt)},
              "messages": [{"role": "user", "content": ${jsonString(userContent)}}]
            }
        """.trimIndent()

        val response = post(state.endpoint, body, mapOf(
            "x-api-key"         to apiKey,
            "anthropic-version" to "2023-06-01",
        ))

        // Extract: {"content":[{"type":"text","text":"..."}], ...}
        return extractJsonString(response, """"text"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            ?: throw ModelCallException("Could not parse text from Anthropic response:\n$response")
    }

    private fun callOpenAiCompat(userContent: String): String {
        // Normalise endpoint: strip /messages suffix if someone pasted the Anthropic URL.
        val endpoint = state.endpoint
            .replace(Regex("/messages$"), "")
            .trimEnd('/') + "/chat/completions"

        val body = """
            {
              "model": ${jsonString(state.model)},
              "max_tokens": 1024,
              "messages": [
                {"role": "system",  "content": ${jsonString(state.systemPrompt)}},
                {"role": "user",    "content": ${jsonString(userContent)}}
              ]
            }
        """.trimIndent()

        val response = post(endpoint, body, mapOf(
            "Authorization" to "Bearer $apiKey",
        ))

        // Extract: {"choices":[{"message":{"content":"..."}}], ...}
        return extractJsonString(response, """"content"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            ?: throw ModelCallException("Could not parse content from OpenAI-compat response:\n$response")
    }

    // ── Claude Code CLI ───────────────────────────────────────────────────────

    /**
     * Runs `claude --print -p <userContent>` as a subprocess, reusing the
     * existing Claude Code OAuth session — no API key required.
     */
    private fun callCli(userContent: String): String {
        val binary = resolveClaudeBinary()
        val cmd = mutableListOf(binary, "--print")
        if (state.systemPrompt.isNotBlank()) {
            cmd += listOf("--system-prompt", state.systemPrompt)
        }
        if (state.model.isNotBlank()) {
            cmd += listOf("--model", state.model)
        }
        cmd += listOf("-p", userContent)

        LOG.debug("CLI command: ${cmd.take(4).joinToString(" ")} [prompt omitted]")

        // Merge stderr into stdout to prevent deadlock from pipe-buffer fill.
        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        proc.outputStream.close()

        val output = proc.inputStream.bufferedReader().readText()

        if (!proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw ModelCallException("claude CLI timed out after 60 seconds")
        }
        if (proc.exitValue() != 0) {
            throw ModelCallException(
                "claude CLI exited ${proc.exitValue()}: ${output.take(400)}"
            )
        }
        return output.trim().ifEmpty {
            throw ModelCallException("claude CLI returned empty output")
        }
    }

    /**
     * Resolves the `claude` binary to an absolute path.
     * IntelliJ's process environment often has a stripped PATH, so we probe
     * common install locations and fall back to asking the login shell.
     * Result is cached per configured name to avoid repeated filesystem probes.
     */
    private fun resolveClaudeBinary(): String {
        val name = state.claudePath.trim().ifBlank { "claude" }
        resolvedBinaryCache[name]?.let { return it }

        // Absolute path provided — use it directly.
        if (name.startsWith("/")) {
            if (java.io.File(name).canExecute()) return name
            throw ModelCallException("claude binary not executable: $name")
        }

        // Probe common install locations (npm global, Homebrew, etc.)
        val home = System.getProperty("user.home", "")
        val candidates = listOf(
            "/usr/local/bin/$name",
            "/opt/homebrew/bin/$name",
            "/usr/bin/$name",
            "$home/.local/bin/$name",
            "$home/.npm-global/bin/$name",
        )
        candidates.firstOrNull { java.io.File(it).canExecute() }?.let {
            resolvedBinaryCache[name] = it
            return it
        }

        // Last resort: ask the login shell (handles nvm, custom PATH, etc.)
        try {
            // Validate name is safe before embedding in a shell string.
            require(name.matches(Regex("[a-zA-Z0-9_.-]+"))) { "unsafe binary name" }
            val proc = ProcessBuilder("/bin/sh", "-l", "-c", "which $name")
                .redirectErrorStream(true).start()
            val path = proc.inputStream.bufferedReader().readLine()?.trim() ?: ""
            proc.waitFor()
            if (path.isNotEmpty() && java.io.File(path).canExecute()) {
                resolvedBinaryCache[name] = path
                return path
            }
        } catch (_: Exception) {}

        throw ModelCallException(
            "'$name' not found. Set the full path in Settings → Tools → Claude Prompt Enhancer."
        )
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private fun post(url: String, body: String, extraHeaders: Map<String, String>): String {
        val reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))

        extraHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }

        val response = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw ModelCallException(
                "API returned HTTP ${response.statusCode()}: ${response.body().take(400)}"
            )
        }
        return response.body()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildUserContent(rawPrompt: String, context: String): String =
        if (context.isBlank()) rawPrompt
        else "$rawPrompt\n\n---\nProject context (use to inform the enhancement):\n$context"

    /** Minimal JSON string escaping — handles the common cases. */
    internal fun jsonString(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    /**
     * Extract the first capture group of [pattern] from [json],
     * un-escaping JSON string escape sequences in the result.
     */
    internal fun extractJsonString(json: String, pattern: String): String? {
        val match = Regex(pattern, RegexOption.DOT_MATCHES_ALL).find(json) ?: return null
        return match.groupValues[1]
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }
}

class ModelCallException(message: String) : RuntimeException(message)

private val resolvedBinaryCache = java.util.concurrent.ConcurrentHashMap<String, String>()
