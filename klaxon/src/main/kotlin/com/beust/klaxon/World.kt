package com.beust.klaxon

import java.util.*

/**
 * The value/status stack of one parse invocation.
 *
 * All cursor state (lookahead, position, path stack, EOF semantics) lives in
 * [TokenCursor]; a [World] only tracks the stack of containers currently being built and
 * the parser [Status]es they were entered in.
 */
internal class World(var status: Status) {
    private val statusStack = LinkedList<Status>()
    private val valueStack = LinkedList<Any>()
    var result: Any? = null
    var parent = JsonObject()

    fun pushAndSet(status: Status, value: Any): World {
        pushStatus(status)
        pushValue(value)
        this.status = status
        return this
    }

    private fun pushStatus(status: Status): World {
        statusStack.addFirst(status)
        return this
    }

    private fun pushValue(value: Any): World {
        valueStack.addFirst(value)
        return this
    }

    fun popValue(): Any {
        return valueStack.removeFirst()
    }

    fun popStatus(): Status {
        return statusStack.removeFirst()
    }

    fun getFirstObject(): JsonObject {
        return valueStack.first as JsonObject
    }

    @Suppress("UNCHECKED_CAST")
    fun getFirstArray(): JsonArray<Any?> {
        return valueStack.first as JsonArray<Any?>
    }

    fun peekStatus(): Status {
        return statusStack[0]
    }

    fun hasValues(): Boolean {
        return valueStack.size > 1
    }
}
