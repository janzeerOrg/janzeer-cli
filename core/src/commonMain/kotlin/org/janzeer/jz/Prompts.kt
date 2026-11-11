package org.janzeer.jz

import org.janzeer.jz.api.KeyInfo
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

    /**
     * Open a wallet for signing: asks the password, and — when the wallet was created with a BIP39 passphrase — the
     * passphrase too (`JZ_PASSPHRASE_FILE`, first line, or a terminal prompt without echo). The passphrase is never stored;
     * a passphrase wallet is recognised by its stored address, which the phrase alone does not derive. Without this every
     * send from such a wallet was built from a DIFFERENT, empty account (review 2026-11-11). The derived address must
     * equal the stored one, or the command stops with exit 8.
     */
    fun unlock(ctx: JzContext, w: WalletFile): Pair<String, KeyInfo> {
        val pw = password(ctx, "Password for wallet '${w.name}'")
        val plain = ctx.wallets.open(w, pw)
        if (!w.isMnemonic || w.address.isEmpty() || plain.second.checksumAddress.equals(w.address, ignoreCase = true)) return plain
        val file = Platform.env("JZ_PASSPHRASE_FILE")
        val pp = if (file != null) {
            if (!Platform.exists(file)) throw JzException(ExitCodes.WALLET, "passphrase", "passphrase file not found: $file")
            Platform.readText(file).lineSequence().firstOrNull()?.trimEnd('\r') ?: ""
        } else Platform.readPassword("BIP39 passphrase for wallet '${w.name}': ") ?: throw JzException(ExitCodes.WALLET, "passphrase",
            "wallet '${w.name}' was created with a BIP39 passphrase and there is no terminal to ask for it", "set JZ_PASSPHRASE_FILE (first line = passphrase)")
        val withPp = ctx.wallets.open(w, pw, pp)
        if (!withPp.second.checksumAddress.equals(w.address, ignoreCase = true)) {
            throw JzException(ExitCodes.WALLET, "passphrase", "that passphrase does not give this wallet's address ${w.address}", "check the passphrase; nothing was signed or sent")
        }
        return withPp
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
