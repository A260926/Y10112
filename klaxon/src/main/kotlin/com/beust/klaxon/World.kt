package com.beust.klaxon

import java.util.*

class World internal constructor(
    var status: Status,
    val pathMatchers: List<PathMatcher>,
    private val pathTracker: PathTracker
) {
    constructor(status: Status, pathMatchers: List<PathMatcher> = emptyList())
            : this(status, pathMatchers, PathTracker())

    private val statusStack = LinkedList<Status>()
    private val valueStack = LinkedList<Any>()
    var result : Any? = null
    var parent = JsonObject()

    /**
     * The path of the current JSON element.
     * See https://github.com/json-path/JsonPath
     */
    val path: String get() = pathTracker.path

    fun pushAndSet(status: Status, value: Any) : World {
        pushStatus(status)
        pushValue(value)
        this.status = status
        return this
    }

    private fun pushStatus(status: Status) : World {
        statusStack.addFirst(status)
        return this
    }

    private fun pushValue(value: Any) : World {
        valueStack.addFirst(value)
        pathTracker.push(value)
        return this
    }

    /**
     * The index of the character we are currently on.
     */
    var index: Int = 0

    /**
     * The current line.
     */
    var line: Int = 0

    fun popValue() : Any {
        val result = valueStack.removeFirst()
        pathTracker.pop()
        return result
    }

    fun popStatus() : Status {
        return statusStack.removeFirst()
    }

    fun getFirstObject() : JsonObject {
        return valueStack.first as JsonObject
    }

    @Suppress("UNCHECKED_CAST")
    fun getFirstArray() : JsonArray<Any?> {
        return valueStack.first as JsonArray<Any?>
    }

    fun peekStatus() : Status {
        return statusStack[0]
    }

    fun isNestedStatus() : Boolean {
        return statusStack.size > 1
    }

    fun hasValues() : Boolean {
        return valueStack.size > 1
    }

    internal fun foundValue() {
        val first = valueStack.peekFirst()
        if (first is JsonObject && first.isNotEmpty()) {
            val value = first.values.last()
            if (value != null && value !is JsonArray<*> && value !is JsonObject) {
                pathMatchers.filter {
                    it.pathMatches(path)
                }.forEach {
                    it.onMatch(path, value)
                }
            }
        }
    }

}
