package com.beust.klaxon

import com.beust.klaxon.token.EOF
import com.beust.klaxon.token.Token
import java.io.Reader

/**
 * Owns the path stack used to compute JSON paths for [PathMatcher]s.
 *
 * The segments mirror the parser's value stack: [World] pairs every
 * `pushValue` with [push] and every `popValue` with [pop], so hit and miss
 * branches of a [PathMatcher] always see a balanced stack. A tracker is
 * created per parse, so an exception unwinding a deep parse can never leak
 * stale segments into the next independent parser.
 */
internal class PathTracker {
    private val segments = arrayListOf<Any>()

    fun push(value: Any) {
        segments.add(0, value)
    }

    fun pop() {
        segments.removeAt(0)
    }

    /**
     * The path of the current JSON element.
     * See https://github.com/json-path/JsonPath
     */
    val path: String
        get() {
            val result = arrayListOf("$")
            segments.reversed().forEach { value ->
                when (value) {
                    is JsonObject -> {
                        if (value.any()) {
                            result.add("." + value.keys.last().toString())
                        }
                    }
                    is JsonArray<*> -> {
                        result.add("[" + (value.size - 1) + "]")
                    }
                    else -> {
                        result.add("." + value)
                    }
                }
            }
            return result.joinToString("")
        }
}

/**
 * Internal token cursor: the single owner of token lookahead, lexer position,
 * the [PathTracker] stack and EOF semantics for one parse.
 *
 * Both the full and the streaming parse loops advance exclusively through
 * this cursor, so the two modes can no longer drift apart on token
 * advancement, position reporting or end-of-input handling. A cursor is
 * created per `parse` call, which keeps concurrently used [Parser] instances
 * fully isolated from each other.
 *
 * Closing the underlying [Reader] stays the caller's responsibility, exactly
 * as it was when the loops talked to the [Lexer] directly.
 */
internal class TokenCursor(passedLexer: Lexer?, reader: Reader) {
    private val lexer: Lexer = passedLexer ?: Lexer(reader)

    /**
     * The path stack owned by this cursor, shared with the parse [World].
     */
    val pathTracker = PathTracker()

    /**
     * The index of the character the lexer is currently on.
     */
    var index: Int = 0
        private set

    /**
     * The current line (1-based).
     */
    var line: Int = 1
        private set

    /**
     * True once the end of the input has been observed. EOF is sticky:
     * once exhausted, the cursor stays exhausted.
     */
    var exhausted: Boolean = false
        private set

    init {
        index = lexer.index
        line = lexer.line
    }

    /**
     * Look at the next token without consuming it. Does not advance the
     * reported position.
     */
    fun peek(): Token {
        val token = lexer.peek()
        if (token is EOF) exhausted = true
        return token
    }

    /**
     * Consume the next token and update the reported position.
     */
    fun next(): Token {
        val token = lexer.nextToken()
        index = lexer.index
        line = lexer.line
        if (token is EOF) exhausted = true
        return token
    }
}
