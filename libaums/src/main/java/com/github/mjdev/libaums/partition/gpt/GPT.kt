package com.github.mjdev.libaums.partition.gpt

import com.github.mjdev.libaums.driver.BlockDeviceDriver
import com.github.mjdev.libaums.partition.PartitionTable
import com.github.mjdev.libaums.partition.PartitionTableEntry
import java.io.IOException
import java.nio.ByteBuffer
import java.util.ArrayList

class GPT private constructor(): PartitionTable {

    // See also https://zh.wikipedia.org/wiki/GUID%E7%A3%81%E7%A2%9F%E5%88%86%E5%89%B2%E8%A1%

    private val partitions = ArrayList<PartitionTableEntry>()

    override val size: Int get() =  partitions.size * 128
    override val partitionTableEntries: List<PartitionTableEntry>
        get() = partitions

    companion object {

        const val EFI_PART = "EFI PART"

        @Throws(IOException::class)
        fun read(blockDevice: BlockDeviceDriver): GPT? {
            val result = GPT()
            val buffer = ByteBuffer.allocate(Math.max(0x800, blockDevice.blockSize)) // 0x800 = 4096
            blockDevice.read(1, buffer) // LBA 1~4

            if (buffer.isGPT) {
                var offset = 0x200
                while (offset < 0x800 && buffer.getInt(offset) != 0) {
                    val beginLBA = buffer.get(offset + 33).toInt() shl 8 + buffer.get(offset + 32).toInt()
                    val endLBA = buffer.get(offset + 41).toInt() shl 8 + buffer.get(offset + 40).toInt()

                    val entry = PartitionTableEntry(0, beginLBA, endLBA)
                    offset += 0x80
                    result.partitions.add(entry)
                }
            } else {
                throw IOException("not a valid GPT")
            }

            return result
        }
    }

}

val ByteBuffer.isGPT get() =
    get(0x00).toInt() == 69 // E
            && get(0x01).toInt() == 70 // F
            && get(0x02).toInt() == 73 // I
            && get(0x03).toInt() == 32 // (space)
            && get(0x04).toInt() == 80 // P
            && get(0x05).toInt() == 65 // A
            && get(0x06).toInt() == 82 // R
            && get(0x07).toInt() == 84 // T
