package org.janzeer.jz

import org.janzeer.jz.platform.Platform

/** Password and confirmation input. Never an option on the command line (process lists leak); never echoed. */
object Prompts {
    /**
     * Order: `--password-file` / `JZ_PASSWORD_FILE` (first line) → terminal prompt without echo → exit 8.
     * [confirm] asks twice (new wallets) and refuses a mismatch or an empty password.
     */
    fun password(ctx: JzContext, prompt: String, confirm: Boolean = false): String {
        ctx.passwordFile?.let { f ->
            if (!Platform.exists(f)) throw JzException(ExitCodes.WALLET, "password", "password file not found: $f")
            val p = Platform.readText(f).lineSequence().firstOrNull()?.trimEnd('\r') ?: ""
            if (p.isEmpty()) throw JzException(ExitCodes.WALLET, "password", "password file is empty: $f")
            return p
        }
        val first = Platform.readPassword("$prompt: ") ?: throw JzException(ExitCodes.WALLET, "password",
            "no terminal to ask for the password", "pass --password-file FILE or set JZ_PASSWORD_FILE (first line = password)")
        if (first.isEmpty()) throw JzException(ExitCodes.WALLET, "password", "empty password refused")
        if (confirm) {
            val second = Platform.readPassword("Repeat password: ") ?: ""
            if (first != second) throw JzException(ExitCodes.WALLET, "password", "passwords do not match")
        }
        return first
    }

    /** `Proceed? [y/N]` unless `--yes`. Without a terminal and without `--yes` the action is refused. */
    fun confirm(ctx: JzContext, question: String): Boolean {
        if (ctx.yes) return true
        if (!Platform.stdinIsTty()) throw JzException(ExitCodes.USAGE, "confirm", "confirmation needed but no terminal", "add --yes to confirm non-interactively")
        val a = Platform.readLine("$question [y/N] ") ?: return false
        return a.trim().lowercase() in setOf("y", "yes")
    }

    /** The user must type [word] exactly (secret reveals, validator exits). `--yes` skips it. */
    fun typed(ctx: JzContext, word: String, why: String) {
        if (ctx.yes) return
        if (!Platform.stdinIsTty()) throw JzException(ExitCodes.USAGE, "confirm", "$why needs a terminal", "add --yes to confirm non-interactively")
        val a = Platform.readLine("$why — type '$word' to continue: ")
        if (a?.trim() != word) throw JzException(ExitCodes.USAGE, "confirm", "cancelled")
    }
}
