package art.boox.probe

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.InflaterInputStream

/** Pixel arrays transfer ownership at capture. A worker may only replace the entire
 * immutable representation; readers retain either the old raw tile or the new bytes. */
internal class HistoryTile private constructor(private val count: Int, initial: Data) {
    private sealed interface Data
    private class Raw(val pixels: IntArray) : Data
    private class Packed(val bytes: ByteArray) : Data
    @Volatile private var data = initial
    val byteSize: Int get() = when (val current = data) {
        is Raw -> current.pixels.size * 4
        is Packed -> current.bytes.size
    }

    fun pixels(): IntArray = when (val current = data) {
        is Raw -> current.pixels
        is Packed -> {
            val raw = ByteArray(count * 4)
            DataInputStream(InflaterInputStream(ByteArrayInputStream(current.bytes))).use { it.readFully(raw) }
            IntArray(count).also { ByteBuffer.wrap(raw).asIntBuffer().get(it) }
        }
    }

    fun samePixels(other: HistoryTile): Boolean {
        val left = data; val right = other.data
        return when {
            left is Raw && right is Raw -> left.pixels.contentEquals(right.pixels)
            left is Packed && right is Packed -> left.bytes.contentEquals(right.bytes)
            else -> pixels().contentEquals(other.pixels())
        }
    }

    fun compress(codec: HistoryTileCodec) {
        val raw = data as? Raw ?: return
        val packed = codec.encode(raw.pixels)
        // Retained bytes must never grow on the worker: history budgets remain valid
        // without callbacks that could reorder edits or resurrect an evicted action.
        if (packed.size < raw.pixels.size * 4) data = Packed(packed)
    }

    companion object {
        fun raw(pixels: IntArray) = HistoryTile(pixels.size, Raw(pixels))
        fun packed(bytes: ByteArray, count: Int) = HistoryTile(count, Packed(bytes))
    }
}

/** Same lossless big-endian ARGB/zlib representation as synchronous history.
 * One codec reuses its scratch/native compressor across every tile in a job. */
internal class HistoryTileCodec : java.io.Closeable {
    private val raw = ByteArray(128 * 128 * 4)
    private val ints = ByteBuffer.wrap(raw).asIntBuffer()
    private val output = ByteArrayOutputStream(1024)
    private val compressed = ByteArray(8192)
    private val deflater = Deflater(Deflater.BEST_SPEED)

    fun encode(pixels: IntArray, count: Int = pixels.size): ByteArray {
        require(count in 1..minOf(pixels.size, raw.size / 4))
        ints.rewind(); ints.put(pixels, 0, count)
        output.reset(); deflater.reset(); deflater.setInput(raw, 0, count * 4); deflater.finish()
        while (!deflater.finished()) {
            val size = deflater.deflate(compressed)
            check(size > 0) { "History compression made no progress" }
            output.write(compressed, 0, size)
        }
        return output.toByteArray()
    }

    override fun close() { deflater.end() }
}
