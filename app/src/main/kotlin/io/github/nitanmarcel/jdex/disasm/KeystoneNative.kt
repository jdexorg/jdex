package io.github.nitanmarcel.jdex.disasm

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference

internal interface KeystoneLib : Library {
    fun ks_open(arch: Int, mode: Int, ks: PointerByReference): Int
    fun ks_option(ks: Pointer, type: Int, value: NativeLong): Int
    fun ks_asm(ks: Pointer, str: String, address: Long, encoding: PointerByReference, encSize: NativeLongByReference, statCount: NativeLongByReference): Int
    fun ks_free(p: Pointer)
    fun ks_close(ks: Pointer): Int
    fun ks_errno(ks: Pointer): Int
    fun ks_strerror(code: Int): String?

    companion object {
        val INSTANCE: KeystoneLib by lazy { Native.load("keystone", KeystoneLib::class.java) }
    }
}
