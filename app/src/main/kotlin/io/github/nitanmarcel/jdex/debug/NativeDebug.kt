package io.github.nitanmarcel.jdex.debug

import io.github.nitanmarcel.jdex.disasm.ElfArch
import java.io.File

sealed interface NativeDebug {
    data class Managed(val lldbServerPath: String) : NativeDebug
    data class Remote(val host: String, val port: Int) : NativeDebug
}

object NdkLldb {
    fun defaultNdkRoot(): File? =
        DeviceBridge.adbPath()?.parentFile?.parentFile?.resolve("ndk")?.listFiles()
            ?.filter { it.isDirectory }?.maxByOrNull { it.name }

    fun lldbServer(ndkRoot: File, abi: String): String? {
        val archDir = ElfArch.fromAndroidAbi(abi)?.ndkLibDir ?: return null
        val host = ndkRoot.resolve("toolchains/llvm/prebuilt").listFiles()?.firstOrNull { it.isDirectory } ?: return null
        val clang = host.resolve("lib/clang").listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name } ?: return null
        return clang.resolve("lib/linux/$archDir/lldb-server").takeIf { it.exists() }?.absolutePath
    }
}
