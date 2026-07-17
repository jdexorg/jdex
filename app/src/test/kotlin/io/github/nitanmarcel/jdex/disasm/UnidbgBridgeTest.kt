package io.github.nitanmarcel.jdex.disasm

import com.github.unidbg.arm.backend.Unicorn2Factory
import com.github.unidbg.linux.android.AndroidEmulatorBuilder
import com.github.unidbg.linux.android.AndroidResolver
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class UnidbgBridgeTest {
    @Test
    fun loadsArm64SoAndRunsJniOnLoad() {
        val bytes = UnidbgBridgeTest::class.java.getResourceAsStream("/jni/libjnitest_arm64-v8a.so")!!.readBytes()
        val so = File.createTempFile("jnitest", ".so").apply { deleteOnExit(); writeBytes(bytes) }
        val emulator = AndroidEmulatorBuilder.for64Bit().addBackendFactory(Unicorn2Factory(true)).build()
        try {
            emulator.memory.setLibraryResolver(AndroidResolver(23))
            val vm = emulator.createDalvikVM(null as File?)
            vm.setVerbose(false)
            val dm = vm.loadLibrary(so, false)
            dm.callJNI_OnLoad(emulator)
            assertTrue(dm.module.base > 0) { "loaded module must have a base address" }
        } finally {
            emulator.close()
        }
    }
}
