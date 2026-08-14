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
}
