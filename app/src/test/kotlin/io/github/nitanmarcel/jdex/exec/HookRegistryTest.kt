package io.github.nitanmarcel.jdex.exec

import io.github.nitanmarcel.jdex.exec.input.DexInputSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class HookRegistryTest {

    private fun source(): DexInputSource {
        val bytes = HookRegistryTest::class.java.getResourceAsStream("/jni/jnicrypt-caller.dex")!!.readBytes()
        val dex = File.createTempFile("caller", ".dex").apply { deleteOnExit(); writeBytes(bytes) }
        return DexInputSource.load(dex)
    }

    @Test
    fun replaceReturnShortCircuitsTheCall() {
        val src = source()
        val hooks = HookRegistry()
        hooks.add("Lcom/jdex/crypto/Native;->decryptBytes([B)Ljava/lang/String;") { call -> call.replace("HOOKED") }
        val vm = Vm(src, hooks = hooks)
        val run = src.method("Lcom/jdex/crypto/App;", "run()Ljava/lang/String;")!!
        assertEquals("HOOKED", vm.invoke(run))
    }

    @Test
    fun listAndClear() {
        val reg = HookRegistry()
        val a = reg.add("La;->m()V") { }
        val b = reg.add("Lb;->n()V") { }
        assertEquals(setOf(a to "La;->m()V", b to "Lb;->n()V"), reg.list().toSet())
        reg.remove(a)
        assertEquals(listOf(b to "Lb;->n()V"), reg.list())
        reg.clear()
        assertEquals(emptyList<Pair<Int, String>>(), reg.list())
        assertEquals(true, reg.isEmpty())
    }

    @Test
    fun noHookLeavesBehaviorUnchanged() {
        val src = source()
        val vm = Vm(src, hooks = HookRegistry())
        val run = src.method("Lcom/jdex/crypto/App;", "run()Ljava/lang/String;")!!
        assertEquals(false, vm.invoke(run) == "HOOKED")
    }
}
