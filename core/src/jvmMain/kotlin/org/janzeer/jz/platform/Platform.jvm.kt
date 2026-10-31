package org.janzeer.jz.platform

import java.io.Console
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

actual object Platform {
    actual fun env(name: String): String? = System.getenv(name)
    actual fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

    actual fun configDir(): String {
        System.getProperty("jz.config.dir")?.takeIf { it.isNotBlank() }?.let { return it }
        env("JZ_CONFIG_DIR")?.takeIf { it.isNotBlank() }?.let { return it }
        if (isWindows()) env("APPDATA")?.takeIf { it.isNotBlank() }?.let { return joinPath(it, "janzeer") }
        env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { return joinPath(it, "janzeer") }
        return joinPath(System.getProperty("user.home"), ".config", "janzeer")
    }
    actual fun walletsDir(): String = joinPath(configDir(), "wallets")
    actual fun joinPath(vararg parts: String): String = Paths.get(parts.first(), *parts.drop(1).toTypedArray()).toString()
    actual fun exists(path: String): Boolean = Files.exists(Paths.get(path))
    actual fun isDirectory(path: String): Boolean = Files.isDirectory(Paths.get(path))
    actual fun listFiles(dir: String): List<String> {
        val p = Paths.get(dir)
        if (!Files.isDirectory(p)) return emptyList()
        return Files.list(p).use { s -> s.filter { Files.isRegularFile(it) }.map { it.toString() }.sorted().toList() }
    }
    actual fun readText(path: String): String = Files.readString(Paths.get(path))

    actual fun writeText(path: String, text: String, private: Boolean) {
        val p: Path = Paths.get(path)
        val dir = p.parent
        if (dir != null && !Files.exists(dir)) {
            Files.createDirectories(dir)
            if (private) setPerms(dir, "rwx------")
        }
        Files.writeString(p, text)
        if (private) setPerms(p, "rw-------")
    }
    private fun setPerms(p: Path, perms: String) {
        if (isWindows()) return
        try { Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perms)) } catch (_: UnsupportedOperationException) { }
    }
    actual fun delete(path: String) { Files.deleteIfExists(Paths.get(path)) }

    actual fun stdoutIsTty(): Boolean = System.console() != null || env("JZ_FORCE_TTY") == "1"
    actual fun stdinIsTty(): Boolean = System.console() != null

    actual fun readPassword(prompt: String): String? {
        val c: Console? = System.console()
        if (c != null) {
            val chars = c.readPassword("%s", prompt) ?: return null
            return String(chars).also { chars.fill('\u0000') }
        }
        // Native image or redirected stdin: try the controlling terminal through stty, else give up (caller falls back to a file).
        if (isWindows()) return null
        return try {
            val tty = java.io.File("/dev/tty")
            if (!tty.exists()) return null
            System.err.print(prompt); System.err.flush()
            ProcessBuilder("sh", "-c", "stty -echo < /dev/tty").inheritIO().start().waitFor()
            try {
                tty.bufferedReader().readLine()
            } finally {
                ProcessBuilder("sh", "-c", "stty echo < /dev/tty").inheritIO().start().waitFor()
                System.err.println()
            }
        } catch (_: Exception) { null }
    }

    actual fun readLine(prompt: String): String? {
        System.err.print(prompt); System.err.flush()
        return readlnOrNull()
    }

    actual fun executablePath(): String? {
        try {
            val self = Paths.get("/proc/self/exe")
            if (Files.exists(self)) return self.toRealPath().toString()
        } catch (_: Exception) { }
        return System.getProperty("jz.executable")
    }

    actual fun run(command: List<String>, workingDir: String?): Int {
        val pb = ProcessBuilder(command).inheritIO()
        if (workingDir != null) pb.directory(java.io.File(workingDir))
        return pb.start().waitFor()
    }

    actual fun nowMs(): Long = System.currentTimeMillis()
    private val iso = DateTimeFormatter.ISO_INSTANT
    actual fun isoUtc(epochMs: Long): String = iso.format(Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC))
    actual fun nowIso(): String = isoUtc(nowMs())
    actual fun onShutdown(block: () -> Unit) { Runtime.getRuntime().addShutdownHook(Thread { runCatching(block) }) }
    actual fun waitForever() { java.util.concurrent.CountDownLatch(1).await() }
    actual fun sleepMs(ms: Long) { Thread.sleep(ms) }
}
