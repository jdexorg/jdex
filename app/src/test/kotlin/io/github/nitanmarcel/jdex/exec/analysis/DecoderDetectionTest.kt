package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.Fixtures
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DecoderDetectionTest {
    private val src = Fixtures.obfuscapk()
    private val sd = StringDecryptor(src)
    private fun m(cls: String, id: String) = src.method(cls, id)!!

    @Test fun cipherDecryptorIsDetected() {
        assertTrue(sd.isDecoderMethod(m("Lcom/decryptstringmanager/DecryptString;", "decipher(Ljava/lang/String;)Ljava/lang/String;")))
    }

    @Test fun wrapperReachingCipherIsDetected() {
        assertTrue(sd.isDecoderMethod(m("Lcom/decryptstringmanager/DecryptString;", "decryptString(Ljava/lang/String;)Ljava/lang/String;")))
    }

    @Test fun appSwitchMethodIsNotDecoder() {
        assertFalse(sd.isDecoderMethod(m("Lcom/jdex/cfgdemo/MainActivity;", "classify(I)Ljava/lang/String;")))
    }

    @Test fun flattenedSwitchMethodIsNotDecoder() {
        val flat = Fixtures.flattenedLoop()
        assertFalse(StringDecryptor(flat).isDecoderMethod(flat.method("Lcom/jdex/cfgdemo/MainActivity;", "classify(I)Ljava/lang/String;")!!))
    }
}
