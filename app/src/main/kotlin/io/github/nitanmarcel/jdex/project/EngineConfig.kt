package io.github.nitanmarcel.jdex.project

enum class EnginePass(val id: String, val label: String) {
    STRING_VALUES("values", "String decryption"),
    UNREFLECT("unreflect", "Reflection unwinding"),
    DEFLATTEN("deflatten", "Control-flow deflattening"),
}

data class EngineConfig(
    val emulatorEnabled: Boolean = true,
    val decryptStringsAtStartup: Boolean = false,
    val passes: Set<EnginePass> = EnginePass.entries.toSet(),
    val codeCleanup: Boolean = true,
) {
    fun enabled(pass: EnginePass): Boolean = emulatorEnabled && pass in passes

    val cleanupEnabled: Boolean get() = emulatorEnabled && codeCleanup

    companion object {
        val DEFAULT = EngineConfig()
    }
}
