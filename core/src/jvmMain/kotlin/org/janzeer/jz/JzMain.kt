package org.janzeer.jz

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import org.janzeer.jz.api.Services
import org.janzeer.jz.out.Output
import org.janzeer.jz.platform.Platform
import org.janzeer.jz.sdk.SdkServices

/** Process entry used by the app module and by tests: returns the exit code instead of calling exitProcess. */
object JzMain {
    fun run(args: Array<String>, services: Services = SdkServices(), stdout: (String) -> Unit = ::println, stderr: (String) -> Unit = System.err::println): Int {
        val tty = Platform.stdoutIsTty()
        val cmd = Jz.build(services, stdout, stderr, tty)
        // Errors may happen before the root command ran (bad option) → a fallback Output that honours --json / --no-color.
        fun out(): Output = (cmd.currentContextOrNull()?.data?.get(com.github.ajalt.clikt.core.Context.DEFAULT_OBJ_KEY) as? JzContext)?.out
            ?: Output(json = "--json" in args, quiet = false, verbose = false, noColor = "--no-color" in args, tty = tty, stdout = stdout, stderr = stderr)
        fun ctx(): JzContext? = cmd.currentContextOrNull()?.data?.get(com.github.ajalt.clikt.core.Context.DEFAULT_OBJ_KEY) as? JzContext
        return try {
            cmd.parse(Jz.hoistGlobals(args.toList()))
            ExitCodes.OK
        } catch (e: PrintHelpMessage) {
            cmd.echoFormattedHelp(e); e.statusCode
        } catch (e: PrintMessage) {
            cmd.echoFormattedHelp(e); e.statusCode
        } catch (e: ProgramResult) {
            e.statusCode
        } catch (e: UsageError) {
            cmd.echoFormattedHelp(e); ExitCodes.USAGE
        } catch (e: CliktError) {
            cmd.echoFormattedHelp(e); if (e.statusCode == 0) ExitCodes.UNEXPECTED else e.statusCode
        } catch (e: JzException) {
            out().error(e.exitCode, e.kind, e.message ?: e.kind, e.hint)
            if (out().verbose) e.cause?.printStackTrace()
            e.exitCode
        } catch (e: InterruptedException) {
            ExitCodes.INTERRUPTED
        } catch (e: Throwable) {
            out().error(ExitCodes.UNEXPECTED, "unexpected", e.toString())
            if (out().verbose) e.printStackTrace()
            ExitCodes.UNEXPECTED
        } finally {
            runCatching { ctx()?.close() }
        }
    }

    private fun Jz.currentContextOrNull() = try { currentContext } catch (_: IllegalStateException) { null }
}
