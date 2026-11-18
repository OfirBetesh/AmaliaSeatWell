package com.ofir.amaliaseatwell

import android.content.Context
import android.hardware.ConsumerIrManager

/** NEC infrared protocol (used by most cheap remotes): 8-bit address + 8-bit command. */
object Nec {
    const val FREQUENCY = 38000

    fun pattern(address: Int, command: Int): IntArray {
        val out = ArrayList<Int>(68)
        out.add(9000)   // leader mark
        out.add(4500)   // leader space
        val bytes = intArrayOf(
            address and 0xFF, address.inv() and 0xFF,
            command and 0xFF, command.inv() and 0xFF
        )
        for (b in bytes) {
            for (bit in 0 until 8) {             // LSB first
                out.add(562)
                out.add(if ((b shr bit) and 1 == 1) 1687 else 562)
            }
        }
        out.add(562)    // stop bit
        return out.toIntArray()
    }
}

class IrSender(context: Context) {
    private val ir = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    val available: Boolean
        get() = try { ir?.hasIrEmitter() == true } catch (e: Exception) { false }

    @Synchronized
    fun send(address: Int, command: Int): Boolean = try {
        ir?.transmit(Nec.FREQUENCY, Nec.pattern(address, command))
        ir != null
    } catch (e: Exception) {
        false
    }
}
