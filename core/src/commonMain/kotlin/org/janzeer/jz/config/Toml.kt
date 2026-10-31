package org.janzeer.jz.config

import org.janzeer.jz.ExitCodes
import org.janzeer.jz.JzException

/**
 * The TOML subset `jz.toml` uses: `[table]` / `[a.b]` headers, `key = "string" | 123 | true | false`, `#` comments,
 * bare keys. Nothing else — a hand parser keeps the native image free of a reflective TOML library.
 */
object Toml {
    /** Flattened map: `profiles.mainnet.node` → value (String, Long or Boolean). */
    fun parse(text: String): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        var prefix = ""
        text.lineSequence().forEachIndexed { i, raw ->
            val line = stripComment(raw).trim()
            if (line.isEmpty()) return@forEachIndexed
            val no = i + 1
            if (line.startsWith("[")) {
                if (!line.endsWith("]") || line.startsWith("[[")) fail(no, "bad table header")
                val name = line.substring(1, line.length - 1).trim()
                if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) fail(no, "bad table name '$name'")
                prefix = "$name."
                return@forEachIndexed
            }
            val eq = line.indexOf('=')
            if (eq <= 0) fail(no, "expected key = value")
            val key = line.substring(0, eq).trim()
            if (!key.all { it.isLetterOrDigit() || it == '_' || it == '-' }) fail(no, "bad key '$key'")
            val value = line.substring(eq + 1).trim()
            out[prefix + key] = parseValue(value, no)
        }
        return out
    }

    private fun parseValue(v: String, no: Int): Any = when {
        v.startsWith("\"") -> {
            if (v.length < 2 || !v.endsWith("\"")) fail(no, "unterminated string")
            unescape(v.substring(1, v.length - 1), no)
        }
        v == "true" -> true
        v == "false" -> false
        v.toLongOrNull() != null -> v.toLong()
        else -> fail(no, "unsupported value $v (use a quoted string, an integer or true/false)")
    }

    private fun unescape(s: String, no: Int): String {
        if (!s.contains('\\')) return s
        val b = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\') { b.append(c); i++; continue }
            if (i + 1 >= s.length) fail(no, "dangling backslash")
            when (val e = s[i + 1]) {
                '"' -> b.append('"'); '\\' -> b.append('\\'); 'n' -> b.append('\n'); 't' -> b.append('\t')
                else -> fail(no, "unsupported escape \\$e")
            }
            i += 2
        }
        return b.toString()
    }

    /** A `#` outside quotes starts a comment. */
    private fun stripComment(line: String): String {
        var inStr = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && inStr) { i += 2; continue }
            if (c == '"') inStr = !inStr
            if (c == '#' && !inStr) return line.substring(0, i)
            i++
        }
        return line
    }

    private fun fail(line: Int, what: String): Nothing =
        throw JzException(ExitCodes.USAGE, "config", "jz.toml line $line: $what")

    /** Serialize the subset back (used by `config init`). Keys are grouped by their table prefix in first-seen order. */
    fun render(values: Map<String, Any>, comments: Map<String, String> = emptyMap()): String {
        val b = StringBuilder()
        val tables = LinkedHashMap<String, MutableList<Pair<String, Any>>>()
        for ((k, v) in values) {
            val dot = k.lastIndexOf('.')
            val table = if (dot < 0) "" else k.substring(0, dot)
            tables.getOrPut(table) { mutableListOf() }.add((if (dot < 0) k else k.substring(dot + 1)) to v)
        }
        tables.forEach { (table, kvs) ->
            if (table.isNotEmpty()) { if (b.isNotEmpty()) b.append('\n'); b.append('[').append(table).append("]\n") }
            for ((k, v) in kvs) {
                val full = if (table.isEmpty()) k else "$table.$k"
                b.append(k).append(" = ").append(renderValue(v))
                comments[full]?.let { b.append("   # ").append(it) }
                b.append('\n')
            }
        }
        return b.toString()
    }

    private fun renderValue(v: Any): String = when (v) {
        is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Boolean, is Long, is Int -> v.toString()
        else -> "\"$v\""
    }
}
