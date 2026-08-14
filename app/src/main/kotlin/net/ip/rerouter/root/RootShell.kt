package net.ip.rerouter.root

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin wrapper around libsu's root shell. All privileged command execution in
 * the app goes through here so there's one place that owns the root session,
 * one place that logs what was run, and one place to swap implementations if
 * we ever need to (e.g. Magisk vs KernelSU differences).
 */
object RootShell {

    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(15)
        )
    }

    data class CommandResult(
        val command: String,
        val exitCode: Int,
        val out: List<String>,
        val isSuccess: Boolean
    )

    /** Confirms root is actually available and granted, not just that `su` exists. */
    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        Shell.getShell().isRoot
    }

    /** Runs a single command as root and returns structured output. */
    suspend fun exec(command: String): CommandResult = withContext(Dispatchers.IO) {
        val result = Shell.cmd(command).exec()
        CommandResult(
            command = command,
            exitCode = result.code,
            out = result.out,
            isSuccess = result.isSuccess
        )
    }

    /** Runs several commands as one batched root session (fewer su round-trips). */
    suspend fun execBatch(commands: List<String>): List<CommandResult> = withContext(Dispatchers.IO) {
        if (commands.isEmpty()) return@withContext emptyList()
        val result = Shell.cmd(*commands.toTypedArray()).exec()
        // libsu gives us combined output for a batch; if per-command results are
        // needed the caller should prefer sequential exec() calls instead.
        listOf(
            CommandResult(
                command = commands.joinToString(" && "),
                exitCode = result.code,
                out = result.out,
                isSuccess = result.isSuccess
            )
        )
    }

    /** Runs commands sequentially, stopping at the first failure. Returns all results so far. */
    suspend fun execSequential(commands: List<String>): List<CommandResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<CommandResult>()
        for (cmd in commands) {
            val r = exec(cmd)
            results.add(r)
            if (!r.isSuccess) break
        }
        results
    }
}
