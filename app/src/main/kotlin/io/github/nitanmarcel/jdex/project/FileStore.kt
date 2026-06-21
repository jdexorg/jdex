package io.github.nitanmarcel.jdex.project

interface FileStore {
    fun importedFiles(): List<StoredFile>
    fun saveFile(path: String, bytes: ByteArray)
    fun removeFile(path: String)
}

object NoFileStore : FileStore {
    override fun importedFiles(): List<StoredFile> = emptyList()
    override fun saveFile(path: String, bytes: ByteArray) = Unit
    override fun removeFile(path: String) = Unit
}

class StoredFile(val path: String, val bytes: ByteArray)
