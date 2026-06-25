import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    java
}

abstract class CmakeBuild @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Internal abstract val source: DirectoryProperty
    @get:Internal abstract val workDir: DirectoryProperty
    @get:OutputFile abstract val output: RegularFileProperty
    @get:Input abstract val cmake: Property<String>
    @get:Input abstract val libName: Property<String>
    @get:Input abstract val version: Property<String>
    @get:Input abstract val extraArgs: ListProperty<String>

    @TaskAction
    fun run() {
        val build = workDir.get().asFile.also { it.mkdirs() }
        val src = source.get().asFile
        check(File(src, "CMakeLists.txt").isFile) { "submodule missing at $src — run: git submodule update --init" }
        exec.exec {
            commandLine(
                listOf(cmake.get(), "-S", src.absolutePath, "-B", build.absolutePath,
                    "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_SHARED_LIBS=ON") + extraArgs.get(),
            )
        }
        exec.exec { commandLine(cmake.get(), "--build", build.absolutePath, "--config", "Release", "-j") }
        val produced = build.walkTopDown()
            .filter { it.isFile && it.name.startsWith(libName.get()) }
            .maxByOrNull { it.length() }
            ?: error("capstone build produced no ${libName.get()} under $build")
        val out = output.get().asFile
        out.parentFile.mkdirs()
        produced.copyTo(out, overwrite = true)
    }
}

abstract class BuildJrunas @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Internal abstract val source: DirectoryProperty
    @get:Internal abstract val workRoot: DirectoryProperty
    @get:OutputDirectory abstract val outDir: DirectoryProperty
    @get:Input abstract val cmake: Property<String>
    @get:Input abstract val ndk: Property<String>

    @TaskAction
    fun run() {
        if (ndk.get().isEmpty()) return
        val src = source.get().asFile
        check(File(src, "CMakeLists.txt").isFile) { "jrunas submodule missing at $src — run: git submodule update --init --recursive" }
        val toolchain = File(ndk.get(), "build/cmake/android.toolchain.cmake")
        check(toolchain.isFile) { "android.toolchain.cmake not found under NDK ${ndk.get()}" }
        val out = outDir.get().asFile
        for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")) {
            val build = workRoot.get().dir(abi).asFile.also { it.mkdirs() }
            exec.exec {
                commandLine(
                    cmake.get(), "-S", src.absolutePath, "-B", build.absolutePath,
                    "-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}",
                    "-DANDROID_ABI=$abi", "-DANDROID_PLATFORM=android-24", "-DCMAKE_BUILD_TYPE=Release",
                )
            }
            exec.exec { commandLine(cmake.get(), "--build", build.absolutePath, "--config", "Release", "-j") }
            val bin = build.walkTopDown().firstOrNull { it.isFile && it.name == "jrunas" }
                ?: error("jrunas build produced no binary for $abi under $build")
            val dst = File(out, "$abi/jrunas").also { it.parentFile.mkdirs() }
            bin.copyTo(dst, overwrite = true)
            dst.setExecutable(true)
        }
    }
}

fun ndkDir(): String? {
    listOfNotNull(System.getenv("ANDROID_NDK_HOME"), System.getenv("ANDROID_NDK_ROOT"))
        .firstOrNull { File(it, "build/cmake/android.toolchain.cmake").isFile }?.let { return it }
    val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "${System.getProperty("user.home")}/Android/Sdk"
    return File(sdk, "ndk").listFiles()?.filter { File(it, "build/cmake/android.toolchain.cmake").isFile }
        ?.maxByOrNull { it.name }?.absolutePath
}

fun jnaPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = when (val a = System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x86-64"
        "aarch64", "arm64" -> "aarch64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        "arm" -> "arm"
        else -> a
    }
    return when {
        os.contains("win") -> "win32-$arch"
        os.contains("mac") || os.contains("darwin") -> "darwin"
        else -> "linux-$arch"
    }
}

fun libFileName(base: String): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("win") -> "$base.dll"
        os.contains("mac") || os.contains("darwin") -> "lib$base.dylib"
        else -> "lib$base.so"
    }
}

val nativesRoot = layout.buildDirectory.dir("natives")

val buildCapstone = tasks.register<CmakeBuild>("buildCapstone") {
    source.set(layout.projectDirectory.dir("capstone"))
    workDir.set(layout.buildDirectory.dir("capstone-cmake"))
    output.set(nativesRoot.map { it.dir(jnaPlatform()).file(libFileName("capstone")) })
    cmake.set((project.findProperty("cmake.path") as String?) ?: "cmake")
    libName.set(libFileName("capstone"))
    version.set("capstone-5.0.1")
    extraArgs.set(listOf("-DCAPSTONE_BUILD_CSTOOL=OFF", "-DCAPSTONE_BUILD_TESTS=OFF"))
}

val buildKeystone = tasks.register<CmakeBuild>("buildKeystone") {
    source.set(layout.projectDirectory.dir("keystone"))
    workDir.set(layout.buildDirectory.dir("keystone-cmake"))
    output.set(nativesRoot.map { it.dir(jnaPlatform()).file(libFileName("keystone")) })
    cmake.set((project.findProperty("cmake.path") as String?) ?: "cmake")
    libName.set(libFileName("keystone"))
    version.set("keystone-0.9.2")
    extraArgs.set(listOf("-DBUILD_LIBS_ONLY=ON", "-DLLVM_TARGETS_TO_BUILD=AArch64;ARM;X86;Mips"))
}

val jrunasNdk = ndkDir()
if (jrunasNdk == null) logger.warn("buildJrunas: Android NDK not found (set ANDROID_NDK_HOME or install \$ANDROID_HOME/ndk) — jrunas will not be bundled")
val buildJrunas = tasks.register<BuildJrunas>("buildJrunas") {
    source.set(layout.projectDirectory.dir("jrunas"))
    workRoot.set(layout.buildDirectory.dir("jrunas-cmake"))
    outDir.set(nativesRoot.map { it.dir("jrunas") })
    cmake.set((project.findProperty("cmake.path") as String?) ?: "cmake")
    ndk.set(jrunasNdk ?: "")
}

sourceSets.named("main") { resources.srcDir(nativesRoot) }
tasks.named("processResources") { dependsOn(buildCapstone, buildKeystone, buildJrunas) }
