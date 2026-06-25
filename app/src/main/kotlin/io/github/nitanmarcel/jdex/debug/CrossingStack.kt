package io.github.nitanmarcel.jdex.debug

enum class Owner { DEX, NATIVE }

data class Crossing(val from: Owner, val to: Owner, val resumeToken: String)

class CrossingStack(start: Owner) {
    var owner: Owner = start
        private set
    private val stack = ArrayDeque<Crossing>()

    fun cross(to: Owner, resumeToken: String) {
        stack.addLast(Crossing(owner, to, resumeToken))
        owner = to
    }

    fun back(): Crossing? {
        val c = stack.removeLastOrNull() ?: return null
        owner = c.from
        return c
    }

    fun depth(): Int = stack.size

    fun peek(): Crossing? = stack.lastOrNull()
}
