package com.beust.klaxon

import com.beust.klaxon.token.*
import java.io.Reader
import java.util.ArrayDeque

/**
 * The single owner of the mutable cursor state of one parse invocation:
 *
 *  - *lookahead*: a one token lookahead (backed by the [Lexer]), shared between a full parse
 *    (which must consume an EOF) and a streaming parse (which stops at a root container
 *    boundary and leaves the next token buffered for the caller);
 *  - *position*: the character index and line number of the current token, straight from
 *    the [Lexer];
 *  - *path stack*: an explicit, strictly balanced stack of container frames. Every container
 *    open is paired with a close, on both matcher hit and miss branches, so a deep failure
 *    can never leave a stale path behind for the next parser;
 *  - *EOF semantics*: [consumeRootValue] encodes how the two parsing modes drive the same
 *    token stream.
 *
 * A fresh cursor is created for every [Parser.parse] call, which guarantees that concurrent
 * parsers (even ones reusing the same streaming [Lexer]) never share path or lookahead state.
 */
internal class TokenCursor(
    val lexer: Lexer,
    private val pathMatchers: List<PathMatcher>,
    private val streaming: Boolean
) {
    constructor(reader: Reader, pathMatchers: List<PathMatcher>, streaming: Boolean)
        : this(Lexer(reader), pathMatchers, streaming)

    /** Character index of the current token. */
    val index: Int get() = lexer.index

    /** Current line number. */
    val line: Int get() = lexer.line

    /** The path of the element the current token belongs to, e.g. `$.store.book[0].author`. */
    val path: String
        get() = buildPath(frames, null)

    private sealed class Frame {
        /**
         * `.<key>` when this frame was opened as the value of an object pair, empty for the
         * root frame and for frames opened directly inside an array.
         */
        abstract val keySegment: String
    }

    private class ObjectFrame(override val keySegment: String = "") : Frame() {
        /** Key most recently consumed inside this object, awaiting its value (or null). */
        var pendingKey: String? = null
    }

    private class ArrayFrame(override val keySegment: String = "") : Frame() {
        /** Index of the element currently being read (-1 between elements). */
        var lastIndex = -1
    }

    private val frames = ArrayDeque<Frame>()

    private fun headObject() = frames.peekFirst() as? ObjectFrame
    private fun headArray() = frames.peekFirst() as? ArrayFrame

    fun peek(): Token = lexer.peek()

    fun next(): Token {
        val token = lexer.nextToken()
        advance(token)
        return token
    }

    /**
     * Whether the parse loop should keep going after [token] was just consumed.
     *
     * Full mode consumes everything up to and including EOF. Streaming mode keeps going while
     * nested inside a container and stops as soon as the root container closes (or EOF is
     * reached); the token following a streaming value stays buffered in the [Lexer].
     */
    fun consumeRootValue(token: Token, nestedBeforeToken: Boolean): Boolean {
        return if (streaming) {
            nestedBeforeToken ||
                (token !is RIGHT_BRACE && token !is RIGHT_BRACKET && token !is EOF)
        } else {
            token !is EOF
        }
    }

    /**
     * Container nesting depth just before [token] is applied, mirroring the legacy status
     * stack semantics: a closing token is considered nested while it still has a frame to pop.
     */
    fun nestedBefore(token: Token): Boolean {
        val depth = when (token) {
            is RIGHT_BRACE, is RIGHT_BRACKET -> frames.size - 1
            else -> frames.size
        }
        return depth > 0
    }

    private fun advance(token: Token) {
        when (token) {
            is LEFT_BRACE -> pushContainer(ObjectFrame(takeIncomingSegment()))
            is LEFT_BRACKET -> pushContainer(ArrayFrame(takeIncomingSegment()))
            is RIGHT_BRACE, is RIGHT_BRACKET -> closeContainer()
            is COMMA, is COLON, is EOF -> Unit
            is Value<*> -> onValue(token)
        }
    }

    /**
     * Resolve the path segment of a value/container about to be read: the pending object key
     * if there is one, otherwise the next array index for array elements.
     */
    private fun takeIncomingSegment(): String {
        headObject()?.let { obj ->
            val key = obj.pendingKey
            if (key != null) {
                obj.pendingKey = null
                return ".$key"
            }
        }
        headArray()?.let { it.lastIndex++ }
        return ""
    }

    private fun pushContainer(frame: Frame) {
        frames.addFirst(frame)
    }

    private fun closeContainer() {
        // Pair every push with a pop regardless of whether matchers were triggered.
        if (frames.isNotEmpty()) frames.removeFirst()
    }

    private fun onValue(token: Value<*>) {
        val current = frames.peekFirst()
        when {
            current is ObjectFrame -> {
                val pendingKey = current.pendingKey
                if (pendingKey != null) {
                    // A value for the key that was waiting: fire matchers and clear the key.
                    fireIfMatched(token.value, ".$pendingKey")
                    current.pendingKey = null
                } else if (lexer.peek() is COLON) {
                    // A name left of a colon: remember it for the value that follows.
                    current.pendingKey = token.value as? String
                }
            }
            current is ArrayFrame -> current.lastIndex++
        }
    }

    private fun fireIfMatched(value: Any?, ownSegment: String) {
        if (value == null || value is JsonBase) return
        // Build the path as if the pending primitive were itself a frame carrying ownSegment.
        val matchedPath = buildPath(frames, ownSegment)
        pathMatchers.forEach { matcher ->
            if (matcher.pathMatches(matchedPath)) {
                matcher.onMatch(matchedPath, value)
            }
        }
    }

    private fun buildPath(
        stack: ArrayDeque<Frame>,
        extraSegment: String?
    ): String = buildString {
        append("$")
        // Frames are stacked deepest-first, so render them root-first (reversed iteration).
        stack.reversed().forEach { frame ->
            append(frame.keySegment)
            if (frame is ArrayFrame) {
                append("[").append(frame.lastIndex).append("]")
            }
        }
        // The pending primitive is a child of the head frame, not a frame itself.
        if (extraSegment != null) append(extraSegment)
    }
}
