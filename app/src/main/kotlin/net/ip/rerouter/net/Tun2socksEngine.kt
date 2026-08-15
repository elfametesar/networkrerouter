package net.ip.rerouter.net

import android.content.Context
import net.ip.rerouter.model.ProxyProtocol
import net.ip.rerouter.model.Tun2socksConfig
import net.ip.rerouter.root.RootShell

data class Tun2socksStartResult(
    val isSuccess: Boolean,
    val error: String? = null
)

/**
 * Manages the bundled tun2socks binary (github.com/xjasonlyu/tun2socks,
 * gVisor-based) as a root-owned detached process: reads raw IP packets off
 * a TUN interface this app already created, terminates real TCP/UDP flows
 * against gVisor's userspace stack, and forwards each flow through a
 * SOCKS5/HTTP/Shadowsocks proxy.
 *
 * We don't reimplement the TCP/IP stack ourselves — that's what tun2socks
 * (lwIP/gVisor-class engines) exists for. This class only owns finding the
 * bundled binary, building its command line from a Tun2socksConfig, and
 * managing its process lifecycle as a detached root process so it survives
 * independently of this app's own process.
 */
class Tun2socksEngine(private val context: Context) {

    private fun binaryPath(): String {
        // Bundled as app/src/main/jniLibs/<abi>/libtun2socks.so so AGP
        // extracts it to nativeLibraryDir with execute permission at
        // install time, regardless of OEM packaging/SELinux quirks.
        val nativeDir = context.applicationInfo.nativeLibraryDir
        return "$nativeDir/libtun2socks.so"
    }

    private fun runDir(): String = "${context.filesDir}/tun2socks"
    private fun pidFile(id: String): String = "${runDir()}/$id.pid"
    private fun logFile(id: String): String = "${runDir()}/$id.log"

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun proxyUrl(config: Tun2socksConfig): String? {
        if (config.proxyAddress.isBlank()) return null
        val scheme = when (config.proxyProtocol) {
            ProxyProtocol.SOCKS5 -> "socks5"
            ProxyProtocol.HTTP -> "http"
            ProxyProtocol.SHADOWSOCKS -> "ss"
            ProxyProtocol.DIRECT -> return null
        }
        val auth = if (!config.proxyUsername.isNullOrBlank()) {
            val user = config.proxyUsername
            val pass = config.proxyPassword.orEmpty()
            "$user:$pass@"
        } else ""
        return "$scheme://$auth${config.proxyAddress}"
    }

    /**
     * Starts (or restarts) a tun2socks session for the given config. `realInterface`,
     * when provided, is passed as `-interface` so tun2socks dials the proxy out
     * through that specific device rather than whatever the kernel's default route
     * currently picks — the same live-detected value RoutingEngine/InterfaceRepository
     * already use elsewhere (detectRealRoutingTable()), never hardcoded here.
     */
    suspend fun start(config: Tun2socksConfig, realInterface: String? = null): Tun2socksStartResult {
        val binary = binaryPath()
        if (!RootShell.exec("test -x ${shellQuote(binary)}").isSuccess) {
            return Tun2socksStartResult(false, "tun2socks binary not found or not executable at $binary")
        }
        val proxy = proxyUrl(config)
            ?: return Tun2socksStartResult(false, "No proxy address configured")

        RootShell.exec("mkdir -p ${shellQuote(runDir())}")

        // Stop any previous instance for this session id first so we never
        // end up with two processes fighting over the same TUN device.
        stop(config.id)

        val args = mutableListOf(
            shellQuote(binary),
            "-device", "tun://${config.tunInterface}",
            "-proxy", shellQuote(proxy),
            "-mtu", config.mtu.toString(),
            "-loglevel", "info"
        )
        if (!realInterface.isNullOrBlank()) {
            args.add("-interface")
            args.add(shellQuote(realInterface))
        }
        config.apiPort?.let {
            args.add("-stats")
            args.add("127.0.0.1:$it")
        }

        val command = args.joinToString(" ")
        val started = RootShell.startDetached(command, pidFile(config.id), logFile(config.id))
        if (!started) return Tun2socksStartResult(false, "Failed to launch tun2socks process")

        return Tun2socksStartResult(true)
    }

    suspend fun stop(sessionId: String): Boolean = RootShell.stopDetached(pidFile(sessionId))

    suspend fun isRunning(sessionId: String): Boolean = RootShell.isProcessRunning(pidFile(sessionId))

    suspend fun recentLog(sessionId: String, lines: Int = 200): List<String> = RootShell.tailLog(logFile(sessionId), lines)
}
