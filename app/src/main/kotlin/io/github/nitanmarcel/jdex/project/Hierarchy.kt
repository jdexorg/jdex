package io.github.nitanmarcel.jdex.project

class HClass(val rawName: String, val fullName: String, val pkg: String, val display: String) {
    override fun toString() = display
}

class HField(val display: String, val name: String, val declaringRawName: String) {
    override fun toString() = display
}

class HMethod(val display: String, val shortId: String, val declaringRawName: String) {
    override fun toString() = display
}

class HMembers(
    val fields: List<HField>,
    val methods: List<HMethod>,
    val innersByMethod: Map<String, List<HClass>>,
    val looseInners: List<HClass>,
)
