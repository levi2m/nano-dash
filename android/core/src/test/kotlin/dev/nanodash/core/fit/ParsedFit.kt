package dev.nanodash.core.fit

/** A small independent reader for the bytes our writer produces, used only to check the writer's output. */
internal class ParsedFit(val bytes: ByteArray) {
    class Mesg(val global: Int, val fields: Map<Int, ByteArray>, val dev: Map<Int, ByteArray>)

    private class Def(val global: Int, val fields: List<Triple<Int, Int, Int>>, val dev: List<Triple<Int, Int, Int>>)

    val headerSize = bytes[0].toInt() and 0xFF
    val protocol = bytes[1].toInt() and 0xFF
    val profile = u(bytes, 2, 2)
    val dataSize = u(bytes, 4, 4)
    val signature = String(bytes, 8, 4, Charsets.US_ASCII)
    val headerCrcOk = u(bytes, 12, 2) == FitCrc.compute(bytes, 0, 12).toLong()
    val fileCrcOk = u(bytes, headerSize + dataSize.toInt(), 2) == FitCrc.compute(bytes, 0, headerSize + dataSize.toInt()).toLong()
    val messages = ArrayList<Mesg>()

    init {
        val defs = HashMap<Int, Def>()
        var p = headerSize
        val end = headerSize + dataSize.toInt()
        while (p < end) {
            val h = bytes[p++].toInt() and 0xFF
            val local = h and 0x0F
            if (h and 0x40 != 0) {
                p++                                       // reserved
                check(bytes[p++].toInt() == 0)            // little endian
                val global = u(bytes, p, 2).toInt(); p += 2
                val n = bytes[p++].toInt() and 0xFF
                val fields = (0 until n).map { Triple(b(p + it * 3), b(p + it * 3 + 1), b(p + it * 3 + 2)) }; p += n * 3
                var dev = emptyList<Triple<Int, Int, Int>>()
                if (h and 0x20 != 0) {
                    val dn = b(p++)
                    dev = (0 until dn).map { Triple(b(p + it * 3), b(p + it * 3 + 1), b(p + it * 3 + 2)) }; p += dn * 3
                }
                defs[local] = Def(global, fields, dev)
            } else {
                val d = defs.getValue(local)
                val f = LinkedHashMap<Int, ByteArray>()
                for ((num, size, _) in d.fields) { f[num] = bytes.copyOfRange(p, p + size); p += size }
                val dv = LinkedHashMap<Int, ByteArray>()
                for ((num, size, _) in d.dev) { dv[num] = bytes.copyOfRange(p, p + size); p += size }
                messages += Mesg(d.global, f, dv)
            }
        }
        check(p == end) { "parsed $p bytes of data, header says $end" }
    }

    private fun b(i: Int) = bytes[i].toInt() and 0xFF

    fun of(global: Int) = messages.filter { it.global == global }

    companion object {
        fun u(b: ByteArray, off: Int, n: Int): Long {
            var v = 0L
            for (i in 0 until n) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
            return v
        }
        fun s32(b: ByteArray): Int = u(b, 0, 4).toInt()
        fun f32(b: ByteArray): Float = java.lang.Float.intBitsToFloat(u(b, 0, 4).toInt())
    }
}
