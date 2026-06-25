package io.github.nitanmarcel.jdex.debug

import io.github.nitanmarcel.jdex.disasm.ElfArch
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

class TargetDescription private constructor(val registers: List<RegInfo>, val arch: ElfArch) {
    companion object {
        fun discover(rsp: RspClient): TargetDescription {
            val lldbRegs = collectRegisterInfo(rsp)
            val lldbArch = rsp.command("qProcessInfo").let { if (it.isEmpty() || it[0] == 'E') null else archFromProcessInfo(it) }
            if (lldbRegs.isNotEmpty() && lldbArch != null) return TargetDescription(assignOffsets(lldbRegs), lldbArch)

            val (xmlRegs, xmlArch) = readTargetXml(rsp)
            val regs = lldbRegs.ifEmpty { xmlRegs }
            val arch = lldbArch ?: xmlArch ?: ElfArch.ARM64
            return TargetDescription(assignOffsets(regs), arch)
        }

        private fun collectRegisterInfo(rsp: RspClient): List<RegInfo> {
            val regs = ArrayList<RegInfo>()
            var i = 0
            while (true) { val info = parseRegisterInfo(rsp.command("qRegisterInfo${i.toString(16)}")) ?: break; regs.add(info); i++ }
            return regs
        }

        private fun archFromProcessInfo(reply: String): ElfArch {
            val fields = reply.split(';').mapNotNull {
                val k = it.substringBefore(':', "")
                if (k.isEmpty()) null else k to it.substringAfter(':')
            }.toMap()
            when (fields["cputype"]?.toLongOrNull(16)) {
                0x0100000cL -> return ElfArch.ARM64
                0xcL -> return ElfArch.ARM
                0x01000007L -> return ElfArch.X86_64
                0x7L -> return ElfArch.X86
            }
            val triple = fields["triple"]
                ?.let { hex -> runCatching { hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.US_ASCII) }.getOrNull() }
                ?.lowercase() ?: ""
            return archFromName(triple) ?: if (fields["ptrsize"] == "8") ElfArch.ARM64 else ElfArch.ARM
        }

        private fun readTargetXml(rsp: RspClient): Pair<List<RegInfo>, ElfArch?> {
            runCatching { rsp.command("?") }
            val root = parseXml(readXfer(rsp, "target.xml")) ?: return emptyList<RegInfo>() to null
            val regs = ArrayList(regsOf(root))
            val includes = root.getElementsByTagName("xi:include")
            for (i in 0 until includes.length) {
                val href = (includes.item(i) as Element).getAttribute("href")
                if (href.isNotEmpty()) parseXml(readXfer(rsp, href))?.let { regs += regsOf(it) }
            }
            return regs to archOf(root)
        }

        private fun readXfer(rsp: RspClient, annex: String): String {
            val sb = StringBuilder()
            var off = 0
            val deadline = System.currentTimeMillis() + 5000
            while (true) {
                val reply = rsp.command("qXfer:features:read:$annex:${off.toString(16)},fff")
                if (reply.isEmpty() || reply[0] == 'E') {
                    if (off == 0 && System.currentTimeMillis() < deadline) { Thread.sleep(150); continue }
                    break
                }
                sb.append(reply, 1, reply.length)
                off += reply.length - 1
                if (reply[0] == 'l') break
            }
            return sb.toString()
        }

        private fun parseXml(text: String): Element? {
            if (text.isEmpty()) return null
            return runCatching {
                val factory = DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = false
                    isValidating = false
                    runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
                    runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                    runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                }
                val builder = factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }
                builder.parse(InputSource(StringReader(text))).documentElement
            }.getOrNull()
        }

        private fun regsOf(root: Element): List<RegInfo> {
            val list = root.getElementsByTagName("reg")
            return (0 until list.length).mapNotNull { idx ->
                val e = list.item(idx) as Element
                val name = e.getAttribute("name").ifEmpty { return@mapNotNull null }
                val bitsize = e.getAttribute("bitsize").toIntOrNull() ?: return@mapNotNull null
                val set = e.getAttribute("group").ifEmpty { e.getAttribute("set") }
                RegInfo(name, bitsize, -1, e.getAttribute("encoding"), set, false, e.getAttribute("generic"))
            }
        }

        private fun archOf(root: Element): ElfArch? {
            val list = root.getElementsByTagName("architecture")
            val text = if (list.length > 0) list.item(0).textContent.trim().lowercase() else ""
            return if (text.isEmpty()) null else archFromName(text)
        }

        private fun archFromName(name: String): ElfArch? = when {
            name.startsWith("aarch64") || name.startsWith("arm64") -> ElfArch.ARM64
            name.contains("x86-64") || name.startsWith("x86_64") -> ElfArch.X86_64
            name.startsWith("i386") || name.startsWith("i486") || name.startsWith("i586") || name.startsWith("i686") -> ElfArch.X86
            name.startsWith("mips64") -> ElfArch.MIPS64
            name.startsWith("mips") -> ElfArch.MIPS
            name.startsWith("arm") || name.startsWith("thumb") -> ElfArch.ARM
            else -> null
        }
    }
}
