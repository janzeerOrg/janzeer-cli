package org.janzeer.jz.out

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.table.Borders
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Everything a command prints goes through here. Human mode renders text and tables on stdout; `--json` prints exactly
 * one JSON document on stdout; warnings and errors always go to stderr so stdout stays parseable.
 */
class Output(
    val json: Boolean,
    val quiet: Boolean,
    val verbose: Boolean,
    noColor: Boolean,
    private val tty: Boolean,
    private val stdout: (String) -> Unit,
    private val stderr: (String) -> Unit,
) {
    val terminal: Terminal = if (tty) Terminal(ansiLevel = if (noColor) AnsiLevel.NONE else null, interactive = true) else Terminal(ansiLevel = AnsiLevel.NONE, width = 200, interactive = false)
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
    /** Styles are applied only when the terminal renders colour; piped output stays plain text. */
    private val color: Boolean = terminal.terminalInfo.ansiLevel != AnsiLevel.NONE
    fun dim(s: String) = if (color) TextStyles.dim(s) else s
    fun bold(s: String) = if (color) TextStyles.bold(s) else s
    private fun red(s: String) = if (color) TextColors.red(s) else s
    private fun green(s: String) = if (color) TextColors.green(s) else s
    private fun yellow(s: String) = if (color) TextColors.yellow(s) else s

    /** Plain line, suppressed by --quiet and in JSON mode. */
    fun line(text: String = "") { if (!json && !quiet) stdout(text) }

    /** A key/value block (`info`, `wallet show`…): keys padded to one width, values never truncated. */
    fun kv(rows: List<Pair<String, String>>) {
        if (json || quiet || rows.isEmpty()) return
        val w = rows.maxOf { it.first.length }
        rows.forEach { (k, v) -> stdout(dim(k.padEnd(w)) + "  " + v) }
    }

    /** A bordered table with a header row. [numeric] columns are right-aligned. */
    fun table(header: List<String>, rows: List<List<String>>, numeric: Set<Int> = emptySet()) {
        if (json || quiet) return
        if (rows.isEmpty()) { stdout(dim("(none)")); return }
        val t = table {
            header { row(*header.map { bold(it) }.toTypedArray()) }
            body { rows.forEach { row(*it.toTypedArray()) } }
            numeric.forEach { i -> column(i) { align = com.github.ajalt.mordant.rendering.TextAlign.RIGHT } }
        }
        stdout(terminal.render(t))
    }

    fun widget(w: Widget) { if (!json && !quiet) stdout(terminal.render(w)) }

    /** The one JSON document of a `--json` run. In human mode nothing is printed (callers render themselves). */
    fun data(value: JsonElement) { if (json) stdout(pretty.encodeToString(JsonElement.serializer(), value)) }

    /** A single scalar for scripts (`balance`, `nonce`): printed in both modes. */
    fun scalar(value: String) { if (json) stdout(pretty.encodeToString(JsonElement.serializer(), JsonPrimitive(value))) else stdout(value) }

    /** Pre-formatted text that IS the output (a file's content): printed verbatim in both modes. */
    fun scalarRaw(text: String) { stdout(text) }

    fun warn(text: String) { stderr(if (json) text else yellow("warning: ") + text) }
    fun debug(text: String) { if (verbose) stderr(dim("debug: $text")) }

    /** Final error report. JSON mode emits `{"error":{code,type,message,hint?}}` on stderr. */
    fun error(code: Int, kind: String, message: String, hint: String? = null) {
        if (json) {
            val o: JsonObject = buildJsonObject {
                put("error", buildJsonObject { put("code", code); put("type", kind); put("message", message); if (hint != null) put("hint", hint) })
            }
            stderr(pretty.encodeToString(JsonElement.serializer(), o))
        } else {
            stderr(red("error: ") + message)
            if (hint != null) stderr(dim("  $hint"))
        }
    }

    fun success(text: String) { if (!json && !quiet) stdout(green("✓ ") + text) }
}
