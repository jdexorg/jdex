package io.github.nitanmarcel.jdex.project

interface BookmarkStore {
    fun toggle(line: Int): Boolean
    fun bookmarks(): Set<Int>
}

object NoBookmarks : BookmarkStore {
    override fun toggle(line: Int) = false
    override fun bookmarks(): Set<Int> = emptySet()
}
