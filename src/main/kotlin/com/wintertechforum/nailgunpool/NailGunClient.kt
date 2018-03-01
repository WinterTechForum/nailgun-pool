package com.wintertechforum.nailgunpool

import kotlinx.coroutines.experimental.async
import mu.KLogging
import java.io.*
import java.net.Socket
import kotlin.reflect.KClass

class NailGunClient(private val address: String, private val port: Int) {
  operator fun invoke(command: NailGunCommander.() -> Unit) {
    val socket = Socket(address, port)
    NailGunCommander(socket.getOutputStream()).command()
    async {
      NailGunReader(socket.getInputStream()).run()
      socket.close()
    }
  }
}

class NailGunCommander(output: OutputStream) {
  private val out = DataOutputStream(output)
  private fun send(type: ChunkType, payload: String) {
    send(type, payload.toByteArray(Charsets.US_ASCII))
  }

  private fun send(type: ChunkType, payload: ByteArray) {
    out.writeInt(payload.size)
    out.writeByte(type.byte)
    out.write(payload)
  }

  private fun workingDir(dir: String) {
    send(ChunkType.DIR, dir)
  }

  private fun command(command: String) {
    send(ChunkType.CMD, command)
  }

  private fun command(command: KClass<*>) {
    command(command.qualifiedName ?: throw IllegalArgumentException("Class has no name"))
  }

  fun submitTask() {
    workingDir("/tmp")
    command(Task::class)
  }

  fun exit() {
    workingDir("/tmp")
    command("ng-stop")
  }
}

private enum class ChunkType(val char: Char) {
  ARG('A'),
  ENV('E'),
  DIR('D'),
  CMD('C'),
  IN('0'),
  OUT('1'),
  ERR('2'),
  START_IN('S'),
  IN_EOF('.'),
  EXIT('X');

  val byte = char.toInt()

  companion object {
    private val byChar = values().associateBy { it.char }
    fun byChar(char: Char) = byChar[char] ?: throw IllegalArgumentException("No chunk type $char")
  }
}


private class NailGunReader(input: InputStream) : Runnable {
  companion object : KLogging()

  private val data = DataInputStream(input)

  enum class State {
    AWAITING_HEADER, AWAITING_CHUNK_TYPE, READING_PAYLOAD, CHUNK_READ
  }

  private var state = State.AWAITING_HEADER
  private var sizeRemaining = 0
  private var type: ChunkType? = null
  private var payload: ByteArray? = null
  override fun run() {
    logger.debug { "Reader running" }
    try {
      while (true) {
        state = when (state) {
          State.AWAITING_HEADER -> {
            var size = 0L
            size = size.or((data.readByte().toLong()).shl(24))
            size = size.or((data.readByte().toLong()).shl(16))
            size = size.or((data.readByte().toLong()).shl(8))
            size = size.or((data.readByte().toLong()))
            logger.debug { "Read a size of $size" }
            sizeRemaining = size.toInt()
            State.AWAITING_CHUNK_TYPE
          }
          State.AWAITING_CHUNK_TYPE -> {
            type = ChunkType.byChar(data.readByte().toChar())
            logger.debug { "Read a chunk type of $type" }
            if (0 == sizeRemaining) State.CHUNK_READ else State.READING_PAYLOAD
          }
          State.READING_PAYLOAD -> {
            if (null == payload) payload = ByteArray(sizeRemaining)
            val read = data.read(payload)
            if (-1 == read) {
              throw EOFException()
            }
            sizeRemaining -= read
            logger.debug { "Payload bytes remaining: $sizeRemaining" }
            when {
              0 < sizeRemaining -> throw IllegalStateException("Read too much")
              0 == sizeRemaining -> State.CHUNK_READ
              else -> State.READING_PAYLOAD
            }
          }
          State.CHUNK_READ -> {
            logger.debug { "Read a complete chunk" }
            val payload = this.payload ?: throw IllegalStateException("No Payload")

            when (type) {
              ChunkType.OUT, ChunkType.ERR -> {
                String(payload).trimEnd().let {
                  if (!it.isEmpty()) {
                    val type = if (ChunkType.OUT == type) "output" else "stderr"
                    logger.info { "Task $type: ${String(payload).trimEnd()}" }
                  }
                }
              }
              ChunkType.EXIT -> logger.debug("Task exiting.")
              else -> throw IllegalStateException("Unexpected chunk type: $type")
            }

            type = null
            this.payload = null

            State.AWAITING_HEADER
          }
        }
      }
    }
    catch (e: EOFException) {
      logger.debug { "EOF" }
    }
  }
}
