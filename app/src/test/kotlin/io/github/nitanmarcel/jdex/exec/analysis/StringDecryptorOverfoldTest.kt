package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.Fixtures
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StringDecryptorOverfoldTest {

    private val src = Fixtures.sample()

    private fun folds(method: String) =
        StringDecryptor(src).recover(src.method("LOverfold;", method)!!).filterIsInstance<InsnAnnotation>()

    @Test
    fun doesNotFoldNonDecoderOnPlaintextArg() {
        assertFalse(folds("useClassifyPlain()Ljava/lang/String;").any { it.text.contains("large") || it.text.contains("small") }) {
            "classify() on a plaintext constant must not be folded (it is not a decryptor)"
        }
    }

    @Test
    fun stillFoldsXorDecoder() {
        assertTrue(folds("useDecCipher()Ljava/lang/String;").any { it.text == "\"hi\"" }) {
            "an XOR decoder must still be folded regardless of input shape"
        }
    }

    @Test
    fun doesNotFoldNonDecoderEvenOnEncryptedLookingArg() {
        assertFalse(folds("useClassifyCipher()Ljava/lang/String;").any { it.text.contains("large") || it.text.contains("small") }) {
            "folding is gated on being a decoder, not on the argument looking like ciphertext"
        }
    }
}
