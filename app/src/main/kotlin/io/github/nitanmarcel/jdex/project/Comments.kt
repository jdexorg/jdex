package io.github.nitanmarcel.jdex.project

interface CommentStore {
    fun get(key: String): String?
    fun set(key: String, text: String?)
    fun all(): Map<String, String>
}

object NoComments : CommentStore {
    override fun get(key: String): String? = null
    override fun set(key: String, text: String?) = Unit
    override fun all(): Map<String, String> = emptyMap()
}
