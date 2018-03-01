package com.wintertechforum.nailgunpool

import com.martiansoftware.nailgun.Alias
import com.martiansoftware.nailgun.NGContext
import com.martiansoftware.nailgun.NGServer
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.runBlocking
import mu.KotlinLogging
import java.net.InetAddress

private val log = KotlinLogging.logger { }

fun main(args: Array<String>) {
  NailGunPool.run()
}

object NailGunPool : AutoCloseable, Runnable {
  private val pool = mutableListOf<NailGunWorker>()
  private var nextExecutor = 0
  private val server = NGServer(InetAddress.getLocalHost(), 2113).apply {
    setAllowNailsByClassName(false)
    aliasManager.apply {
      removeAlias("ng-alias")
      removeAlias("ng-cp")
      removeAlias("ng-stop")
      Command.values().forEach {
        addAlias(Alias(it.command, it.description, NailGunPool::class.java))
      }
    }
  }

  override fun run() {
    val serverRun = async {
      server.run()
    }
    log.info { "Startup complete." }
    runBlocking {
      serverRun.await()
    }
  }

  @Suppress("unused")
  @JvmStatic
  fun nailMain(context: NGContext) {
    Command[context.command](context.args)
  }

  fun addExecutors(count:Int) {
    for (i in 0 until count) {
      pool.add(NailGunWorker().apply { start() })
    }
  }

  fun submitTask() {
    if (pool.isEmpty()) throw IllegalStateException("Pool is empty")
    if (nextExecutor >= pool.size) nextExecutor = 0
    pool[nextExecutor++].submitTask()
  }

  override fun close() {
    log.info { "Closing pool of ${pool.size}" }
    pool.forEach { it.stop() }
    server.shutdown(false)
  }
}

private enum class Command(val description: String, val action: NailGunPool.(Array<String>) -> Unit) {
  SAY("Echo arguments to stdout", {
    val message = it.joinToString(" ")
    log.info { "Saying: $message" }
    println(message)
  }),
  START_POOL("Start the nailgun pool", {
    val count = it[0].toInt()
    addExecutors(count)
  }),
  START_TASK("Start a task on the nailgun pool", {
    val count = if (it.size == 1) it[0].toInt() else 1
    for (i in 0 until count) {
      submitTask()
    }
  }),
  STOP_POOL("Stop the nailgun pool", {
    close()
  });

  val command = name.toLowerCase().replace('_', '-')

  companion object {
    private val byCommand = values().associateBy { it.command }
    operator fun get(command: String) = byCommand[command] ?: throw IllegalArgumentException("Unknown command $command")
  }

  operator fun invoke(args: Array<String>) {
    NailGunPool.action(args)
  }
}
