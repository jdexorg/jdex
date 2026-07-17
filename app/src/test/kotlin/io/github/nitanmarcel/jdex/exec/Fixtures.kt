package io.github.nitanmarcel.jdex.exec

import io.github.nitanmarcel.jdex.exec.input.DexInputSource
import java.io.File

object Fixtures {
    fun sample(): DexInputSource = load("/fixtures/sample.dex")

    fun flattened(): DexInputSource = load("/fixtures/flattened.dex")

    fun opaque(): DexInputSource = load("/fixtures/opaque.dex")

    fun flattenedLoop(): DexInputSource = load("/fixtures/flattened_loop.dex")

    fun strconcat(): DexInputSource = load("/fixtures/strconcat.dex")

    fun a1demo(): DexInputSource = load("/fixtures/a1demo.dex")

    fun tryCatch(): DexInputSource = load("/fixtures/trycatch.dex")

    fun frameworkCall(): DexInputSource = load("/fixtures/frameworkcall.dex")

    fun obfuscapk(): DexInputSource = load("/fixtures/obfuscapk-cfgdemo.apk", ".apk")

    fun typeFidelity(): DexInputSource = load("/fixtures/typefidelity.dex")

    fun ops(): DexInputSource = load("/fixtures/ops.dex")

    fun flt(): DexInputSource = load("/fixtures/flt.dex")

    fun mutStatic(): DexInputSource = load("/fixtures/mutstatic.dex")

    fun degProbe(): DexInputSource = load("/fixtures/degprobe.dex")

    fun reflect(): DexInputSource = load("/fixtures/reflect.dex")

    fun dispatch(): DexInputSource = load("/fixtures/dispatch.dex")

    fun emufix(): DexInputSource = load("/fixtures/emufix.dex")

    private fun load(resource: String, ext: String = ".dex"): DexInputSource {
        val bytes = Fixtures::class.java.getResourceAsStream(resource)!!.readBytes()
        val tmp = File.createTempFile("jdex-fixture", ext).apply { deleteOnExit() }
        tmp.writeBytes(bytes)
        return DexInputSource.load(tmp)
    }
}
