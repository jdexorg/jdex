package io.github.nitanmarcel.jdex.debug

import java.io.File

object Jrunas {
    fun extract(abi: String): File? {
        val res = Jrunas::class.java.getResourceAsStream("/jrunas/$abi/jrunas") ?: return null
        val f = File.createTempFile("jrunas-$abi-", "").apply { deleteOnExit() }
        res.use { input -> f.outputStream().use { input.copyTo(it) } }
        f.setExecutable(true)
        return f
    }
}
