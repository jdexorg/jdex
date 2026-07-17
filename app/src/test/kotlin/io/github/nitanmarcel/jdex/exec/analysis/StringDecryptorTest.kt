package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StringDecryptorTest {

    private val src = Fixtures.sample()

    @Test
    fun recoversXorLoopDecryptedString() {
        val anns = StringDecryptor(src).recover(src.method("LSample;", "secret()Ljava/lang/String;")!!)
        assertTrue(anns.any { it.text == "\"hi\"" }) { "got: $anns" }
    }

    @Test
    fun doesNotFoldPureConcatenationHelper() {
        val anns = StringDecryptor(src).recover(src.method("LSample;", "tag()Ljava/lang/String;")!!)
        assertTrue(anns.none { it.text == "\"hi x\"" }) { "tag() calls a pure concatenation helper, not a decryptor: $anns" }
    }

    @Test
    fun reconstructsDecryptorCalledInsideUnknownBranch() {
        val anns = StringDecryptor(src).recover(src.method("LSample;", "branchy(Z)Ljava/lang/String;")!!)
        assertTrue(anns.any { it.text == "\"SECRET\"" }) { "got: $anns" }
    }

    @Test
    fun recoversBase64Decryptor() {
        val anns = StringDecryptor(src).recover(src.method("LSample;", "secretB64()Ljava/lang/String;")!!)
        assertTrue(anns.any { it.text == "\"SECRET_B64\"" }) { "got: $anns" }
    }

    @Test
    fun recoversAesDecryptor() {
        val anns = StringDecryptor(src).recover(src.method("LSample;", "secretAes()Ljava/lang/String;")!!)
        assertTrue(anns.any { it.text == "\"SECRET_AES\"" }) { "got: $anns" }
    }

    @Test
    fun recoversWithStaticFieldKeyArg() {
        val anns = StringDecryptor(src).recover(src.method("LSample;", "secretFkey()Ljava/lang/String;")!!)
        assertTrue(anns.any { it.text == "\"SECRET_FKEY\"" }) { "got: $anns" }
    }

    @Test
    fun recoversStaticStringPoolRead() {
        val anns = StringDecryptor(src).recover(src.method("LSample;", "poolPick()Ljava/lang/String;")!!)
        assertTrue(anns.any { it.text == "\"hidden_in_pool\"" }) { "got: $anns" }
    }

    @Test
    fun recoversCallerClassKeyedDecryptor() {
        val anns = StringDecryptor(src).recover(src.method("LSample;", "secretCaller()Ljava/lang/String;")!!)
        assertTrue(anns.any { it.text == "\"SECRET_CALLER\"" }) { "got: $anns" }
    }

    @Test
    fun recoversInlineNewString() {
        val anns = StringDecryptor(src).recover(src.method("LSample;", "inlineStr()Ljava/lang/String;")!!)
        assertTrue(anns.any { it.text == "\"InLine_OK\"" }) { "got: $anns" }
    }

    @Test
    fun recoverAllAggregates() {
        val methods = listOf("secret()Ljava/lang/String;", "tag()Ljava/lang/String;", "loop(I)I")
            .mapNotNull { src.method("LSample;", it) }
        val anns = StringDecryptor(src).recoverAll(methods)
        assertTrue(anns.any { it.text == "\"hi\"" })
    }

    @Test
    fun recoversEveryBranchStringInReflectionFlattenedMethod() {
        val obf = Fixtures.obfuscapk()
        val classify = obf.method("Lcom/jdex/cfgdemo/MainActivity;", "classify(I)Ljava/lang/String;")!!
        val got = StringDecryptor(obf).recover(classify)
            .filterIsInstance<InsnAnnotation>().map { it.text.trim('"') }.toSet()
        assertEquals(setOf("negative", "zero", "small", "medium", "large", "huge"), got)
    }
}
