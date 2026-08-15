package net.ip.rerouter.root

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin wrapper around libsu's root shell.
 *
 * libsu configuration is initialized by RerouterApp before any shell is
 * created. This object only owns command execution and root-state checks.
 */
object RootShell {

    data class CommandResult(
        val command: String,
        val exitCode: Int,
        val out: List<String>,
        val err: List<String> = emptyList(),
        val isSuccess: Boolean
    )

    /**
     * Confirms root is actually available and granted,
     * not just that `su` exists.
     */
    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        Shell.getShell().isRoot
    }

    /**
     * Runs a single command as root and returns structured output.
     */
    suspend fun exec(command: String): CommandResult =
        withContext(Dispatchers.IO) {
            val result = Shell.cmd(command).exec()

            CommandResult(
                command = command,
                exitCode = result.code,
                out = result.out,
                err = result.err,
                isSuccess = result.isSuccess
            )
        }

    /**
     * Runs several commands as one batched root session.
     *
     * libsu gives us combined output for a batch. If per-command results
     * are needed, the caller should prefer sequential exec() calls.
     */
    suspend fun execBatch(commands: List<String>): List<CommandResult> =
        withContext(Dispatchers.IO) {
            if (commands.isEmpty()) {
                return@withContext emptyList()
            }

            val result = Shell.cmd(*commands.toTypedArray()).exec()

            listOf(
                CommandResult(
                    command = commands.joinToString(" && "),
                    exitCode = result.code,
                    out = result.out,
                    err = result.err,
                    isSuccess = result.isSuccess
                )
            )
        }

    /**
     * Runs commands sequentially, stopping at the first failure.
     * Returns all results collected so far.
     */
    suspend fun execSequential(commands: List<String>): List<CommandResult> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<CommandResult>()

            for (cmd in commands) {
                val result = exec(cmd)
                results.add(result)

                if (!result.isSuccess) {
                    break
                }
            }

            results
        }

    /**
     * Starts a long-running root process detached from this app's process
     * lifecycle (setsid + nohup + disown), writing its PID to pidFile so it
     * can be found and stopped later even across app restarts. Output is
     * redirected to logFile since nothing is left to consume stdout once
     * the shell session that launched it returns.
     *
     * Returns true if the launcher command itself succeeded (i.e. the
     * process was started) — this does not guarantee the process is still
     * alive moments later; use isProcessRunning(pidFile) to check.
     */
    suspend fun startDetached(command: String, pidFile: String, logFile: String): Boolean =
        withContext(Dispatchers.IO) {
            // Runs directly in the root shell libsu already gives us (no
            // extra `sh -c '...'` wrapper — nesting quoted strings inside
            // that wrapper breaks as soon as `command` itself contains a
            // single quote, which shellQuote-escaped arguments always do).
            // setsid: new session, so the process survives this shell exiting.
            // nohup: ignore SIGHUP for the same reason, belt and suspenders.
            // echo $! after backgrounding gives us the child PID to persist.
            val launch = "setsid nohup $command >$logFile 2>&1 < /dev/null & echo \$! > $pidFile"
            exec(launch).isSuccess
        }

    /** True if the PID recorded in pidFile refers to a currently running process. */
    suspend fun isProcessRunning(pidFile: String): Boolean = withContext(Dispatchers.IO) {
        val pid = exec("cat $pidFile 2>/dev/null").out.firstOrNull()?.trim()?.toIntOrNull() ?: return@withContext false
        exec("kill -0 $pid 2>/dev/null").isSuccess
    }

    /** Kills the process recorded in pidFile, if any, and removes the file. */
    suspend fun stopDetached(pidFile: String): Boolean = withContext(Dispatchers.IO) {
        val pid = exec("cat $pidFile 2>/dev/null").out.firstOrNull()?.trim()?.toIntOrNull()
        val killed = if (pid != null) exec("kill $pid 2>/dev/null; sleep 0.3; kill -9 $pid 2>/dev/null; true").isSuccess else true
        exec("rm -f $pidFile 2>/dev/null")
        killed
    }

    /** Tail of a log file written by a process started with startDetached, most recent lines last. */
    suspend fun tailLog(logFile: String, lines: Int = 200): List<String> =
        exec("tail -n $lines $logFile 2>/dev/null").out
}
