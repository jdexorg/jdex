package io.github.nitanmarcel.jdex.exec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VmReflectiveDispatchTest {

    private val src = Fixtures.obfuscapk()
    private val obfuscate = src.method(
        "Lcom/apireflectionmanager/ApiReflection;",
        "obfuscate(ILjava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
    )!!

    @Test
    fun dispatchesReflectedDecryptorAndReturnsPlaintext() {
        val vm = Vm(src)
        val cases = mapOf(
            1 to ("d4aa5936a2404bca52acd9687db6a140" to "negative"),
            2 to ("fd712d071769baea8542a36ce3418933" to "zero"),
            5 to ("9fe5f32970ad1aec7ebc0d6b3162aab6" to "large"),
        )
        for ((idx, pair) in cases) {
            val (enc, plain) = pair
            val r = vm.invoke(obfuscate, listOf(idx, null, arrayOf<Any?>(enc)))
            assertEquals(plain, r) { "obfuscate($idx) must dispatch through reflection to the decryptor" }
        }
    }
}
