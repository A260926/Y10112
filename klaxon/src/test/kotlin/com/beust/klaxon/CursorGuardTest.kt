package com.beust.klaxon

import org.assertj.core.api.Assertions.assertThat
import org.testng.annotations.Test
import java.io.FilterReader
import java.io.InputStream
import java.io.StringReader
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Behavioral guards that pin down the cursor semantics before and after the streaming/full
 * parser control flows are converged onto a single internal token cursor.
 */
@Test
class CursorGuardTest {

    //
    // Helpers
    //

    private class TrackingMatcher(private val accept: (String) -> Boolean) : PathMatcher {
        val events = arrayListOf<Pair<String, Any>>()
        override fun pathMatches(path: String) = accept(path)
        override fun onMatch(path: String, value: Any) {
            events.add(path to value)
        }
    }

    /** A reader that serves at most [chunk] characters per read, forcing heavy re-buffering. */
    private class ChunkedReader(source: String, private val chunk: Int) :
        FilterReader(StringReader(source)) {
        override fun read(cbuf: CharArray, off: Int, len: Int): Int =
            super.read(cbuf, off, minOf(len, chunk))
    }

    /** An input stream that serves at most [chunk] bytes per read. */
    private class ChunkedInputStream(private val bytes: ByteArray, private val chunk: Int) :
        InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos >= bytes.size) -1 else bytes[pos++].toInt() and 0xFF
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= bytes.size) return -1
            val n = minOf(len, chunk, bytes.size - pos)
            System.arraycopy(bytes, pos, b, off, n)
            pos += n
            return n
        }
    }

    private fun parseFull(json: String, matchers: List<PathMatcher> = emptyList()): Any =
        Parser.default(matchers).parse(StringBuilder(json))

    private fun klaxonParse(matchers: List<PathMatcher>, json: String): JsonObject {
        val k = Klaxon()
        matchers.forEach { k.pathMatcher(it) }
        return k.parseJsonObject(StringReader(json))
    }

    /** Parse exactly one value off a streaming reader driven by one shared lexer. */
    private fun parseOneStreaming(reader: JsonReader): Any =
        Parser.default(passedLexer = reader.lexer, streaming = true).parse(reader)

    //
    // 1. Full and streaming parses of the same value yield the same result
    //

    fun fullAndStreamingAgreeOnObject() {
        val json = """{ "name": "Joe", "age": 23, "obj": { "a": 1, "b": [true, null] } }"""
        val full = parseFull(json) as JsonObject
        val streamed = JsonReader(StringReader("[$json]")).use { reader ->
            reader.beginArray { parseOneStreaming(reader) as JsonObject }
        }
        assertThat(streamed).isEqualTo(full)
    }

    fun fullAndStreamingAgreeOnArray() {
        val json = """[1, "two", { "ok": true }, [3.5, null]]"""
        val full = parseFull(json) as JsonArray<*>
        val streamed = JsonReader(StringReader("[$json]")).use { reader ->
            reader.beginArray { parseOneStreaming(reader) as JsonArray<*> }
        }
        assertThat(streamed).isEqualTo(full)
    }

    fun fullParsesScalarRoots() {
        assertThat(parseFull("123")).isEqualTo(123)
        assertThat(parseFull("  \"hi\u00e9\"  ")).isEqualTo("hi\u00e9")
        assertThat(parseFull("true")).isEqualTo(true)
        assertThat(parseFull("{}")).isEqualTo(JsonObject())
        assertThat(parseFull("[]")).isEqualTo(JsonArray<Any>())
    }

    //
    // 2. Consecutive root values: rejected in full, supported in streaming
    //

    fun fullRejectsConsecutiveRootValues() {
        listOf(
            "1 2",
            "\"x\" \"y\"",
            "{} true",
            "[][]",
            "{\"a\":1} {\"b\":2}",
            "{} ,1"
        ).forEach { json ->
            val ex = try {
                parseFull(json)
                null
            } catch (e: KlaxonException) {
                e
            }
            assertThat(ex)
                .describedAs("second root value must be rejected in full mode: $json")
                .isNotNull
        }
    }

    fun fullAllowsOnlyWhitespaceAfterRoot() {
        assertThat(parseFull("1\n \r\t ")).isEqualTo(1)
        assertThat(parseFull("\"a\"  \n")).isEqualTo("a")
        assertThat(parseFull("9223372036854775808  ")).isEqualTo(BigInteger("9223372036854775808"))
    }

    fun streamingKeepsNextTokenForCaller() {
        val jr = JsonReader(StringReader("""[{"a":1},{"b":2},{"c":3}]"""))
        val objects = arrayListOf<JsonObject>()
        jr.beginArray {
            while (jr.hasNext()) {
                objects.add(parseOneStreaming(jr) as JsonObject)
            }
        }
        assertThat(objects).containsExactly(
            JsonObject(mapOf("a" to 1)),
            JsonObject(mapOf("b" to 2)),
            JsonObject(mapOf("c" to 3))
        )
    }

    fun streamingReusesLexerAcrossRoots() {
        // One shared lexer drives several independent streaming parses.
        val reader = JsonReader(StringReader("""{"a":1} , {"a":2}"""))
        val p1 = parseOneStreaming(reader) as JsonObject
        val p2 = parseOneStreaming(reader) as JsonObject
        assertThat(p1["a"]).isEqualTo(1)
        assertThat(p2["a"]).isEqualTo(2)
    }

    //
    // 3. PathMatcher hit and miss branches keep the path balanced
    //

    fun pathMatcherBalancedAcrossHitsAndMisses() {
        val matcher = TrackingMatcher { path ->
            path == "$.a.b2.name" || path == "$.arr[1].x"
        }
        val json = """{
            "a": {
                "b1": { "name": "miss1" },
                "b2": { "name": "hit", "other": 1 },
                "b3": []
            },
            "arr": [
                { "x": 0 },
                { "x": 7, "y": 8 },
                {}
            ],
            "tail": "end"
        }"""
        klaxonParse(listOf(matcher), json)
        assertThat(matcher.events).containsExactly(
            "$.a.b2.name" to "hit",
            "$.arr[1].x" to 7
        )
    }

    fun pathMatcherFiresInsideNestedEmptyContainers() {
        val matcher = TrackingMatcher { it.startsWith("$.root") }
        val json = """{"root": {"emptyObj": {}, "emptyArr": [], "arr": [{}, []], "v": 1}}"""
        klaxonParse(listOf(matcher), json)
        assertThat(matcher.events).containsExactly("$.root.v" to 1)
    }

    //
    // 4. A failure deep in one parse must not leak path state into the next parser
    //

    fun freshParseAfterMatcherThrows() {
        val throwing = TrackingMatcher { false }
        val boom = object : PathMatcher {
            override fun pathMatches(path: String) = path == "$.boom"
            override fun onMatch(path: String, value: Any) =
                throw KlaxonException("boom at $path")
        }
        val json = """{"boom": 1, "after": 2}"""
        try {
            klaxonParse(listOf(throwing, boom), json)
            throw AssertionError("expected matcher exception to surface")
        } catch (e: KlaxonException) {
            assertThat(e.message).contains("boom")
        }

        // A brand new parser must start with an empty path and observe the full path cleanly.
        val after = TrackingMatcher { true }
        klaxonParse(listOf(after), json)
        assertThat(after.events).containsExactly(
            "$.boom" to 1,
            "$.after" to 2
        )
    }

    fun matcherThrowInsideNestedDocumentDoesNotLeakFrames() {
        // The matcher throws after several containers have been opened; frames must still be
        // released so the next parse starts with an empty path stack.
        val boom = object : PathMatcher {
            override fun pathMatches(path: String) = path == "$.a.b.c"
            override fun onMatch(path: String, value: Any) =
                throw KlaxonException("deep boom at $path")
        }
        val json = """{"a": {"b": {"c": 5, "d": 6}, "e": [1, 2]}, "f": 7}"""
        try {
            parseFull(json, listOf(boom))
            throw AssertionError("expected deep matcher exception")
        } catch (e: KlaxonException) {
            assertThat(e.message).isEqualTo("deep boom at $.a.b.c")
        }
        val after = TrackingMatcher { true }
        parseFull(json, listOf(after))
        assertThat(after.events.map { it.first })
            .containsExactly("$.a.b.c", "$.a.b.d", "$.f")
        // Bare array primitives are not path-match events, only object pair values are.
        assertThat(after.events.map { it.second })
            .containsExactly(5, 6, 7)
    }

    fun freshParseAfterSyntaxError() {
        try {
            parseFull("""{ "a": { "b": [ 1, { ] } }""")
            throw AssertionError("expected syntax error")
        } catch (e: KlaxonException) {
            assertThat(e).hasMessageContaining("line")
        }
        val next = parseFull("{\"a\": 1}") as JsonObject
        assertThat(next["a"]).isEqualTo(1)
    }

    fun parallelParsersHaveIsolatedCursors() {
        val threadCount = 8
        val pool = Executors.newFixedThreadPool(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val failures = AtomicInteger(0)
        repeat(threadCount) { id ->
            pool.submit {
                start.await()
                try {
                    repeat(50) { i ->
                        val matcher = TrackingMatcher { true }
                        val json = """{"id": $id, "n": $i, "child": {"x": "v$i"}}"""
                        val obj = klaxonParse(listOf(matcher), json) as JsonObject
                        assertThat(obj["id"]).isEqualTo(id)
                        assertThat(matcher.events.map { it.first })
                            .containsExactly("$.id", "$.n", "$.child.x")
                    }
                } catch (e: Throwable) {
                    failures.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        done.await()
        pool.shutdown()
        assertThat(failures.get()).isEqualTo(0)
    }

    //
    // 5. Chunked readers / streams: UTF-8 crossing buffers, surrogates, CRLF lines
    //

    fun chunkedReaderKeepsUnicodeIntact() {
        val json = "{ \"emoji\": \"\uD83D\uDE00\", \"e\": \"\u00e9\u00e8\u00e7\" }"
        val result = Parser.default().parse(ChunkedReader(json, 1)) as JsonObject
        assertThat(result["emoji"]).isEqualTo("\uD83D\uDE00")
        assertThat(result["e"]).isEqualTo("\u00e9\u00e8\u00e7")
    }

    fun chunkedUtf8StreamParsesAcrossBufferBoundaries() {
        val bytes = "{\"a\":\"x\u00e9\u00e9\"}".toByteArray(Charsets.UTF_8)
        val result = Parser.default().parse(ChunkedInputStream(bytes, 1)) as JsonObject
        assertThat(result["a"]).isEqualTo("x\u00e9\u00e9")
    }

    fun crlfLineCountingUnchanged() {
        val json = "{\r\n  \"a\": 1,\r\n  \"b\": [\r\n    1,\r\n  ]\r\n}"
        assertThat(parseFull(json)).isEqualTo(
            JsonObject(mapOf("a" to 1, "b" to JsonArray(1)))
        )
        try {
            parseFull("{\r\n\"a\": 1,\r\n\"b\": 2,\r\n33 }")
            throw AssertionError("expected error on line 4")
        } catch (e: KlaxonException) {
            assertThat(e.message).contains("at line 4")
        }
    }

    //
    // 6. EOF boundaries, long numbers and deeply nested empty containers
    //

    fun eofBoundaries() {
        assertThat(parseFull("[]")).isEqualTo(JsonArray<Any>())
        assertThat(parseFull(" {} ")).isEqualTo(JsonObject())
        listOf("", "   ", "\r\n\t ").forEach { blank ->
            try {
                parseFull(blank)
                throw AssertionError("expected EOF error for blank input")
            } catch (e: KlaxonException) {
                assertThat(e.message).contains("EOF")
            }
        }
        try {
            parseFull("\"unterminated")
            throw AssertionError("expected unterminated string error")
        } catch (e: KlaxonException) {
            assertThat(e).isInstanceOf(KlaxonException::class.java)
        }
    }

    fun oversizedNumbersAndEmptyContainers() {
        val bigLong = "9223372036854775808"
        val deep = buildString {
            append("{\"a\":")
            repeat(100) { append("[") }
            repeat(100) { append("]") }
            append(",\"b\":{},\"c\":[")
            repeat(50) { append("[") }
            repeat(50) { append("]") }
            append("]}")
        }
        val obj = parseFull("""{"l": $bigLong, "deep": $deep, "n": 1234567890123}""") as JsonObject
        assertThat(obj["l"]).isEqualTo(BigInteger(bigLong))
        assertThat(obj["n"]).isEqualTo(1234567890123L)
        val deepObj = obj["deep"] as JsonObject
        assertThat(deepObj["b"]).isEqualTo(JsonObject())
        var nested: Any = deepObj["c"]!!
        var depth = 0
        while (nested is JsonArray<*>) {
            depth++
            nested = if (nested.isEmpty()) Unit else nested[0]!!
        }
        assertThat(depth).isEqualTo(51)
    }

    //
    // 7. Token cursor introspection: token positions and path.
    //    Enabled only once the internal TokenCursor exists.
    //

    @Test
    fun cursorExposesPositionAndPath() {
        val json = "{\n  \"a\": [10, 20]\n}"
        val cursor = TokenCursor(Lexer(StringReader(json)), emptyList(), streaming = false)
        val trace = arrayListOf<String>()
        while (true) {
            val token = cursor.next()
            trace.add("${token}@${cursor.index}:${cursor.line}:${cursor.path}")
            if (token is com.beust.klaxon.token.EOF) break
        }
        trace.forEach(::println)
        val joined = trace.joinToString("|")
        assertThat(joined).contains("[@10:2:$.a[-1]")
        assertThat(joined).contains("10@12:2:$.a[0]")
        assertThat(joined).contains("20@16:2:$.a[1]")
        assertThat(joined).contains("]@17:2:$|")
        assertThat(trace.first()).isEqualTo("{@1:1:$")
        assertThat(trace.last()).isEqualTo("EOF@19:3:$")
    }
}
