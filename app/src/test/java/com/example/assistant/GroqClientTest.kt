package com.example.assistant

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class GroqClientTest {

    private lateinit var server: MockWebServer
    private val pauses = ArrayList<Long>()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun client(
        token: String = "tok",
        http: OkHttpClient = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    ) = GroqClient(server.url("/v1/chat/completions").toString(), token, null, http) { pauses.add(it) }

    private val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "привет"))

    private fun ok(text: String) = MockResponse().setResponseCode(200)
        .setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", text)))).toString())

    private fun send(c: GroqClient) = runBlocking { c.sendConversation(messages, null, effort = "low", maxTokens = 100) }

    @Test fun successfulAnswerAndHeaders() {
        server.enqueue(ok("Привет!"))
        val result = send(client())
        assertEquals(GroqResult.FinalText("Привет!"), result)
        val request = server.takeRequest()
        assertEquals("tok", request.getHeader("X-App-Token"))
        assertNull(request.getHeader("Authorization"))
        assertEquals("/v1/chat/completions", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("openai/gpt-oss-120b", body.getString("model"))
        assertEquals("low", body.getString("reasoning_effort"))
    }

    @Test fun toolCallsAreParsed() {
        val call = JSONObject().put("id", "c1").put("type", "function")
            .put("function", JSONObject().put("name", "flashlight").put("arguments", "{\"on\":true}"))
        val message = JSONObject().put("tool_calls", JSONArray().put(call))
        server.enqueue(MockResponse().setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message))).toString()))
        val result = send(client()) as GroqResult.FunctionCalls
        assertEquals(1, result.calls.size)
        assertEquals("flashlight", result.calls[0].name)
        assertEquals(true, result.calls[0].arguments.getBoolean("on"))
        assertEquals("c1", result.calls[0].id)
    }

    @Test fun badRequestRetriesWithoutExtras() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"unsupported\"}"))
        server.enqueue(ok("готово"))
        assertEquals(GroqResult.FinalText("готово"), send(client()))
        val first = JSONObject(server.takeRequest().body.readUtf8())
        val second = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(first.has("reasoning_effort") && first.has("max_completion_tokens"))
        assertTrue(!second.has("reasoning_effort") && !second.has("max_completion_tokens"))
        assertTrue(pauses.isEmpty())
    }

    @Test fun rateLimitRetriesWithRetryAfterThenBackoff() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "3").setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
        server.enqueue(ok("наконец"))
        assertEquals(GroqResult.FinalText("наконец"), send(client()))
        assertEquals(3, server.requestCount)
        // первая пауза по Retry-After, вторая по расписанию 2, 4, 8: это второй повтор, то есть 4 с
        assertEquals(listOf(3000L, 4000L), pauses)
    }

    @Test fun rateLimitGivesUpAfterThreeRetries() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(429).setBody("{}")) }
        try {
            send(client())
            fail("ожидалась ошибка")
        } catch (e: GroqApiException) {
            assertEquals(429, e.code)
        }
        assertEquals(4, server.requestCount)
        assertEquals(listOf(2000L, 4000L, 8000L), pauses)
    }

    @Test fun longRetryAfterIsNotWaited() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("{}"))
        try {
            send(client())
            fail("ожидалась ошибка")
        } catch (e: GroqApiException) {
            assertEquals(120L, e.retryAfterSec)
        }
        assertEquals(1, server.requestCount)
        assertTrue(pauses.isEmpty())
    }

    @Test fun unauthorizedIsNotRetried() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"unauthorized\"}"))
        try {
            send(client())
            fail("ожидалась ошибка")
        } catch (e: GroqApiException) {
            assertEquals(401, e.code)
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun timeoutIsReported() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val fast = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()
        try {
            send(client(http = fast))
            fail("ожидалась ошибка")
        } catch (e: SocketTimeoutException) {
            assertEquals(FriendlyError.TIMEOUT, FriendlyError.text(e))
        }
    }

    @Test fun notConfiguredFailsClearly() {
        try {
            send(client(token = ""))
            fail("ожидалась ошибка")
        } catch (e: NotConfiguredException) {
            assertEquals(0, server.requestCount)
        }
    }

    // ---------------------------------------------------------------- разбор ответа

    private fun parse(json: String) = client().parseResponse(JSONObject(json))

    @Test fun emptyChoicesAndEmptyContent() {
        assertEquals(GroqResult.FinalText("Модель не вернула ответ"), parse("{\"choices\":[]}"))
        assertEquals(GroqResult.FinalText("Модель не вернула ответ"), parse("{}"))
        assertEquals(GroqResult.FinalText("Пустой ответ от модели"), parse("{\"choices\":[{\"message\":{\"content\":\"  \"}}]}"))
    }

    @Test fun brokenArgumentsBecomeEmptyObject() {
        val r = parse("{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"a\",\"function\":{\"name\":\"set_alarm\",\"arguments\":\"{oops\"}}]}}]}") as GroqResult.FunctionCalls
        assertEquals("set_alarm", r.calls[0].name)
        assertEquals(0, r.calls[0].arguments.length())
    }

    @Test fun toolCallWithoutNameIsIgnored() {
        val r = parse("{\"choices\":[{\"message\":{\"content\":\"текст\",\"tool_calls\":[{\"id\":\"a\",\"function\":{\"arguments\":\"{}\"}}]}}]}")
        assertEquals(GroqResult.FinalText("текст"), r)
    }
}
