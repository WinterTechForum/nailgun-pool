package com.wintertechforum.nailgunpool

import com.google.common.base.StandardSystemProperty
import kotlinx.coroutines.experimental.async
import mu.KLogging
import java.io.File
import java.util.concurrent.TimeUnit

class NailGunWorker(heapSize: String = "8m") {
  companion object : KLogging()

  private val startupLineRegex = Regex(".*? started on ([\\d.]+), port (\\d+)\\.$")
  private val javaHome = StandardSystemProperty.JAVA_HOME.value()
  private val classpath = StandardSystemProperty.JAVA_CLASS_PATH.value()
  private val javaExecutable: String by lazy {
    val executable = File("$javaHome/bin/java")
    if (!executable.canExecute()) {
      throw IllegalArgumentException("Cannot find executable at $executable")
    }
    executable.absolutePath
  }
  private val command = listOf(
      javaExecutable,
      "-Xmx$heapSize",
      "-cp",
      classpath,
      "com.martiansoftware.nailgun.NGServer",
      "127.0.1.1:0"
  )

  class RunningWorker(
      private val process: Process,
      address: String,
      private val port: Int
  ) : AutoCloseable {
    val client = NailGunClient(address, port)
    override fun close() {
      logger.info { "Shutting down worker on port $port" }
      client {
        exit()
      }
      process.apply {
        waitFor(10, TimeUnit.SECONDS)
        logger.info { "Worker exited with: ${exitValue()}" }
      }
    }

    override fun toString(): String {
      return "RunningWorker(port=$port, client=$client)"
    }

  }

  private var worker: RunningWorker? = null

  fun start() {
    if (null != worker) throw IllegalStateException("Worker process already running")
    logger.info { "Running NG Server: $command" }
    val process = ProcessBuilder(command)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()

    val output = process!!.inputStream
    val startupLine = output.bufferedReader().readLine()
    async {
      println(startupLine)
      output.bufferedReader().useLines {
        it.forEach {
          println(it)
        }
      }
    }
    val (address, port) = startupLineRegex.matchEntire(startupLine)
        ?.groupValues?.let {
      it[1] to it[2].toInt()
    }
        ?: throw IllegalStateException("Startup line did not match expected patter: $startupLine")
    worker = RunningWorker(
        process, address, port
    )
    logger.info { "Worker started: $worker" }
  }

  fun submitTask() {
    worker?.apply {
      client { submitTask() }
    } ?: IllegalStateException("No worker")
  }

  fun stop() {
    worker?.close() ?: throw IllegalStateException("Stopping stopped worker")
  }
}
