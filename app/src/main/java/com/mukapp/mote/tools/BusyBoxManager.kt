package com.mukapp.mote.tools

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import com.mukapp.mote.util.MoteLog
import java.io.File
import java.util.concurrent.TimeUnit

object BusyBoxManager {
    private const val Component = "BusyBox"
    private const val AssetRoot = "busybox"
    private const val ShellDirName = "shell"
    private const val BinDirName = "bin"
    private const val TmpDirName = "tmp"
    private const val AiTempDirName = "ai_tmp"
    private const val BusyBoxFileName = "busybox"
    private const val NativeBusyBoxFileName = "libbusybox.so"
    private const val InstallStampFileName = ".busybox_install"
    private const val InstallVersion = 1
    private const val InstallTimeoutSeconds = 15L
    private const val AndroidPathSeparator = ":"
    private const val DefaultAndroidPath = "/system/bin:/system/xbin:/vendor/bin:/odm/bin:/product/bin"

    private val initLock = Any()

    @Volatile
    private var cachedEnvironment: BusyBoxEnvironment? = null

    @Volatile
    private var cachedAiTempDir: File? = null

    @Volatile
    private var hasLoggedUnavailable = false

    data class BusyBoxEnvironment(
        val shellDir: File,
        val binDir: File,
        val tmpDir: File,
        val busyBox: File,
        val abi: String,
        val sourceId: String
    )

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        ensureAiTempDir(appContext)
        ensureInitialized(appContext)
    }

    fun ensureInitialized(context: Context): BusyBoxEnvironment? {
        cachedEnvironment?.let { return it }

        synchronized(initLock) {
            cachedEnvironment?.let { return it }

            val startMs = System.currentTimeMillis()
            MoteLog.i(
                Component,
                MoteLog.event(
                    "开始初始化 BusyBox",
                    "abiCount" to Build.SUPPORTED_ABIS.size,
                    "primaryAbi" to Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
                )
            )
            val environment = runCatching { installIfNeeded(context.applicationContext) }
                .onSuccess { installed ->
                    if (installed != null) {
                        MoteLog.i(
                            Component,
                            MoteLog.event(
                                "BusyBox 初始化成功",
                                "abi" to installed.abi,
                                "source" to installed.sourceKind,
                                "durationMs" to MoteLog.durationMs(startMs)
                            )
                        )
                    }
                }
                .onFailure { error -> MoteLog.w(Component, "BusyBox 初始化失败", error) }
                .getOrNull()

            if (environment == null && !hasLoggedUnavailable) {
                hasLoggedUnavailable = true
                MoteLog.w(Component, "未找到内置 BusyBox，将继续使用系统 PATH 执行 shell 命令。")
            }

            cachedEnvironment = environment
            return environment
        }
    }

    fun ensureAiTempDir(context: Context): File {
        cachedAiTempDir?.let { return it }

        synchronized(initLock) {
            cachedAiTempDir?.let { return it }

            val appContext = context.applicationContext
            val directory = resolveAiTempDir(appContext)
            cachedAiTempDir = directory
            MoteLog.i(
                Component,
                MoteLog.event("AI 临时目录已准备", "path" to directory.path)
            )
            return directory
        }
    }

    fun environmentOverrides(context: Context): Map<String, String> {
        val appContext = context.applicationContext
        val environment = ensureInitialized(appContext)
        val aiTempDir = ensureAiTempDir(appContext)
        return if (environment != null) {
            buildEnvironmentVariables(environment, System.getenv("PATH"))
        } else {
            buildFallbackEnvironmentVariables(aiTempDir, System.getenv("PATH"))
        }
    }

    fun environmentOverrides(): Map<String, String> {
        val environment = cachedEnvironment
        val aiTempDir = cachedAiTempDir
        return when {
            environment != null -> buildEnvironmentVariables(environment, System.getenv("PATH"))
            aiTempDir != null -> buildFallbackEnvironmentVariables(aiTempDir, System.getenv("PATH"))
            else -> emptyMap()
        }
    }

    internal fun buildEnvironmentVariables(
        environment: BusyBoxEnvironment,
        inheritedPath: String?
    ): Map<String, String> {
        return mapOf(
            "PATH" to buildPath(
                prefixes = listOf(environment.binDir.path, environment.shellDir.path),
                inheritedPath = inheritedPath
            ),
            "BUSYBOX" to environment.busyBox.path,
            "MOTE_SHELL_DIR" to environment.shellDir.path,
            "MOTE_AI_TMPDIR" to environment.tmpDir.path,
            "TMPDIR" to environment.tmpDir.path
        )
    }

    internal fun buildFallbackEnvironmentVariables(
        tmpDir: File,
        inheritedPath: String?
    ): Map<String, String> {
        return mapOf(
            "PATH" to buildPath(prefixes = emptyList(), inheritedPath = inheritedPath),
            "MOTE_AI_TMPDIR" to tmpDir.path,
            "TMPDIR" to tmpDir.path
        )
    }

    internal fun assetCandidatesForAbi(abi: String): List<String> {
        val aliases = when (abi) {
            "arm64-v8a" -> listOf("arm64-v8a", "aarch64")
            "armeabi-v7a" -> listOf("armeabi-v7a", "armv7", "arm")
            "armeabi" -> listOf("armeabi", "arm")
            "x86_64" -> listOf("x86_64", "amd64")
            "x86" -> listOf("x86", "i686")
            else -> listOf(abi)
        }

        return aliases.map { alias -> "$AssetRoot/$alias/$BusyBoxFileName" }
    }

    private fun installIfNeeded(context: Context): BusyBoxEnvironment? {
        val source = resolveBusyBoxSource(context) ?: return null
        MoteLog.d(
            Component,
            MoteLog.event(
                "已解析 BusyBox 来源",
                "abi" to source.abi,
                "source" to source.kind
            )
        )
        val shellDir = File(context.filesDir, ShellDirName)
        val binDir = File(shellDir, BinDirName)
        val tmpDir = ensureAiTempDir(context)
        prepareDirectory(shellDir)
        prepareDirectory(binDir)

        val busyBox = when (source) {
            is BusyBoxSource.NativeLibrary -> ensureNativeBusyBoxLauncher(shellDir, source.file)
            is BusyBoxSource.Asset -> {
                val target = File(shellDir, BusyBoxFileName)
                if (!isInstallCurrent(shellDir, binDir, target, source)) {
                    MoteLog.i(
                        Component,
                        MoteLog.event("复制内置 BusyBox", "abi" to source.abi)
                    )
                    copyAsset(context, source.path, target)
                } else {
                    MoteLog.d(
                        Component,
                        MoteLog.event("BusyBox 可执行文件缓存命中", "abi" to source.abi)
                    )
                    target.setExecutable(true, true)
                }
                target
            }
        }

        val environment = BusyBoxEnvironment(
            shellDir = shellDir,
            binDir = binDir,
            tmpDir = tmpDir,
            busyBox = busyBox,
            abi = source.abi,
            sourceId = source.id
        )

        if (!isInstallCurrent(shellDir, binDir, busyBox, source)) {
            resetManagedBinDir(shellDir, binDir)
            val installSucceeded = runCatching { runBusyBoxInstall(environment) }
                .onFailure { error -> MoteLog.w(Component, "BusyBox 软链接安装失败", error) }
                .isSuccess
            if (installSucceeded) {
                writeInstallStamp(shellDir, binDir, busyBox, source)
                MoteLog.i(
                    Component,
                    MoteLog.event("BusyBox applet 安装成功", "abi" to source.abi)
                )
            }
        } else {
            MoteLog.d(
                Component,
                MoteLog.event("BusyBox applet 缓存命中", "abi" to source.abi)
            )
        }

        return environment
    }

    private fun resolveAiTempDir(context: Context): File {
        resolveExternalAiTempDir(context)?.let { return it }

        val fallback = File(File(context.filesDir, ShellDirName), TmpDirName)
        prepareDirectory(fallback)
        MoteLog.w(
            Component,
            "无法使用应用专属外部临时目录，已回退到内部临时目录：${fallback.path}"
        )
        return fallback
    }

    private fun resolveExternalAiTempDir(context: Context): File? {
        val externalRoot = runCatching { context.getExternalFilesDir(null) }
            .onFailure { error -> MoteLog.w(Component, "获取应用专属外部目录失败。", error) }
            .getOrNull()
            ?: return null

        val directory = File(externalRoot, AiTempDirName)
        return runCatching {
            prepareDirectory(directory)
            directory
        }.onFailure { error ->
            MoteLog.w(Component, "创建应用专属外部临时目录失败：${directory.path}", error)
        }.getOrNull()
    }

    private fun resolveBusyBoxSource(context: Context): BusyBoxSource? {
        resolveNativeBusyBoxSource(context)?.let { return it }

        val supportedAbis = Build.SUPPORTED_ABIS.toList()

        supportedAbis.forEach { abi ->
            assetCandidatesForAbi(abi).forEach { path ->
                if (assetExists(context, path)) {
                    return BusyBoxSource.Asset(path = path, abi = abi)
                }
            }
        }

        return null
    }

    private fun resolveNativeBusyBoxSource(context: Context): BusyBoxSource.NativeLibrary? {
        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir?.takeIf { it.isNotBlank() } ?: return null
        val file = File(nativeLibraryDir, NativeBusyBoxFileName)
        if (!file.isFile || !file.canExecute()) {
            return null
        }

        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return BusyBoxSource.NativeLibrary(file = file, abi = abi)
    }

    private fun assetExists(context: Context, path: String): Boolean {
        return runCatching { context.assets.open(path).use { } }.isSuccess
    }

    private fun prepareDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("无法创建目录：${directory.path}")
        }
        if (!directory.isDirectory) {
            throw IllegalStateException("路径不是目录：${directory.path}")
        }
    }

    private fun ensureNativeBusyBoxLauncher(shellDir: File, nativeBusyBox: File): File {
        val launcher = File(shellDir, BusyBoxFileName)
        if (isSymbolicLinkTo(launcher, nativeBusyBox) && launcher.exists()) {
            return launcher
        }

        if (launcher.exists() || isSymbolicLink(launcher)) {
            deletePath(launcher)
        }

        return runCatching {
            Os.symlink(nativeBusyBox.path, launcher.path)
            launcher
        }.getOrElse { error ->
            MoteLog.w(Component, "无法创建 BusyBox 启动软链接，将直接使用原生库路径。", error)
            nativeBusyBox
        }
    }

    private fun copyAsset(context: Context, assetPath: String, target: File) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        deletePath(temp)
        context.assets.open(assetPath).use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }

        if (!temp.setReadable(true, true)) {
            MoteLog.w(Component, "无法设置 BusyBox 可读权限。")
        }
        if (!temp.setExecutable(true, true)) {
            throw IllegalStateException("无法设置 BusyBox 可执行权限：${temp.path}")
        }

        if (target.exists() || isSymbolicLink(target)) {
            deletePath(target)
        }
        if (!temp.renameTo(target)) {
            deletePath(temp)
            throw IllegalStateException("无法写入 BusyBox：${target.path}")
        }
    }

    private fun runBusyBoxInstall(environment: BusyBoxEnvironment) {
        val builder = ProcessBuilder(environment.busyBox.path, "--install", "-s", environment.binDir.path)
            .redirectErrorStream(true)
        builder.environment().putAll(buildEnvironmentVariables(environment, builder.environment()["PATH"]))

        val process = builder.start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val finished = process.waitFor(InstallTimeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw IllegalStateException("busybox --install 超时")
        }

        val exitCode = process.exitValue()
        if (exitCode != 0) {
            throw IllegalStateException("busybox --install 退出码 $exitCode：${output.trim()}")
        }
    }

    private fun isInstallCurrent(
        shellDir: File,
        binDir: File,
        busyBox: File,
        source: BusyBoxSource
    ): Boolean {
        if (!busyBox.exists()) {
            return false
        }

        val stamp = File(shellDir, InstallStampFileName)
        if (!stamp.isFile || stamp.readText() != buildInstallStamp(binDir, busyBox, source)) {
            return false
        }

        return binDir.listFiles()?.isNotEmpty() == true
    }

    private fun writeInstallStamp(
        shellDir: File,
        binDir: File,
        busyBox: File,
        source: BusyBoxSource
    ) {
        File(shellDir, InstallStampFileName).writeText(buildInstallStamp(binDir, busyBox, source))
    }

    private fun buildInstallStamp(binDir: File, busyBox: File, source: BusyBoxSource): String {
        return buildString {
            appendLine("version=$InstallVersion")
            appendLine("abi=${source.abi}")
            appendLine("source=${source.id}")
            appendLine("busybox=${busyBox.path}")
            appendLine("bin=${binDir.path}")
        }
    }

    private fun resetManagedBinDir(shellDir: File, binDir: File) {
        val shellCanonical = shellDir.canonicalFile
        val binCanonical = binDir.canonicalFile
        val expectedPrefix = shellCanonical.path + File.separator
        if (binCanonical.path != File(shellCanonical, BinDirName).canonicalPath ||
            !binCanonical.path.startsWith(expectedPrefix)
        ) {
            throw IllegalStateException("拒绝清理非托管目录：${binDir.path}")
        }

        prepareDirectory(binDir)
        binDir.listFiles()?.forEach { child -> deletePath(child) }
    }

    private fun deletePath(path: File) {
        if (!path.exists() && !isSymbolicLink(path)) {
            return
        }
        if (isSymbolicLink(path) || path.isFile) {
            if (!path.delete()) {
                throw IllegalStateException("无法删除文件：${path.path}")
            }
            return
        }
        if (!path.deleteRecursively()) {
            throw IllegalStateException("无法删除目录：${path.path}")
        }
    }

    private fun isSymbolicLinkTo(link: File, target: File): Boolean {
        if (!isSymbolicLink(link)) {
            return false
        }

        val actualTarget = runCatching { Os.readlink(link.path).let(::File).canonicalFile }.getOrNull()
        return actualTarget == target.canonicalFile
    }

    private fun isSymbolicLink(file: File): Boolean {
        return runCatching { (Os.lstat(file.path).st_mode and OsConstants.S_IFMT) == OsConstants.S_IFLNK }
            .getOrDefault(false)
    }

    private fun buildPath(prefixes: List<String>, inheritedPath: String?): String {
        val segments = (prefixes + (inheritedPath?.split(AndroidPathSeparator).orEmpty()))
            .map { it.trim().replace('\\', '/') }
            .filter { it.isNotEmpty() }
            .ifEmpty { DefaultAndroidPath.split(AndroidPathSeparator) }

        return segments.distinct().joinToString(AndroidPathSeparator)
    }

    private sealed class BusyBoxSource {
        abstract val abi: String
        abstract val id: String
        abstract val kind: String

        data class NativeLibrary(
            val file: File,
            override val abi: String
        ) : BusyBoxSource() {
            override val id: String = "native:${file.path}:${file.length()}:${file.lastModified()}"
            override val kind: String = "native"
        }

        data class Asset(
            val path: String,
            override val abi: String
        ) : BusyBoxSource() {
            override val id: String = "asset:$path:$InstallVersion"
            override val kind: String = "asset"
        }
    }

    private val BusyBoxEnvironment.sourceKind: String
        get() = sourceId.substringBefore(':').ifBlank { "unknown" }
}
