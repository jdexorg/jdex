package io.github.nitanmarcel.jdex.project

class AnnotationStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile
    private var onChange: (() -> Unit)? = null

    @Volatile
    private var batching = false

    fun set(anchor: String, text: String?) = update {
        if (text.isNullOrEmpty()) map.remove(anchor) else map[anchor] = text
    }

    fun clear() = update { map.clear() }

    fun all(): Map<String, String> = HashMap(map)

    fun observe(callback: () -> Unit) {
        onChange = callback
    }

    fun batch(block: () -> Unit) {
        batching = true
        try {
            block()
        } finally {
            batching = false
        }
        onChange?.invoke()
    }

    private fun update(block: () -> Unit) {
        block()
        if (!batching) onChange?.invoke()
    }
}
