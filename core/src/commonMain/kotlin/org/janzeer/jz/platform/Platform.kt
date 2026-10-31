package org.janzeer.jz.platform

/** The handful of OS services the command tree needs; `jvmMain` provides the actuals. */
expect object Platform {
    /** Environment variable or null. */
    fun env(name: String): String?
    /** `$XDG_CONFIG_HOME/janzeer`, `%APPDATA%\janzeer` or `~/.config/janzeer` — not created. */
    fun configDir(): String
    /** Directory holding wallet files (`configDir()/wallets`). */
    fun walletsDir(): String
    fun isWindows(): Boolean
    fun joinPath(vararg parts: String): String
    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun listFiles(dir: String): List<String>
    fun readText(path: String): String
    /** Creates parent directories; [private] = 0600 file in a 0700 dir where the OS supports it. */
    fun writeText(path: String, text: String, private: Boolean = false)
    fun delete(path: String)
    /** True when stdout is an interactive terminal. */
    fun stdoutIsTty(): Boolean
    /** True when stdin is an interactive terminal (prompts possible). */
    fun stdinIsTty(): Boolean
    /** Reads one line from stdin without echo; null when no terminal is available. */
    fun readPassword(prompt: String): String?
    /** Reads one visible line from stdin; null on EOF. */
    fun readLine(prompt: String): String?
    /** Absolute path of the running executable, or null when unknown. */
    fun executablePath(): String?
    /** Runs a process inheriting stdio; returns its exit code. */
    fun run(command: List<String>, workingDir: String?): Int
    /** Milliseconds since the epoch. */
    fun nowMs(): Long
    /** ISO-8601 UTC for an epoch millisecond value. */
    fun isoUtc(epochMs: Long): String
    /** Current UTC timestamp in ISO-8601. */
    fun nowIso(): String
    /** Register a callback for process termination (Ctrl-C). */
    fun onShutdown(block: () -> Unit)
    /** Block the calling thread until the process is terminated. */
    fun waitForever()
    /** Sleep. */
    fun sleepMs(ms: Long)
    /** Home directory. */
    fun homeDir(): String
    /** Last [n] lines of a text file (bounded read). */
    fun tail(path: String, n: Int): List<String>
    /** File size in bytes, or -1. */
    fun size(path: String): Long
    /** Read the bytes appended after [from]. */
    fun readFrom(path: String, from: Long): String
    /** Glob matches (`*` in the last path segments only), absolute paths. */
    fun glob(pattern: String): List<String>
    /** Last modification time (epoch ms), or 0. */
    fun modified(path: String): Long
}
