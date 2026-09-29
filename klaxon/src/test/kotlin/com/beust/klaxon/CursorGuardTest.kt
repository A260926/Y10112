package com.beust.klaxon

import com.beust.klaxon.token.EOF
import com.beust.klaxon.token.COMMA
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.testng.annotations.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.Reader
import java.io.StringReader
import java.math.BigInteger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Behavior guards for the token cursor refactoring: these tests pin down the
 * observable behavior of the full and streaming parse loops (token advancement,
 * EOF handling, positions and PathMatcher paths) so the internal cursor
 * extraction cannot change it.
 */
@Test
class CursorGuardTest {

    /** A reader that never returns more than [chunk] characters per read. */
    private class ChunkedReader(text: String, private val chunk: Int) : Reader() {
        private val delegate = StringReader(text)
        override fun read(cbuf: CharArray, off: Int, len: Int): Int =
            delegate.read(cbuf, off, minOf(chunk, len))
        override fun close() = delegate.close()
    }

    /** An input stream that never returns more than [chunk] bytes per read. */
    private class ChunkedInputStream(bytes: ByteArray, private val chunk: Int) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            delegate.read(b, off, minOf(chunk, len))
    }

    private class RecordingMatcher(private val hits: Set<String>) : PathMatcher {
        val queried = mutableListOf<String>()
        val matched = mutableListOf<Pair<String, Any>>()
        override fun pathMatches(path: String): Boolean {
            queried.add(path)
            return path in hits
        }
        override fun onMatch(path: String, value: Any) {
            matched.add(path to value)
        }
    }

    private val sample = """{"a": [1, 2, {"b": "x"}], "c": null, "d": 1.5, "e": true}"""

    fun fullAndStreamingParseAgreeOnSameValue() {
        val full = Parser.default().parse(StringReader(sample))
        val jsonReader = JsonReader(StringReader(sample))
        val streamed = Parser.default(passedLexer = jsonReader.lexer, streaming = true).parse(jsonReader)
        assertThat(streamed).isInstanceOf(JsonObject::class.java)
        assertThat((streamed as JsonBase).toJsonString()).isEqualTo((full as JsonBase).toJsonString())

        val arrayJson = """[1, "two", [3], {"four": 4}]"""
        val fullArray = Parser.default().parse(StringReader(arrayJson))
        val arrayReader = JsonReader(StringReader(arrayJson))
        val streamedArray =
            Parser.default(passedLexer = arrayReader.lexer, streaming = true).parse(arrayReader)
        assertThat((streamedArray as JsonBase).toJsonString()).isEqualTo((fullArray as JsonBase).toJsonString())
    }

    fun fullParseConsumesSingleRootValueThenOnlyWhitespace() {
        // Trailing whitespace is fine.
        val result = Parser.default().parse(StringReader("  {\"a\":1}  \r\n\t  "))
        assertThat((result as JsonObject)["a"]).isEqualTo(1)

        // Anything but whitespace after the root value is rejected.
        listOf("""{"a":1} {"b":2}""", "[1] [2]", "1 2", "true false", """{"a":1} x""").forEach {
            assertThatThrownBy { Parser.default().parse(StringReader(it)) }
                .isInstanceOf(KlaxonException::class.java)
        }
    }

    fun streamingParseLeavesNextTokenForCaller() {
        val jsonReader = JsonReader(StringReader("[1, 2], [3, 4]"))
        val first = Parser.default(passedLexer = jsonReader.lexer, streaming = true).parse(jsonReader)
        assertThat((first as JsonArray<*>).toJsonString()).isEqualTo("[1,2]")

        // The token following the completed value is left for the caller.
        assertThat(jsonReader.lexer.peek()).isEqualTo(COMMA)

        // A new streaming parser on the same lexer skips the comma and reads the next value.
        val second = Parser.default(passedLexer = jsonReader.lexer, streaming = true).parse(jsonReader)
        assertThat((second as JsonArray<*>).toJsonString()).isEqualTo("[3,4]")
        assertThat(jsonReader.lexer.peek()).isEqualTo(EOF)
    }

    fun pathMatcherPushPopPairsOnHitAndMiss() {
        val matcher = RecordingMatcher(setOf("$.a.c[1].d", "$.e"))
        val json = """{"a": {"b": 1, "c": [2, {"d": 3}]}, "e": 4}"""
        Parser.default(pathMatchers = listOf(matcher)).parse(StringReader(json))

        assertThat(matcher.queried).containsExactly("$.a.b", "$.a.c[1].d", "$.e")
        assertThat(matcher.matched).containsExactly("$.a.c[1].d" to 3, "$.e" to 4)
    }

    fun freshParserDoesNotSeeStalePathAfterError() {
        val matcher = RecordingMatcher(setOf("$.a[2].b", "$.x"))
        // Fails deep inside a nested structure, after one path matcher notification.
        assertThatThrownBy {
            Parser.default(pathMatchers = listOf(matcher)).parse(StringReader("""{"a": [1, 2, {"b": 3}]"""))
        }.isInstanceOf(KlaxonException::class.java)
        assertThat(matcher.queried).containsExactly("$.a[2].b")

        // A new, independent parser starts from a clean path stack.
        matcher.queried.clear()
        Parser.default(pathMatchers = listOf(matcher)).parse(StringReader("""{"x": 1}"""))
        assertThat(matcher.queried).containsExactly("$.x")
        assertThat(matcher.matched).contains("$.x" to 1)
    }

    fun chunkedReaderProducesSameResultsAndErrorPositions() {
        val json = "{\r\n  \"emoji\": \"\uD83D\uDE00\",\r\n" +
                "  \"big\": 123456789012345678901234567890,\r\n" +
                "  \"empty\": {},\r\n  \"nested\": [[]]\r\n}"
        val expected = Parser.default().parse(StringReader(json)) as JsonObject
        for (chunk in listOf(1, 2, 3, 7)) {
            val actual = Parser.default().parse(ChunkedReader(json, chunk)) as JsonObject
            assertThat(actual.toJsonString()).isEqualTo(expected.toJsonString())
        }
        assertThat(expected["emoji"]).isEqualTo("\uD83D\uDE00")
        assertThat(expected["big"]).isEqualTo(BigInteger("123456789012345678901234567890"))
        assertThat(expected["empty"]).isEqualTo(JsonObject())
        assertThat(expected["nested"]).isEqualTo(JsonArray(listOf(JsonArray<Any>())))

        // Error positions do not depend on how the reader chunks its input.
        val broken = "{\"a\": 1,\r\n\"b\": oops}"
        val plainError = runCatching { Parser.default().parse(StringReader(broken)) }.exceptionOrNull()
        val chunkedError = runCatching { Parser.default().parse(ChunkedReader(broken, 1)) }.exceptionOrNull()
        assertThat(chunkedError).isInstanceOf(KlaxonException::class.java)
        assertThat(chunkedError!!.message).isEqualTo(plainError!!.message)

        // CRLF line counting is preserved in state machine errors.
        val extraBrace = "{\r\n\"a\": 1\r\n} }"
        val error = runCatching { Parser.default().parse(ChunkedReader(extraBrace, 1)) }.exceptionOrNull()
        assertThat(error).isInstanceOf(KlaxonException::class.java)
        assertThat(error!!.message).contains("at line 3")
    }

    fun chunkedInputStreamKeepsUtf8AcrossBufferBoundaries() {
        val json = """{"text": "héllo 😀 世界", "n": 42}"""
        val bytes = json.toByteArray(Charsets.UTF_8)
        for (chunk in listOf(1, 2, 3, 5)) {
            val result = Parser.default().parse(ChunkedInputStream(bytes, chunk)) as JsonObject
            assertThat(result["text"]).isEqualTo("héllo 😀 世界")
            assertThat(result["n"]).isEqualTo(42)
        }
    }

    fun eofBoundaries() {
        // Empty input is an error in full parse mode.
        assertThatThrownBy { Parser.default().parse(StringReader("")) }
            .isInstanceOf(KlaxonException::class.java)
        // Whitespace-only input is an error as well.
        assertThatThrownBy { Parser.default().parse(StringReader("  \r\n ")) }
            .isInstanceOf(KlaxonException::class.java)
        // Unterminated string at EOF.
        assertThatThrownBy { Parser.default().parse(StringReader("\"abc")) }
            .isInstanceOf(KlaxonException::class.java)
            .hasMessageContaining("Unterminated string")
        // Values ending exactly at EOF (no trailing whitespace) parse fine.
        assertThat((Parser.default().parse(StringReader("[1,2]")) as JsonArray<*>).toJsonString())
            .isEqualTo("[1,2]")
        assertThat(Parser.default().parse(StringReader("\"abc\""))).isEqualTo("abc")
        assertThat(Parser.default().parse(StringReader("123"))).isEqualTo(123)
        // A single value character as the very last character of the input is not dropped.
        assertThat(Parser.default().parse(StringReader("5"))).isEqualTo(5)
        assertThat(Parser.default().parse(StringReader(" 5"))).isEqualTo(5)
        assertThat(
            (Parser.default().parse(StringReader("""{"a":{},"b":[[]]}""")) as JsonObject).toJsonString()
        ).isEqualTo("""{"a":{},"b":[[]]}""")
    }

    fun concurrentParsersHaveIsolatedCursorState() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..64).map { i ->
                pool.submit<Pair<Int, String>> {
                    val json = if (i % 2 == 0) sample else """[1, 2, {"k": $i}]"""
                    val result = Parser.default().parse(StringReader(json))
                    i to (result as JsonBase).toJsonString()
                }
            }
            futures.forEach { future ->
                val (i, actual) = future.get(30, TimeUnit.SECONDS)
                val expected = if (i % 2 == 0) (Parser.default().parse(StringReader(sample)) as JsonBase).toJsonString()
                else """[1,2,{"k":$i}]"""
                assertThat(actual).isEqualTo(expected)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    fun tokenPositionsAndPathAreConsistent() {
        val json = "{\"a\": [1, 2],\r\n\"b\": {\"c\": true}}"
        val tokens = mutableListOf<String>()
        val lexer = Lexer(StringReader(json))
        while (true) {
            val token = lexer.nextToken()
            tokens.add(token.toString())
            println("token=$token index=${lexer.index} line=${lexer.line}")
            if (token is EOF) break
        }
        assertThat(tokens).containsExactly("{", "\"a\"", ":", "[", "1", ",", "2", "]",
            ",", "\"b\"", ":", "{", "\"c\"", ":", "true", "}", "}", "EOF")

        val paths = mutableListOf<String>()
        val matcher = object : PathMatcher {
            override fun pathMatches(path: String) = true
            override fun onMatch(path: String, value: Any) {
                paths.add(path)
                println("path=$path value=$value")
            }
        }
        Parser.default(pathMatchers = listOf(matcher)).parse(StringReader(json))
        assertThat(paths).containsExactly("$.b.c")
    }
}
