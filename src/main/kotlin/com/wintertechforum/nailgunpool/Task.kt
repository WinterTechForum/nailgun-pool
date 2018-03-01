package com.wintertechforum.nailgunpool

import java.lang.management.ManagementFactory
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger

class Task : Runnable {
  companion object {
    val counter = AtomicInteger()
    @JvmStatic
    fun main(args: Array<String>) {
      Task().run()
    }
  }

  private val workerNumber = counter.getAndIncrement()
  private val sleepTime = ThreadLocalRandom.current().nextLong(3000)
  private val evil = ThreadLocalRandom.current().nextInt(10) == 0
  private val pid = Regex("(\\d+)@.*").matchEntire(ManagementFactory.getRuntimeMXBean().name)?.groupValues?.get(1)
      ?: throw IllegalStateException("JVM Name did not match expected pattern")

  private fun say(message: String) {
    println("Worker #$workerNumber@$pid: $message")
  }

  override fun run() {
    say("started")
    Thread.sleep(sleepTime)
    if (evil) {
      say("MUAHAHAHA")
      generateSequence {
        Thread.sleep(1)
        ByteArray(8192)
      }.toList()
    }
    else {
      say("One of the good ones")
    }
  }
}
