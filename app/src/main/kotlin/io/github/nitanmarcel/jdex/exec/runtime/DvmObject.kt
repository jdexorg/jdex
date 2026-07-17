package io.github.nitanmarcel.jdex.exec.runtime

class DvmObject(val type: String) {
    val fields = HashMap<String, Any?>()
}

class UninitHost(val type: String)

class DvmThrowable(val type: String, message: String?, val obj: Any? = null) : RuntimeException(message)

enum class ClinitState { RUNNING, DONE }
