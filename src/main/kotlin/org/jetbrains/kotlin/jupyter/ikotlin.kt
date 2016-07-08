
package org.jetbrains.kotlin.jupyter

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import com.beust.klaxon.int
import com.beust.klaxon.string
import org.jetbrains.kotlin.cli.common.KotlinVersion
import org.jetbrains.kotlin.cli.jvm.repl.LineResult
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

data class KernelArgs(val cfgFile: File,
                      val classpath: List<File>)

private fun parseCommandLine(vararg args: String): KernelArgs {
    var cfgFile: File? = null
    var classpath: List<File>? = null
    args.forEach { when {
        it.startsWith("-cp=") || it.startsWith("-classpath=") -> {
            if (classpath != null) throw IllegalArgumentException("classpath already set to ${classpath!!.joinToString(File.pathSeparator)}")
            classpath = it.substringAfter('=').split(File.pathSeparator).map { File(it) }
        }
        else -> {
            if (cfgFile != null) throw IllegalArgumentException("config file already set to $cfgFile")
            cfgFile = File(it)
        }
    } }
    if (cfgFile == null) throw IllegalArgumentException("config file is not provided")
    if (!cfgFile!!.exists() || !cfgFile!!.isFile ) throw IllegalArgumentException("invalid config file $cfgFile")
    return KernelArgs(cfgFile!!, classpath ?: emptyList())
}

fun main(vararg args: String) {
    try {
        val (cfgFile, classpath) = parseCommandLine(*args)
        val cfgJson = Parser().parse(cfgFile.canonicalPath) as JsonObject
        fun JsonObject.getInt(field: String): Int = int(field) ?: throw RuntimeException("Cannot find $field in $cfgFile")

        val sigScheme = cfgJson.string("signature_scheme")
        val key = cfgJson.string("key")

        kernelServer(KernelConfig(
                ports = JupyterSockets.values().map { cfgJson.getInt("${it.name}_port") }.toTypedArray(),
                transport = cfgJson.string("transport") ?: "tcp",
                signatureScheme = sigScheme ?: "hmac1-sha256",
                signatureKey = if (sigScheme == null || key == null) "" else key,
                classpath = classpath
        ))
    }
    catch (e: Exception) {
        log.error("exception running kernel with args: \"${args.joinToString()}\"", e)
    }
}

fun kernelServer(config: KernelConfig) {
    log.info("Starting server: $config")

    JupyterConnection(config).use { conn ->

        log.info("start listening")

        val executionCount = AtomicLong(1)

        val repl = ReplForJupyter(conn)

        val mainThread = Thread.currentThread()

        val controlThread = thread {
            while (true) {
                try {
                    conn.heartbeat.onData { send(it, 0) }
                    conn.control.onMessage { shellMessagesHandler(it, null, executionCount) }

                    Thread.sleep(config.pollingIntervalMillis)
                }
                catch (e: InterruptedException) {
                    log.debug("Control: Interrupted")
                    mainThread.interrupt()
                    break
                }
            }
        }

        while (true) {
            try {
                conn.shell.onMessage { shellMessagesHandler(it, repl, executionCount) }

                Thread.sleep(config.pollingIntervalMillis)
            }
            catch (e: InterruptedException) {
                log.debug("Main: Interrupted")
                controlThread.interrupt()
                break
            }
        }

        try {
            controlThread.join()
        }
        catch (e: InterruptedException) {}

        log.info("Shutdown server")
    }
}

fun JupyterConnection.Socket.shellMessagesHandler(msg: Message, repl: ReplForJupyter?, executionCount: AtomicLong) {
    when (msg.header!!["msg_type"]) {
        "kernel_info_request" ->
            send(makeReplyMessage(msg, "kernel_info_reply",
                    content = jsonObject(
                            "protocol_version" to protocolVersion,
                            "language" to "Kotlin",
                            "language_version" to KotlinVersion.VERSION
                    )))
        "history_request" ->
            send(makeReplyMessage(msg, "history_reply",
                    content = jsonObject(
                            "history" to listOf<String>() // not implemented
                    )))
        "shutdown_request" -> {
            send(makeReplyMessage(msg, "shutdown_reply", content = msg.content))
            Thread.currentThread().interrupt()
        }
        "connect_request" ->
            send(makeReplyMessage(msg, "connection_reply",
                    content = jsonObject(JupyterSockets.values()
                                            .map { Pair("${it.name}_port", connection.config.ports[it.ordinal]) })))
        "execute_request" -> {
            connection.contextMessage = msg
            val count = executionCount.getAndIncrement()
            val startedTime = ISO8601DateNow
            with (connection.iopub) {
                send(makeReplyMessage(msg, "status", content = jsonObject("execution_state" to "busy")))
                send(makeReplyMessage(msg, "execute_input", content = jsonObject(
                        "execution_count" to count,
                        "code" to msg.content["code"])))
                val res = connection.evalWithIO { repl?.interpreter?.eval(msg.content["code"].toString()) }
                val resStr = when (res) {
                    is LineResult.ValueResult -> res.valueAsString
                    is LineResult.UnitResult -> "Ok"
                    is LineResult.Error -> "Error: ${res.errorText}"
                    is LineResult.Incomplete -> "..."
                    null -> "no repl"
                }
                send(makeReplyMessage(msg, "execute_result", content = jsonObject(
                        "execution_count" to count,
                        "data" to JsonObject(jsonObject("text/plain" to resStr)),
                        "metadata" to JsonObject())))
                send(makeReplyMessage(msg, "status", content = jsonObject("execution_state" to "idle")))
            }
            send(makeReplyMessage(msg, "execute_reply",
                    metadata = jsonObject(
                            "dependencies_met" to true,
                            "engine" to msg.header["session"],
                            "status" to "ok",
                            "started" to startedTime),
                    content = jsonObject(
                            "status" to "ok",
                            "execution_count" to count,
                            "user_variables" to JsonObject(),
                            "payload" to listOf<String>(),
                            "user_expressions" to JsonObject())
                    ))
            connection.contextMessage = null
        }
        "is_complete_request" -> {
            send(makeReplyMessage(msg, "is_complete_reply", content = jsonObject("status" to "complete")))
        }
        else -> send(makeReplyMessage(msg, "unsupported_message_reply"))
    }
}

