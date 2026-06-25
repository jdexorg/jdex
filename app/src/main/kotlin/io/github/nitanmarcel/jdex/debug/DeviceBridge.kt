package io.github.nitanmarcel.jdex.debug

import com.android.ddmlib.AdbInitOptions
import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import java.io.File
import java.util.concurrent.TimeUnit

data class DebugDevice(val serial: String, val label: String, val online: Boolean)

data class DebugProcess(val pid: Int, val name: String)

object DeviceBridge {

    @Volatile
    private var bridge: AndroidDebugBridge? = null

    @Volatile
    private var initialized = false

    fun adbPath(): File? {
        val home = System.getProperty("user.home")
        val roots = listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            home?.let { "$it/Android/Sdk" },
            "/opt/android-sdk",
        )
        for (root in roots) {
            val f = File(root, "platform-tools/adb")
            if (f.canExecute()) return f
        }
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach {
            val f = File(it, "adb")
            if (f.canExecute()) return f
        }
        return null
    }

    @Synchronized
    fun connect(): AndroidDebugBridge {
        bridge?.let { if (it.isConnected) return it }
        val adb = adbPath() ?: throw IllegalStateException("adb not found — set ANDROID_HOME or install platform-tools")
        if (!initialized) {
            AndroidDebugBridge.init(AdbInitOptions.builder().setClientSupportEnabled(true).build())
            initialized = true
        }
        val b = AndroidDebugBridge.createBridge(adb.absolutePath, false, 30, TimeUnit.SECONDS)
            ?: throw IllegalStateException("Could not start the adb bridge")
        val deadline = System.currentTimeMillis() + 10_000
        while (!b.hasInitialDeviceList() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        bridge = b
        return b
    }

    fun devices(): List<DebugDevice> = connect().devices.map { d ->
        val model = runCatching { d.getProperty(IDevice.PROP_DEVICE_MODEL) }.getOrNull()
        DebugDevice(d.serialNumber, model ?: d.serialNumber, d.isOnline)
    }

    private val appUser = Regex("u\\d+_a\\d+")

    fun processes(serial: String): List<DebugProcess> {
        val out = runCatching { shell(serial, "ps -A -o PID,USER,NAME") }.getOrDefault("")
        return out.lineSequence().mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
            if (p.size < 3) return@mapNotNull null
            val pid = p[0].toIntOrNull() ?: return@mapNotNull null
            if (!appUser.matches(p[1])) return@mapNotNull null
            val name = p[2]
            if ('.' !in name) return@mapNotNull null
            DebugProcess(pid, name)
        }.distinctBy { it.pid }.sortedBy { it.name }.toList()
    }

    fun androidRelease(serial: String): Int {
        val device = connect().devices.firstOrNull { it.serialNumber == serial } ?: return 99
        val rel = runCatching { device.getProperty("ro.build.version.release") }.getOrNull()
        return rel?.substringBefore('.')?.toIntOrNull() ?: 99
    }

    fun deviceAbi(serial: String): String {
        val device = connect().devices.firstOrNull { it.serialNumber == serial }
        return device?.let { runCatching { it.getProperty("ro.product.cpu.abi") }.getOrNull() } ?: "arm64-v8a"
    }

    fun debuggerPort(serial: String, pid: Int): Int? {
        val device = connect().devices.firstOrNull { it.serialNumber == serial } ?: return null
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val client = device.clients.firstOrNull { it.clientData?.pid == pid }
            val port = client?.debuggerListenPort ?: -1
            if (port > 0) return port
            Thread.sleep(150)
        }
        return null
    }

    fun disconnect() {
        bridge = null
        initialized = false
        runCatching { AndroidDebugBridge.terminate() }
    }

    private fun device(serial: String) =
        connect().devices.firstOrNull { it.serialNumber == serial }
            ?: throw IllegalStateException("device $serial not connected")

    fun shell(serial: String, cmd: String): String {
        val r = com.android.ddmlib.CollectingOutputReceiver()
        device(serial).executeShellCommand(cmd, r, 10, TimeUnit.SECONDS)
        return r.output
    }

    @Volatile private var jrunasRemote: String? = null

    fun useJrunas(remotePath: String?) { jrunasRemote = remotePath }

    private fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"
    private fun runAs(pkg: String, cmd: String): String =
        jrunasRemote?.let { "su -c ${shq("$it --ptrace $pkg $cmd")}" } ?: "run-as $pkg $cmd"

    fun isDebuggable(serial: String, pkg: String): Boolean =
        shell(serial, "dumpsys package $pkg").lineSequence()
            .any { (it.contains("flags=[") || it.contains("pkgFlags=[")) && it.contains("DEBUGGABLE") }

    fun hasRoot(serial: String): Boolean =
        runCatching { shell(serial, "su -c id").contains("uid=0") }.getOrDefault(false)

    fun pushJrunas(serial: String, localPath: String, remote: String = "/data/local/tmp/jrunas"): String {
        pushFile(serial, localPath, remote)
        shell(serial, "su -c 'chmod 755 $remote'")
        return remote
    }

    fun readProcMaps(serial: String, pkg: String, pid: Int): String =
        shell(serial, runAs(pkg, "cat /proc/$pid/maps"))

    fun readAuxv(serial: String, pkg: String, pid: Int): ByteArray? {
        val out = shell(serial, runAs(pkg, "base64 /proc/$pid/auxv")).replace(Regex("[^A-Za-z0-9+/=]"), "")
        return runCatching { java.util.Base64.getDecoder().decode(out) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun readThreadInfo(serial: String, pkg: String, pid: Int): Map<Long, Pair<String, String>> {
        val out = shell(serial, runAs(pkg, "sh -c 'for t in /proc/$pid/task/*; do s=\$(cat \$t/stat); st=\${s##*) }; echo \"\$(basename \$t)|\$(cat \$t/comm)|\${st%% *}\"; done'"))
        val m = HashMap<Long, Pair<String, String>>()
        out.lineSequence().forEach { line ->
            val p = line.split('|')
            if (p.size < 3) return@forEach
            val tid = p[0].trim().toLongOrNull() ?: return@forEach
            m[tid] = p[1].trim() to p[2].trim()
        }
        return m
    }

    fun pushFile(serial: String, local: String, remote: String) {
        device(serial).pushFile(local, remote)
    }

    fun pullFile(serial: String, remote: String, local: String) {
        device(serial).pullFile(remote, local)
    }

    fun forward(serial: String, localPort: Int, remotePort: Int) {
        device(serial).createForward(localPort, remotePort)
    }

    fun removeForward(serial: String, localPort: Int) {
        runCatching { device(serial).removeForward(localPort) }
    }

    fun startLldbServer(serial: String, pkg: String, lldbLocalPath: String, pid: Int, port: Int): AutoCloseable {
        val dst = "/data/data/$pkg/lldb-server"
        pushFile(serial, lldbLocalPath, "/data/local/tmp/lldb-server")
        shell(serial, runAs(pkg, "sh -c 'cp /data/local/tmp/lldb-server $dst && chmod 700 $dst'"))
        val dev = device(serial)
        val t = Thread({
            runCatching {
                dev.executeShellCommand(runAs(pkg, "$dst gdbserver :$port --attach $pid"),
                    com.android.ddmlib.CollectingOutputReceiver(), 0, TimeUnit.SECONDS)
            }
        }, "jdex-lldb-server").apply { isDaemon = true; start() }
        return AutoCloseable {
            runCatching { shell(serial, runAs(pkg, "pkill lldb-server")) }
            t.interrupt()
        }
    }
}
