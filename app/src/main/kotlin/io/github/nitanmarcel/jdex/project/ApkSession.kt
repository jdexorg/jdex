package io.github.nitanmarcel.jdex.project

import com.android.apksig.ApkVerifier
import jadx.api.DecompilationMode as JadxMode
import jadx.api.ICodeInfo
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import jadx.api.JavaField
import jadx.api.JavaMethod
import jadx.api.ResourceFile
import jadx.api.ResourceType
import jadx.api.metadata.annotations.InsnCodeOffset
import jadx.api.metadata.annotations.NodeDeclareRef
import jadx.api.plugins.input.insns.Opcode
import jadx.core.dex.instructions.args.ArgType
import jadx.core.dex.visitors.blocks.JdexMultiEntryFix
import jadx.core.dex.visitors.blocks.JdexCfgSpikeCapture
import jadx.core.dex.visitors.blocks.JdexBlockPrunePass
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.TreeMap
import java.util.zip.ZipFile

class ApkSession private constructor(
    private val jadx: JadxDecompiler,
    val root: ProjectNode,
    val appPackage: String?,
    val certificateCount: Int,
    private val manifestText: String,
    val malformedDexes: List<MalformedDex>,
    private val tempDexFiles: List<File>,
    private val input: File,
    val config: EngineConfig = EngineConfig.DEFAULT,
) : AutoCloseable {

    private val inputIsZip by lazy { runCatching { ZipFile(input).use {} }.isSuccess }

    fun entryNames(): List<String> = runCatching {
        if (inputIsZip) ZipFile(input).use { zip ->
            zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
        } else listOf(input.name)
    }.getOrDefault(emptyList())

    fun readFile(name: String): ByteArray = runCatching {
        if (inputIsZip) ZipFile(input).use { zip ->
            zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes() } } ?: ByteArray(0)
        } else if (name == input.name) input.readBytes() else ByteArray(0)
    }.getOrDefault(ByteArray(0))

    fun mainActivity(): String? = runCatching {
        if (manifestText.isBlank()) return@runCatching null
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestText.byteInputStream())
        val activities = doc.getElementsByTagName("activity")
        for (i in 0 until activities.length) {
            val activity = activities.item(i) as org.w3c.dom.Element
            val filters = activity.getElementsByTagName("intent-filter")
            for (j in 0 until filters.length) {
                val filter = filters.item(j) as org.w3c.dom.Element
                if (hasChild(filter, "action", "android.intent.action.MAIN") &&
                    hasChild(filter, "category", "android.intent.category.LAUNCHER")
                ) {
                    return@runCatching resolveActivity(activity.getAttribute("android:name").ifEmpty { activity.getAttribute("name") })
                }
            }
        }
        null
    }.getOrNull()

    private fun hasChild(parent: org.w3c.dom.Element, tag: String, name: String): Boolean {
        val nodes = parent.getElementsByTagName(tag)
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as org.w3c.dom.Element
            if (e.getAttribute("android:name").ifEmpty { e.getAttribute("name") } == name) return true
        }
        return false
    }

    private fun resolveActivity(name: String): String? {
        val pkg = appPackage
        return when {
            name.isEmpty() -> null
            pkg == null -> name
            name.startsWith(".") -> pkg + name
            !name.contains(".") -> "$pkg.$name"
            else -> name
        }
    }

    private val classesByRawName by lazy { jadx.classesWithInners.associateBy { it.rawName } }

    fun usages(symbol: Symbol): List<Usage>? {
        val node = resolveNode(symbol) ?: return null
        return runCatching { node.useIn.mapNotNull(::usageOf) }.getOrDefault(emptyList())
    }

    private fun resolveNode(symbol: Symbol): jadx.api.JavaNode? {
        val raw = symbol.declaringClassName() ?: return null
        val cls = classesByRawName[raw] ?: return null
        return when (symbol.kind) {
            SymbolKind.TYPE -> cls
            SymbolKind.METHOD -> symbol.member?.let { cls.searchMethodByShortId(it) }
            SymbolKind.FIELD -> symbol.member?.substringBefore(':')?.let { name -> cls.fields.firstOrNull { it.name == name } }
            else -> null
        }
    }

    private fun usageOf(node: jadx.api.JavaNode): Usage? = when (node) {
        is jadx.api.JavaMethod -> node.declaringClass?.let {
            val shortId = runCatching { node.methodNode.methodInfo.shortId }.getOrNull()
            Usage("${it.rawName.substringAfterLast('.')}.${node.name}", it.rawName, shortId, null)
        }
        is jadx.api.JavaField -> node.declaringClass?.let {
            Usage("${it.rawName.substringAfterLast('.')}.${node.name}", it.rawName, null, node.name)
        }
        is jadx.api.JavaClass -> Usage(node.rawName, node.rawName, null, null)
        else -> null
    }

    class Decompiled(val title: String, val code: String, val sync: CodeSync)

    enum class DecompileMode(val label: String, val sync: Boolean) {
        JDEC("JDec", true),
        JAVA("Code", true),
        SIMPLE("Simple", true),
        FALLBACK("Fallback", false),
    }

    private val engineSource by lazy {
        try {
            io.github.nitanmarcel.jdex.exec.input.DexInputSource.load(input)
        } catch (t: Throwable) {
            java.util.logging.Logger.getLogger("jdex").log(
                java.util.logging.Level.WARNING, "Engine load failed; all deobfuscation disabled for this input", t)
            null
        }
    }

    fun engineSource(): io.github.nitanmarcel.jdex.exec.MethodSource? = engineSource

    private val engineCtx by lazy { engineSource?.let { io.github.nitanmarcel.jdex.exec.EngineContext(it) } }

    private val stringDecryptor by lazy {
        engineSource?.let { io.github.nitanmarcel.jdex.exec.analysis.StringDecryptor(it, ctx = engineCtx) }
    }

    private val nativeDecryptorsLazy = lazy {
        val src = engineSource ?: return@lazy emptyList<io.github.nitanmarcel.jdex.disasm.NativeStringDecryptor>()
        val libs = armNativeLibBytes()
        if (libs.isEmpty()) return@lazy emptyList()
        val bridge = io.github.nitanmarcel.jdex.disasm.VmJavaBridge(src)
        libs.mapNotNull { runCatching { io.github.nitanmarcel.jdex.disasm.NativeStringDecryptor(it, bridge) }.getOrNull() }
    }
    private val nativeDecryptors get() = nativeDecryptorsLazy.value

    private val classDeob = java.util.concurrent.ConcurrentHashMap<String, List<io.github.nitanmarcel.jdex.exec.analysis.InsnAnnotation>>()

    fun deobStringsForClass(rawName: String): List<io.github.nitanmarcel.jdex.exec.analysis.InsnAnnotation> =
        classDeob.getOrPut(rawName) {
            val src = engineSource ?: return@getOrPut emptyList()
            val sd = stringDecryptor ?: return@getOrPut emptyList()
            val methods = src.methodsOf(descOf(rawName))
            val dex = runCatching { sd.recoverAll(methods) }.getOrDefault(emptyList())
            val native = nativeDecryptors.flatMap { runCatching { it.recoverCallSites(methods) }.getOrDefault(emptyList()) }
            dex + native
        }

    private fun armNativeLibBytes(): List<ByteArray> {
        val names = entryNames().filter { it.startsWith("lib/") && it.endsWith(".so") }
        val abi = listOf("arm64-v8a", "armeabi-v7a").firstOrNull { a -> names.any { it.startsWith("lib/$a/") } } ?: return emptyList()
        return names.filter { it.startsWith("lib/$abi/") }.map { readFile(it) }.filter { it.isNotEmpty() }
    }

    private val cfgAnalysis by lazy { engineSource?.let { io.github.nitanmarcel.jdex.exec.analysis.CfgAnalysis(it, ctx = engineCtx) } }
    private val cfgFactsCache = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<io.github.nitanmarcel.jdex.exec.analysis.MethodCfgFacts>>()

    private fun cfgFactsFor(rawName: String, shortId: String): io.github.nitanmarcel.jdex.exec.analysis.MethodCfgFacts? {
        val analysis = cfgAnalysis ?: return null
        val src = engineSource ?: return null
        return cfgFactsCache.computeIfAbsent("$rawName#$shortId") {
            val m = src.method("L${rawName.replace('.', '/')};", shortId)
            java.util.Optional.ofNullable(m?.let { runCatching { analysis.analyze(it) }.getOrNull() })
        }.orElse(null)
    }

    private val valueResolutionPass = io.github.nitanmarcel.jdex.exec.analysis.ValueResolutionPass()
    private val returnValuePass = io.github.nitanmarcel.jdex.exec.analysis.ReturnValuePass()
    private val reflectionPass = io.github.nitanmarcel.jdex.exec.analysis.ReflectionPass()
    private val reflectiveDispatch by lazy { engineSource?.let { io.github.nitanmarcel.jdex.exec.analysis.ReflectiveDispatch(it) } }
    private val valueAnnCache = java.util.concurrent.ConcurrentHashMap<String, List<io.github.nitanmarcel.jdex.exec.analysis.InsnAnnotation>>()

    internal fun valueAnnotationsFor(rawName: String, shortId: String): List<io.github.nitanmarcel.jdex.exec.analysis.InsnAnnotation> {
        val analysis = cfgAnalysis ?: return emptyList()
        val src = engineSource ?: return emptyList()
        return valueAnnCache.computeIfAbsent("$rawName#$shortId") {
            val m = src.method("L${rawName.replace('.', '/')};", shortId) ?: return@computeIfAbsent emptyList()
            val result = runCatching { analysis.result(m) }.getOrNull() ?: return@computeIfAbsent emptyList()
            if (!result.complete) return@computeIfAbsent emptyList()
            val out = ArrayList<io.github.nitanmarcel.jdex.exec.analysis.InsnAnnotation>()
            for (a in valueResolutionPass.run(m, result)) {
                if (a !is io.github.nitanmarcel.jdex.exec.analysis.InsnAnnotation || a.text.startsWith("\"")) continue
                out += a.copy(text = "= ${a.text}")
            }
            for (a in returnValuePass.run(m, result)) {
                if (a !is io.github.nitanmarcel.jdex.exec.analysis.InsnAnnotation || a.text.removePrefix("returns ").startsWith("\"")) continue
                out += a
            }
            for (a in reflectionPass.run(m, result)) {
                if (a is io.github.nitanmarcel.jdex.exec.analysis.InsnAnnotation) out += a
            }
            reflectiveDispatch?.let { out += runCatching { it.annotations(m, result) }.getOrDefault(emptyList()) }
            out
        }
    }

    internal fun valueOverlayFor(rawName: String): List<io.github.nitanmarcel.jdex.exec.analysis.InsnAnnotation> {
        val src = engineSource ?: return emptyList()
        return src.methodsOf("L${rawName.replace('.', '/')};").flatMap { valueAnnotationsFor(rawName, it.ref.shortId) }
    }

    private val typePinCache = java.util.concurrent.ConcurrentHashMap<String, Map<Int, ArgType>>()

    private fun refArgType(v: Any?): ArgType? {
        val desc = when (v) {
            is io.github.nitanmarcel.jdex.exec.runtime.DvmObject -> v.type
            is String -> "Ljava/lang/String;"
            is io.github.nitanmarcel.jdex.exec.runtime.UnknownVal -> v.type
            else -> null
        } ?: return null
        if (desc == "Ljava/lang/Object;" || !(desc.startsWith("L") || desc.startsWith("["))) return null
        val t = runCatching { ArgType.parse(desc) }.getOrNull() ?: return null
        return if (t.isObject || t.isArray) t else null
    }

    private fun typesFor(rawName: String, shortId: String): Map<Int, ArgType>? {
        val analysis = cfgAnalysis ?: return null
        val src = engineSource ?: return null
        return typePinCache.computeIfAbsent("$rawName#$shortId") {
            val m = src.method("L${rawName.replace('.', '/')};", shortId) ?: return@computeIfAbsent emptyMap()
            val result = runCatching { analysis.result(m) }.getOrNull() ?: return@computeIfAbsent emptyMap()
            if (!result.complete) return@computeIfAbsent emptyMap()
            val out = HashMap<Int, ArgType>()
            val insns = m.insns
            for (i in insns.indices) {
                val insn = insns[i]
                if (insn.regs.isEmpty()) continue
                val at = refArgType(result.regOut(i, insn.regs[0])) ?: continue
                out[insn.offset] = at
                if (insn.opcode == Opcode.MOVE_RESULT && i > 0) out[insns[i - 1].offset] = at
            }
            out
        }
    }

    private val deflattenCache = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<io.github.nitanmarcel.jdex.exec.analysis.DispatchGraph>>()

    private val deflattenExplorer by lazy { engineSource?.let { io.github.nitanmarcel.jdex.exec.analysis.DispatchExplorer(it) } }

    private fun deflattenGraphFor(rawName: String, shortId: String): io.github.nitanmarcel.jdex.exec.analysis.DispatchGraph? {
        val explorer = deflattenExplorer ?: return null
        val src = engineSource ?: return null
        return deflattenCache.computeIfAbsent("$rawName#$shortId") {
            val m = src.method("L${rawName.replace('.', '/')};", shortId)
            java.util.Optional.ofNullable(m?.let { runCatching { explorer.explore(it) }.getOrNull() })
        }.orElse(null)
    }

    private val unreflect by lazy { engineSource?.let { io.github.nitanmarcel.jdex.exec.analysis.Unreflect(it) } }

    private fun unreflectFor(dispatcherRaw: String, index: Int): io.github.nitanmarcel.jdex.exec.analysis.UnreflectTarget? =
        unreflect?.let { runCatching { it.resolve("L${dispatcherRaw.replace('.', '/')};", index) }.getOrNull() }

    private fun resolveDispatchFor(dispatcherRaw: String, shortId: String, index: Int): io.github.nitanmarcel.jdex.exec.analysis.DispatchTarget? =
        reflectiveDispatch?.let { runCatching { it.resolve("L${dispatcherRaw.replace('.', '/')};", shortId, index) }.getOrNull() }

    private fun resolveDispatchInvokeFor(dispatcherRaw: String, shortId: String, index: Int, argCount: Int): io.github.nitanmarcel.jdex.exec.analysis.ReflectInvokeTarget? =
        reflectiveDispatch?.let { runCatching { it.resolveInvoke("L${dispatcherRaw.replace('.', '/')};", shortId, index, argCount) }.getOrNull() }

    private fun isReflectiveFor(dispatcherRaw: String, shortId: String): Boolean =
        reflectiveDispatch?.let { runCatching { it.isReflectiveDispatcher("L${dispatcherRaw.replace('.', '/')};", shortId) }.getOrDefault(false) } ?: false

    private val reflectInvokeCache = java.util.concurrent.ConcurrentHashMap<String, Map<Int, io.github.nitanmarcel.jdex.exec.analysis.ReflectInvokeTarget>>()

    private fun reflectInvokeFor(rawName: String, shortId: String, offset: Int): io.github.nitanmarcel.jdex.exec.analysis.ReflectInvokeTarget? {
        val analysis = cfgAnalysis ?: return null
        val src = engineSource ?: return null
        val map = reflectInvokeCache.computeIfAbsent("$rawName#$shortId") {
            val m = src.method("L${rawName.replace('.', '/')};", shortId) ?: return@computeIfAbsent emptyMap()
            val result = runCatching { analysis.result(m) }.getOrNull()?.takeIf { it.complete } ?: return@computeIfAbsent emptyMap()
            runCatching { reflectionPass.invokeTargets(m, result) }.getOrDefault(emptyMap())
        }
        return map[offset]
    }

    private val reflectSiteCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<io.github.nitanmarcel.jdex.exec.analysis.ReflectFieldSite>, List<io.github.nitanmarcel.jdex.exec.analysis.ReflectInvokeSite>>>()

    private fun reflectSitesFor(rawName: String, shortId: String): Pair<List<io.github.nitanmarcel.jdex.exec.analysis.ReflectFieldSite>, List<io.github.nitanmarcel.jdex.exec.analysis.ReflectInvokeSite>> =
        reflectSiteCache.computeIfAbsent("$rawName#$shortId") {
            val rd = reflectiveDispatch ?: return@computeIfAbsent emptyList<io.github.nitanmarcel.jdex.exec.analysis.ReflectFieldSite>() to emptyList()
            val m = engineSource?.method("L${rawName.replace('.', '/')};", shortId)
                ?: return@computeIfAbsent emptyList<io.github.nitanmarcel.jdex.exec.analysis.ReflectFieldSite>() to emptyList()
            runCatching { rd.fieldSites(m) }.getOrDefault(emptyList()) to runCatching { rd.invokeSites(m) }.getOrDefault(emptyList())
        }

    fun deobBytecodeOverlay(): Map<String, String> {
        val src = engineSource ?: return emptyMap()
        val out = HashMap<String, String>()
        for (a in classRawNames().flatMap { deobStringsForClass(it) }) {
            val m = src.method(a.descriptor.substringBefore("->"), a.descriptor.substringAfter("->")) ?: continue
            out["i:%08x".format(m.codeOffset + a.offset * 2)] = a.text
        }
        return out
    }

    private val jdecJadx by lazy {
        JadxDecompiler(JadxArgs().apply {
            inputFiles.add(input)
            setSkipResources(true)
            setShowInconsistentCode(true)
            renameFlags = emptySet()
            useKotlinMethodsForVarNames = JadxArgs.UseKotlinMethodsForVarNames.APPLY_AND_HIDE
        }).also { d ->
            d.addCustomPass(JdecBlockLockPass())
            if (config.enabled(EnginePass.STRING_VALUES)) d.addCustomPass(JdecValuePass(::deobStringsForClass))
            if (config.enabled(EnginePass.UNREFLECT)) {
                d.addCustomPass(JdecReflectPreBlock({ raw, s -> reflectSitesFor(raw, s).first }, { _, _ -> emptyList() }))
                d.addCustomPass(JdecUnreflectPass(::unreflectFor, ::reflectInvokeFor))
                d.addCustomPass(JdecUnreflectPass(::unreflectFor, ::reflectInvokeFor, ::resolveDispatchFor, ::resolveDispatchInvokeFor, ::isReflectiveFor, afterTypes = true))
            }
            if (config.cleanupEnabled) d.addCustomPass(JdecTypePinPass(::typesFor))
            if (config.cleanupEnabled) d.addCustomPass(JdecConcatCastPass())
            if (config.cleanupEnabled) d.addCustomPass(JdecCleanupPass(::cfgFactsFor))
            if (config.cleanupEnabled) d.addCustomPass(JdecConstFoldPass())
            if (config.cleanupEnabled) d.addCustomPass(JdecDeadCodePass(::cfgFactsFor))
            if (config.enabled(EnginePass.DEFLATTEN)) d.addCustomPass(JdecDeflattenPass(::deflattenGraphFor))
            if (config.cleanupEnabled) {
                d.addCustomPass(JdexBlockPrunePass(::cfgFactsFor))
                d.addCustomPass(JdexMultiEntryFix())
                d.addCustomPass(JdexCfgSpikeCapture())
            }
            Thread.interrupted()
            d.load()
        }
    }

    private val jdecClassesByRawName by lazy { jdecJadx.classesWithInners.associateBy { it.rawName } }

    fun prewarm() {
        if (!config.emulatorEnabled) return
        runCatching { jdecClassesByRawName }
        if (config.decryptStringsAtStartup) {
            runCatching { classRawNames().forEach { deobStringsForClass(it) } }
        } else {
            runCatching { engineCtx }
            runCatching { nativeDecryptors.parallelStream().forEach { it.candidates() } }
        }
    }

    private val appliedRenames = HashMap<String, String>()

    fun syncRenames(map: Map<String, String>) {
        for (key in appliedRenames.keys + map.keys) {
            val desired = map[key]
            if (appliedRenames[key] == desired) continue
            runCatching { applyAlias(key, desired) }
            if (desired == null) appliedRenames.remove(key) else appliedRenames[key] = desired
        }
    }

    private fun applyAlias(key: String, name: String?) {
        val rawName = key.substringBefore("->").removePrefix("L").removeSuffix(";").replace('/', '.')
        val cls = classesByRawName[rawName] ?: return
        when {
            "->" !in key -> if (name == null) cls.removeAlias() else cls.classNode.classInfo.changeShortName(name)
            '(' in key.substringAfter("->") -> {
                val shortId = key.substringAfter("->")
                cls.searchMethodByShortId(shortId)?.methodNode?.methodInfo?.setAlias(name ?: shortId.substringBefore('('))
            }
            else -> {
                val fieldName = key.substringAfter("->")
                cls.fields.firstOrNull { it.name == fieldName }?.fieldNode?.fieldInfo?.setAlias(name ?: fieldName)
            }
        }
    }

    fun decompile(name: String, mode: DecompileMode = DecompileMode.JAVA): Decompiled? {
        Thread.interrupted()
        val cls = classesByRawName[name] ?: return null
        val top = cls.topParentClass
        val title = top.fullName.substringAfterLast('.')
        return runCatching {
            val jdecCls = if (mode == DecompileMode.JDEC && config.emulatorEnabled) jdecClassesByRawName[name] else null
            val info = when (mode) {
                DecompileMode.JDEC -> (jdecCls?.topParentClass ?: top).codeInfo
                DecompileMode.JAVA -> if (appliedRenames.isNotEmpty()) top.reload() else top.codeInfo
                DecompileMode.SIMPLE -> top.classNode.decompileWithMode(JadxMode.SIMPLE)
                DecompileMode.FALLBACK -> top.classNode.decompileWithMode(JadxMode.FALLBACK)
            }
            val code = if (jdecCls != null) JdecValueInjector.annotatedSource(jdecCls, info, valueOverlayFor(name)) else info.codeStr
            Decompiled(title, code, if (mode.sync) buildSync(info) else CodeSync.EMPTY)
        }.getOrElse { recoverDecompile(title, name, it) }
    }

    private fun recoverDecompile(title: String, name: String, e: Throwable): Decompiled {
        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
        Thread.interrupted()
        val trace = e.stackTraceToString().trimEnd().lineSequence().joinToString("\n") { " * $it" }
        return Decompiled(title, "/*\n * jdex: failed to decompile $name\n *\n$trace\n */\n", CodeSync.EMPTY)
    }

    private fun buildSync(info: ICodeInfo): CodeSync {
        if (!info.hasMetadata()) return CodeSync.EMPTY
        val md = info.codeMetadata
        val lineStarts = lineStarts(info.codeStr)
        fun lineOf(pos: Int): Int {
            var lo = 0; var hi = lineStarts.size - 1; var ans = 0
            while (lo <= hi) { val m = (lo + hi) ushr 1; if (lineStarts[m] <= pos) { ans = m; lo = m + 1 } else hi = m - 1 }
            return ans + 1
        }
        val targets = TreeMap<Int, SyncTarget>()
        val srcToJava = HashMap<String, TreeMap<Int, Int>>()
        for ((javaLine, sourceLine) in md.lineMapping) {
            val pos = lineStarts.getOrNull(javaLine - 1) ?: continue
            val method = jadx.getJavaNodeByRef(md.getNodeAt(pos) ?: continue) as? JavaMethod ?: continue
            val methodId = methodId(method) ?: continue
            targets[javaLine] = SyncTarget.Line(methodId, sourceLine)
            srcToJava.getOrPut(methodId) { TreeMap() }.putIfAbsent(sourceLine, javaLine)
        }
        val classDecl = HashMap<String, Int>()
        val methodDecl = HashMap<String, Int>()
        val fieldDecl = HashMap<String, Int>()
        for ((pos, annotation) in md.asMap) {
            if (annotation !is NodeDeclareRef) continue
            val line = lineOf(pos)
            when (val node = jadx.getJavaNodeByRef(annotation.node)) {
                is JavaMethod -> methodId(node)?.let { targets[line] = SyncTarget.MethodDecl(it); methodDecl[it] = line }
                is JavaField -> {
                    targets[line] = SyncTarget.FieldDecl(node.declaringClass.rawName, node.name)
                    fieldDecl["${node.declaringClass.rawName}#${node.name}"] = line
                }
                is JavaClass -> {
                    targets[line] = SyncTarget.ClassDecl(node.rawName)
                    classDecl[node.rawName] = line
                }
                else -> {}
            }
        }
        return CodeSync(targets, srcToJava, classDecl, methodDecl, fieldDecl)
    }

    private fun lineStarts(code: String): IntArray {
        val starts = ArrayList<Int>().apply { add(0) }
        for (i in code.indices) if (code[i] == '\n') starts.add(i + 1)
        return starts.toIntArray()
    }

    private fun methodId(method: JavaMethod): String? {
        val shortId = runCatching { method.methodNode.methodInfo.shortId }.getOrNull() ?: return null
        return "${method.declaringClass.rawName}#$shortId"
    }

    fun decompiler(): JadxDecompiler = jadx

    fun classRawNames(): List<String> = classesByRawName.keys.sorted()

    fun classNode(rawName: String): JavaClass? = classesByRawName[rawName]

    fun nativeMethodShortId(rawName: String, methodName: String, argDescriptor: String? = null): String? {
        val cls = classesByRawName[rawName] ?: return null
        val named = runCatching { cls.methods.filter { it.name == methodName } }.getOrDefault(emptyList())
        val natives = named.filter { runCatching { it.methodNode.accessFlags.isNative }.getOrDefault(false) }
        val pool = natives.ifEmpty { named }
        val pick = when {
            argDescriptor != null -> pool.firstOrNull { runCatching { it.methodNode.methodInfo.shortId.startsWith("$methodName($argDescriptor)") }.getOrDefault(false) }
            pool.size == 1 -> pool[0]
            else -> null
        }
        return runCatching { pick?.methodNode?.methodInfo?.shortId }.getOrNull()
    }


    fun jniMethodKey(name: String, jniSignature: String): String? {
        val target = "$name$jniSignature"
        val keys = classesByRawName.values.mapNotNull { cls ->
            if (runCatching { BytecodeWriter.methodShortIds(cls).contains(target) }.getOrDefault(false))
                "${descOf(cls.rawName)}->$target" else null
        }
        return keys.singleOrNull()
    }

    private fun resourceNames(): Map<Int, String> =
        runCatching { jadx.root.constValues.resourcesNames }.getOrDefault(emptyMap())

    fun smali(rawName: String): String? =
        classNode(rawName)?.let { runCatching { BytecodeWriter.forClass(it, resourceNames()) }.getOrNull() }

    fun javaSource(rawName: String): String? = decompile(rawName)?.code

    fun classSuper(rawName: String): String? = classNode(rawName)?.classNode?.clsData?.superType

    fun classInterfaces(rawName: String): List<String> =
        classNode(rawName)?.classNode?.clsData?.interfacesTypes ?: emptyList()

    private fun descOf(rawName: String): String = "L${rawName.replace('.', '/')};"

    fun findMethods(pattern: String): List<String> {
        val rx = runCatching { Regex(pattern) }.getOrNull() ?: return emptyList()
        return classesByRawName.values.flatMap { cls ->
            val desc = descOf(cls.rawName)
            BytecodeWriter.methodShortIds(cls).map { "$desc->$it" }
        }.filter { rx.containsMatchIn(it) }
    }

    fun findFields(pattern: String): List<String> {
        val rx = runCatching { Regex(pattern) }.getOrNull() ?: return emptyList()
        return classesByRawName.values.flatMap { cls ->
            val desc = descOf(cls.rawName)
            BytecodeWriter.fieldNames(cls).map { "$desc->$it" }
        }.filter { rx.containsMatchIn(it) }
    }

    fun methodSmali(rawName: String, shortId: String): String? =
        classNode(rawName)?.let { BytecodeWriter.methodSmali(it, shortId, resourceNames()) }

    fun methodInstructions(rawName: String, shortId: String): List<Map<String, Any?>>? =
        classNode(rawName)?.let { BytecodeWriter.instructions(it, shortId, resourceNames()) }

    fun methodCfg(rawName: String, shortId: String): MethodCfg? =
        classNode(rawName)?.let { BytecodeWriter.cfg(it, shortId) }

    fun classStrings(rawName: String): List<String> =
        classNode(rawName)?.let { BytecodeWriter.strings(it) } ?: emptyList()

    fun classInfo(rawName: String): Map<String, Any?>? =
        classNode(rawName)?.let { BytecodeWriter.classInfo(it) }

    fun methodInfo(rawName: String, shortId: String): Map<String, Any?>? =
        classNode(rawName)?.let { BytecodeWriter.methodInfo(it, shortId) }

    fun registerMeta(rawName: String, shortId: String): io.github.nitanmarcel.jdex.debug.RegisterMeta? =
        classNode(rawName)?.let { BytecodeWriter.registerInfo(it, shortId) }
            ?.let { io.github.nitanmarcel.jdex.debug.RegisterMeta(it[0], it[1], it[2] == 1) }

    fun fieldInfo(rawName: String, name: String): Map<String, Any?>? =
        classNode(rawName)?.let { BytecodeWriter.fieldInfo(it, name) }

    fun searchCode(pattern: String, limit: Int): List<Map<String, Any?>> {
        val rx = runCatching { Regex(pattern) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Map<String, Any?>>()
        for (cls in classesByRawName.values) {
            val desc = descOf(cls.rawName)
            for ((shortId, offset, text) in BytecodeWriter.scanCode(cls, rx)) {
                out.add(linkedMapOf("method" to "$desc->$shortId", "offset" to offset, "text" to text))
                if (out.size >= limit) return out
            }
        }
        return out
    }

    fun allStrings(): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        for (cls in classesByRawName.values) {
            val desc = descOf(cls.rawName)
            for ((shortId, value) in BytecodeWriter.stringRefs(cls)) {
                out.add(linkedMapOf("value" to value, "method" to "$desc->$shortId"))
            }
        }
        return out
    }

    fun fieldAccess(fieldDesc: String, write: Boolean): List<String> {
        val usages = usages(Symbol(SymbolKind.FIELD, fieldDesc)) ?: return emptyList()
        val fieldClassDesc = fieldDesc.substringBefore("->")
        val fieldName = fieldDesc.substringAfter("->").substringBefore(':')
        val result = LinkedHashSet<String>()
        for (u in usages) {
            val shortId = u.shortId ?: continue
            val cls = classesByRawName[u.rawName] ?: continue
            val (reads, writes) = BytecodeWriter.fieldAccessIn(cls, shortId, fieldClassDesc, fieldName)
            if (if (write) writes else reads) result.add("${descOf(u.rawName)}->$shortId")
        }
        return result.toList()
    }

    fun offsetAtLine(rawName: String, line: Int): Int? {
        val cls = classesByRawName[rawName] ?: return null
        val info = runCatching { cls.topParentClass.codeInfo }.getOrNull() ?: return null
        if (!info.hasMetadata()) return null
        val starts = lineStarts(info.codeStr)
        val from = starts.getOrNull(line - 1) ?: return null
        val to = starts.getOrNull(line) ?: info.codeStr.length
        var best: Int? = null
        var bestPos = Int.MAX_VALUE
        for ((pos, ann) in info.codeMetadata.asMap) {
            if (ann is InsnCodeOffset && pos in from until to && pos < bestPos) {
                bestPos = pos
                best = ann.offset
            }
        }
        return best
    }

    fun manifest(): String = manifestText

    private fun manifestDoc(): org.w3c.dom.Document? = runCatching {
        if (manifestText.isBlank()) null
        else javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestText.byteInputStream())
    }.getOrNull()

    private fun manifestNames(tag: String): List<org.w3c.dom.Element> {
        val doc = manifestDoc() ?: return emptyList()
        val nodes = doc.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
    }

    fun permissions(): List<String> = manifestNames("uses-permission")
        .mapNotNull { it.getAttribute("android:name").ifEmpty { it.getAttribute("name") }.ifEmpty { null } }
        .distinct()

    fun components(tag: String): List<String> = manifestNames(tag)
        .mapNotNull { resolveActivity(it.getAttribute("android:name").ifEmpty { it.getAttribute("name") }) }
        .distinct()

    fun topClasses(): List<HClass> =
        jadx.classes.map { HClass(it.rawName, it.fullName, it.getPackage(), it.name) }

    fun members(rawName: String): HMembers {
        val cls = classesByRawName[rawName] ?: return HMembers(emptyList(), emptyList(), emptyMap(), emptyList())
        val raw = cls.rawName
        val fields = runCatching {
            cls.fields.map { HField("${it.name} : ${simpleName(it.type.toString())}", it.name, raw) }
        }.getOrDefault(emptyList())
        val methods = runCatching {
            cls.methods.map { m ->
                val shortId = runCatching { m.methodNode.methodInfo.shortId }.getOrElse { m.name }
                HMethod(methodDisplay(m, raw), shortId, raw)
            }
        }.getOrDefault(emptyList())
        val byMethod = LinkedHashMap<String, MutableList<HClass>>()
        val loose = mutableListOf<HClass>()
        runCatching {
            cls.innerClasses.forEach { inner ->
                val display = inner.rawName.removePrefix("$raw$").ifEmpty { inner.name }
                val hc = HClass(inner.rawName, inner.fullName, inner.getPackage(), display)
                val declaringMethod = runCatching { inner.classNode.useInMth.firstOrNull()?.methodInfo?.shortId }.getOrNull()
                if (declaringMethod != null) byMethod.getOrPut(declaringMethod) { mutableListOf() }.add(hc) else loose.add(hc)
            }
        }
        return HMembers(fields, methods, byMethod, loose)
    }

    private fun methodDisplay(m: jadx.api.JavaMethod, raw: String): String {
        val args = m.arguments.joinToString(", ") { simpleName(it.toString()) }
        return if (m.isConstructor) "${raw.substringAfterLast('.')}($args)"
        else "${m.name}($args) : ${simpleName(m.returnType.toString())}"
    }

    private fun simpleName(type: String): String = type.substringAfterLast('.')

    override fun close() {
        jadx.close()
        if (nativeDecryptorsLazy.isInitialized()) nativeDecryptorsLazy.value.forEach { runCatching { it.close() } }
        tempDexFiles.forEach { runCatching { it.delete() } }
    }

    companion object {
        private val PACKAGE = Regex("""package\s*=\s*"([^"]+)"""")
        private val DEX = Regex("""classes\d*\.dex""")
        private val MAX_BYTES = 1 shl 20

        fun load(input: File, projectName: String, dexStore: DexStore = NoDexStore, fileStore: FileStore = NoFileStore, config: EngineConfig = EngineConfig.DEFAULT): ApkSession {
            val isApk = runCatching { ZipFile(input).use {} }.isSuccess
            val certificates = if (isApk) certificates(input) else emptyList()

            val extraDexFiles = mutableListOf<File>()
            val malformed = mutableListOf<MalformedDex>()
            gatherDexes(input, isApk, dexStore, extraDexFiles, malformed)

            val jadx = JadxDecompiler(JadxArgs().apply {
                inputFiles.add(input)
                extraDexFiles.forEach { inputFiles.add(it) }
                setSkipResources(false)
                setShowInconsistentCode(true)
                renameFlags = emptySet()
                useKotlinMethodsForVarNames = JadxArgs.UseKotlinMethodsForVarNames.APPLY_AND_HIDE
            })
            jadx.load()

            val hasDex = scanHasDex(input) || jadx.classes.isNotEmpty()
            val resources = jadx.resources
            var manifest: ResourceFile? = null
            val res = mutableListOf<Pair<String, (() -> Content)?>>()
            val assets = mutableListOf<Pair<String, (() -> Content)?>>()
            val libs = mutableListOf<Pair<String, (() -> Content)?>>()
            val imported = mutableListOf<Pair<String, (() -> Content)?>>()
            for (rf in resources) {
                val name = rf.deobfName
                when {
                    rf.type == ResourceType.MANIFEST -> manifest = rf
                    name.startsWith("assets/") -> assets += name.removePrefix("assets/") to fileContent(input, rf)
                    name.startsWith("lib/") -> libs += name.removePrefix("lib/") to nativeContent(input, rf)
                    name.startsWith("res/") -> res += name.removePrefix("res/") to fileContent(input, rf)
                    name == "resources.arsc" -> res += name to fileContent(input, rf)
                }
            }
            fileStore.importedFiles().forEach { f ->
                if (f.path.startsWith("lib/")) {
                    val rel = f.path.removePrefix("lib/")
                    libs.removeAll { it.first == rel }
                    libs += rel to fileFromBytes(f.bytes, rel)
                } else {
                    val rel = f.path.removePrefix("unknown/")
                    imported.removeAll { it.first == rel }
                    imported += rel to fileFromBytes(f.bytes, rel)
                }
            }

            val manifestText = manifest?.let { decode(it) } ?: ""
            val appPackage = PACKAGE.find(manifestText)?.groupValues?.get(1)

            val units = buildList {
                if (manifest != null) add(ProjectNode("Manifest", open = { TextContent(manifestText, Syntax.XML) }))
                certificates.forEachIndexed { index, cert ->
                    val name = if (index == 0) "Certificate" else "Certificate #${index + 1}"
                    val schemes = cert.schemes.joinToString(", ") { "v$it" }
                    add(ProjectNode("$name ($schemes)", open = { TextContent(cert.details) }))
                }
                if (hasDex) add(ProjectNode("Bytecode", open = {
                    CodeContent(Syntax.SMALI) { progress, cancel -> bytecode(jadx, progress, cancel) }
                }))
                folder("Resources", res)?.let(::add)
                folder("Assets", assets)?.let(::add)
                folder("Libraries", libs)?.let(::add)
                folder("unknown", imported)?.let(::add)
            }

            val container = appPackage?.let { ProjectNode(it, children = units) }
            val artifact = ProjectNode(input.name, children = listOfNotNull(container).ifEmpty { units })
            val rootChildren = buildList {
                if (units.isNotEmpty()) add(artifact)
                if (malformed.isNotEmpty()) {
                    add(ProjectNode("[unknown]", children = malformed.map { ProjectNode(it.name.substringAfterLast('/'), dex = it) }))
                }
            }.ifEmpty { listOf(artifact) }
            val root = ProjectNode(projectName, children = rootChildren)

            return ApkSession(jadx, root, appPackage, certificates.size, manifestText, malformed, extraDexFiles, input, config)
        }

        private fun gatherDexes(input: File, isApk: Boolean, store: DexStore, extra: MutableList<File>, malformed: MutableList<MalformedDex>) {
            fun handle(name: String, source: ByteArray, isImport: Boolean) {
                val sha = DexPatch.sha256(source)
                val effective = store.patch(sha)?.apply(source) ?: source
                when {
                    Dex.parseBroken(effective) -> malformed += MalformedDex(name, source, sha, effective, Dex.validate(effective))
                    effective !== source || isImport -> {
                        extra += File.createTempFile("jdex-dex", ".dex").apply { writeBytes(effective); deleteOnExit() }
                    }
                }
            }
            if (isApk) {
                runCatching {
                    ZipFile(input).use { zip ->
                        zip.entries().asSequence().filter { DEX.matches(it.name) }.forEach { entry ->
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            if (Dex.parseBroken(bytes)) handle(entry.name, bytes, false)
                        }
                    }
                }
            } else {
                runCatching { input.readBytes() }.getOrNull()?.let { handle(input.name, it, false) }
            }
            store.importedDexes().forEach { handle(it.name, it.bytes, true) }
        }

        private fun bytecode(jadx: JadxDecompiler, progress: (Int, Int) -> Unit, cancel: () -> Boolean): LineSource? {
            val resources = runCatching { jadx.root.constValues.resourcesNames }.getOrDefault(emptyMap())
            val classes = jadx.classesWithInners.sortedBy { it.rawName }
            val total = classes.size
            val chunks = classes.asSequence().mapIndexed { index, cls ->
                if (cancel()) throw GenerationCancelled()
                progress(index + 1, total)
                val listing = runCatching { BytecodeWriter.forClassListing(cls, resources) }
                    .getOrElse { BytecodeWriter.BytecodeListing("# failed to disassemble ${cls.rawName}: ${it.message}\n", IntArray(0), IntArray(0)) }
                LabeledChunk(cls.rawName, listing.text + "\n", listing.offsetLines, listing.dexPcs)
            }
            return try {
                DiskLineSource.build(chunks)
            } catch (e: GenerationCancelled) {
                null
            }
        }

        private fun nativeContent(input: File, rf: ResourceFile): () -> Content = {
            val bytes = readEntryFull(input, rf.originalName)
            if (io.github.nitanmarcel.jdex.disasm.ElfFile.parse(bytes) != null)
                NativeContent(rf.deobfName.substringAfterLast('/'), bytes)
            else BinaryContent(bytes)
        }

        private fun fileFromBytes(bytes: ByteArray, path: String): () -> Content = {
            if (io.github.nitanmarcel.jdex.disasm.ElfFile.parse(bytes) != null)
                NativeContent(path.substringAfterLast('/'), bytes)
            else BinaryContent(bytes)
        }

        fun abiFolder(arch: io.github.nitanmarcel.jdex.disasm.ElfArch): String? = arch.androidAbi

        private fun readEntryFull(input: File, name: String): ByteArray = runCatching {
            ZipFile(input).use { zip ->
                val entry = zip.getEntry(name) ?: return@use ByteArray(0)
                zip.getInputStream(entry).use { it.readBytes() }
            }
        }.getOrDefault(ByteArray(0))

        private fun fileContent(input: File, rf: ResourceFile): () -> Content = {
            val syntax = syntaxFor(rf.deobfName)
            val decoded = decode(rf).takeIf { it.isNotBlank() }
            if (decoded != null) {
                TextContent(decoded, syntax)
            } else {
                val bytes = readEntry(input, rf.originalName)
                if (printable(bytes)) TextContent(String(bytes, Charsets.UTF_8), syntax) else BinaryContent(bytes)
            }
        }

        private fun syntaxFor(name: String): Syntax = when (name.substringAfterLast('.', "").lowercase()) {
            "xml" -> Syntax.XML
            "json" -> Syntax.JSON
            "js" -> Syntax.JAVASCRIPT
            "html", "htm" -> Syntax.HTML
            "css" -> Syntax.CSS
            "properties" -> Syntax.PROPERTIES
            else -> Syntax.NONE
        }

        private fun decode(rf: ResourceFile): String =
            runCatching { rf.loadContent()?.text?.codeStr }.getOrNull() ?: ""

        private fun folder(name: String, files: List<Pair<String, (() -> Content)?>>): ProjectNode? {
            if (files.isEmpty()) return null
            val rootNode = MutableNode(name)
            for ((path, open) in files.sortedBy { it.first }) {
                val parts = path.split('/')
                var node = rootNode
                parts.forEachIndexed { index, part ->
                    val leaf = index == parts.lastIndex
                    node = node.children.getOrPut(part) { MutableNode(part, if (leaf) open else null) }
                }
            }
            return rootNode.toProjectNode()
        }

        private class MutableNode(val label: String, val open: (() -> Content)? = null) {
            val children = LinkedHashMap<String, MutableNode>()
            fun toProjectNode(): ProjectNode = ProjectNode(label, open, children.values.map { it.toProjectNode() })
        }

        private fun readEntry(input: File, name: String): ByteArray = runCatching {
            ZipFile(input).use { zip ->
                val entry = zip.getEntry(name) ?: return@use ByteArray(0)
                zip.getInputStream(entry).use { it.readNBytes(MAX_BYTES) }
            }
        }.getOrDefault(ByteArray(0))

        private fun printable(bytes: ByteArray): Boolean =
            bytes.isNotEmpty() && bytes.none { (it.toInt() and 0xff) == 0 } &&
                bytes.count { val v = it.toInt() and 0xff; v in 0x20..0x7e || v == 0x09 || v == 0x0a || v == 0x0d } > bytes.size * 0.85

        private fun certificates(input: File): List<CertificateInfo> = runCatching {
            val result = ApkVerifier.Builder(input).build().verify()
            val byCert = LinkedHashMap<String, Pair<X509Certificate, MutableSet<Int>>>()

            fun record(cert: X509Certificate?, scheme: Int) {
                cert ?: return
                val key = cert.encoded.joinToString("") { "%02x".format(it) }
                byCert.getOrPut(key) { cert to sortedSetOf() }.second.add(scheme)
            }

            result.v1SchemeSigners.forEach { record(it.certificate, 1) }
            result.v2SchemeSigners.forEach { record(it.certificate, 2) }
            result.v3SchemeSigners.forEach { record(it.certificate, 3) }

            byCert.values.map { (cert, schemes) -> CertificateInfo(cert, schemes.toList()) }
        }.getOrDefault(emptyList())

        private class CertificateInfo(cert: X509Certificate, val schemes: List<Int>) {
            val details = buildString {
                appendLine("Subject:    ${cert.subjectX500Principal.name}")
                appendLine("Issuer:     ${cert.issuerX500Principal.name}")
                appendLine("Serial:     ${cert.serialNumber.toString(16)}")
                appendLine("Valid from: ${cert.notBefore}")
                appendLine("Valid to:   ${cert.notAfter}")
                appendLine("Algorithm:  ${cert.sigAlgName}")
                appendLine("SHA-256:    ${fingerprint(cert.encoded)}")
                appendLine("Schemes:    ${schemes.joinToString(", ") { "v$it" }}")
            }

            private fun fingerprint(encoded: ByteArray): String =
                MessageDigest.getInstance("SHA-256").digest(encoded).joinToString(":") { "%02X".format(it) }
        }

        private fun scanHasDex(input: File): Boolean = runCatching {
            ZipFile(input).use { zip -> zip.entries().asSequence().any { DEX.matches(it.name) } }
        }.getOrDefault(false)
    }
}
