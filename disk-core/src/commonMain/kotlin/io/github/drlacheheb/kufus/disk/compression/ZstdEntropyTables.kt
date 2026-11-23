package io.github.drlacheheb.kufus.disk.compression

import io.github.drlacheheb.kufus.disk.io.requireStream

private const val MAX_HUFFMAN_SYMBOLS = 256
private const val MAX_HUFFMAN_BITS = 11

/**
 * Forward LSB-first bitstream reader for Zstandard FSE distribution headers.
 */
internal class ZstdForwardBitReader(
    private val data: ByteArray,
    private val startOffset: Int,
    private val endOffset: Int,
) {
    private var byteIndex = startOffset
    private var bitOffset = 0

    val bytesConsumed: Int
        get() = (byteIndex - startOffset) + if (bitOffset > 0) 1 else 0

    fun peekBits(count: Int): Int {
        var acc = 0
        var bIdx = byteIndex
        var bOff = bitOffset
        var remaining = count
        var shift = 0
        while (remaining > 0 && bIdx < endOffset) {
            val avail = 8 - bOff
            val take = minOf(remaining, avail)
            val mask = (1 shl take) - 1
            val bits = ((data[bIdx].toInt() and 0xFF) ushr bOff) and mask
            acc = acc or (bits shl shift)
            shift += take
            remaining -= take
            bOff += take
            if (bOff == 8) {
                bOff = 0
                bIdx++
            }
        }
        return acc
    }

    fun consumeBits(count: Int) {
        val totalBits = bitOffset + count
        byteIndex += totalBits ushr 3
        bitOffset = totalBits and 7
        requireStream(byteIndex < endOffset || (byteIndex == endOffset && bitOffset == 0), "CORRUPT_ZSTD") {
            "Unexpected end of FSE distribution header"
        }
    }

    fun readBits(count: Int): Int {
        val value = peekBits(count)
        consumeBits(count)
        return value
    }
}

/**
 * Backward MSB-first bitstream reader for sentinel-terminated Zstandard Huffman and Sequence streams.
 */
internal class ZstdBackwardBitReader(
    private val data: ByteArray,
    private val startOffset: Int,
    endOffset: Int,
) {
    private var byteCursor: Int
    private var bitContainer: ULong
    private var bitsInContainer: Int

    init {
        requireStream(endOffset > startOffset, "CORRUPT_ZSTD") { "Empty Zstd backward bitstream" }
        val sentinelByte = data[endOffset - 1].toInt() and 0xFF
        requireStream(sentinelByte != 0, "CORRUPT_ZSTD") { "Missing sentinel bit in Zstd stream" }
        val sentinelPos = 31 - sentinelByte.countLeadingZeroBits()
        byteCursor = endOffset - 1
        bitContainer = (sentinelByte and ((1 shl sentinelPos) - 1)).toULong()
        bitsInContainer = sentinelPos
    }

    fun remainingBits(): Long = (byteCursor - startOffset).toLong() * 8L + bitsInContainer.toLong()

    fun readBitsStrict(count: Int): Int {
        if (count == 0) return 0
        refill(count)
        requireStream(bitsInContainer >= count, "CORRUPT_ZSTD") { "Truncated Zstd backward bitstream ($bitsInContainer < $count)" }
        bitsInContainer -= count
        return ((bitContainer shr bitsInContainer) and ((1uL shl count) - 1uL)).toInt()
    }

    fun peekBitsPadded(count: Int): Int {
        if (count == 0) return 0
        refill(count)
        if (bitsInContainer >= count) {
            return ((bitContainer shr (bitsInContainer - count)) and ((1uL shl count) - 1uL)).toInt()
        }
        val deficit = count - bitsInContainer
        return ((bitContainer shl deficit) and ((1uL shl count) - 1uL)).toInt()
    }

    fun consumeBitsStrict(count: Int) {
        if (count == 0) return
        refill(count)
        requireStream(bitsInContainer >= count, "CORRUPT_ZSTD") { "Zstd Huffman bitstream underrun" }
        bitsInContainer -= count
    }

    fun verifyFullyConsumed() {
        requireStream(byteCursor == startOffset && bitsInContainer == 0, "CORRUPT_ZSTD") {
            "Trailing unconsumed bits in Zstd stream (byteOffset=${byteCursor - startOffset}, bits=$bitsInContainer)"
        }
    }

    private fun refill(neededBits: Int) {
        while (bitsInContainer < neededBits && byteCursor > startOffset) {
            byteCursor--
            bitContainer = (bitContainer shl 8) or (data[byteCursor].toInt() and 0xFF).toULong()
            bitsInContainer += 8
        }
    }
}

/**
 * Finite State Entropy decoding table per RFC 8878 Section 4.1.
 */
internal class ZstdFseTable(
    val accuracyLog: Int,
    val symbols: IntArray,
    val nbBits: IntArray,
    val newStates: IntArray,
) {
    companion object {
        fun singleSymbolRle(symbol: Int): ZstdFseTable =
            ZstdFseTable(
                accuracyLog = 0,
                symbols = intArrayOf(symbol),
                nbBits = intArrayOf(0),
                newStates = intArrayOf(0),
            )

        fun fromNormalizedFrequencies(
            norm: IntArray,
            maxSymbol: Int,
            accuracyLog: Int,
        ): ZstdFseTable {
            val tableSize = 1 shl accuracyLog
            val symbols = IntArray(tableSize)
            val symNext = IntArray(maxSymbol + 1)
            var highThreshold = tableSize - 1

            for (sym in 0..maxSymbol) {
                val freq = norm[sym]
                if (freq == -1) {
                    symbols[highThreshold--] = sym
                    symNext[sym] = 1
                } else {
                    symNext[sym] = freq
                }
            }

            spreadSymbols(norm, maxSymbol, tableSize, highThreshold, symbols)

            val nbBits = IntArray(tableSize)
            val newStates = IntArray(tableSize)
            for (state in 0 until tableSize) {
                val sym = symbols[state]
                val nextState = symNext[sym]++
                val highBit = 31 - nextState.countLeadingZeroBits()
                val bits = accuracyLog - highBit
                nbBits[state] = bits
                newStates[state] = (nextState shl bits) - tableSize
            }
            return ZstdFseTable(accuracyLog, symbols, nbBits, newStates)
        }

        private fun spreadSymbols(
            norm: IntArray,
            maxSymbol: Int,
            tableSize: Int,
            highThreshold: Int,
            symbols: IntArray,
        ) {
            val step = (tableSize ushr 1) + (tableSize ushr 3) + 3
            val mask = tableSize - 1
            var pos = 0
            for (sym in 0..maxSymbol) {
                val count = norm[sym]
                if (count <= 0) continue
                repeat(count) {
                    symbols[pos] = sym
                    do {
                        pos = (pos + step) and mask
                    } while (pos > highThreshold)
                }
            }
            requireStream(pos == 0, "CORRUPT_ZSTD") { "Invalid FSE table frequency distribution" }
        }

        fun readTable(
            data: ByteArray,
            startOffset: Int,
            endOffset: Int,
            maxSymbolLimit: Int,
            maxAccuracyLog: Int,
        ): Pair<ZstdFseTable, Int> {
            val reader = ZstdForwardBitReader(data, startOffset, endOffset)
            val accuracyLog = reader.readBits(4) + 5
            requireStream(accuracyLog <= maxAccuracyLog, "CORRUPT_ZSTD") { "FSE accuracyLog $accuracyLog > $maxAccuracyLog" }

            var remaining = (1 shl accuracyLog) + 1
            var threshold = 1 shl accuracyLog
            var nbBits = accuracyLog + 1
            val norm = IntArray(maxSymbolLimit + 1)
            var symbol = 0
            var previous0 = false

            while (remaining > 1 && symbol <= maxSymbolLimit) {
                if (previous0) {
                    symbol = skipRepeatZeroSymbols(reader, symbol, maxSymbolLimit)
                }
                if (remaining > 1 && symbol <= maxSymbolLimit) {
                    val count = decodeNextNormalizedCount(reader, remaining, threshold, nbBits)
                    remaining -= kotlin.math.abs(count)
                    norm[symbol++] = count
                    previous0 = count == 0
                    while (remaining < threshold) {
                        nbBits--
                        threshold = threshold ushr 1
                    }
                }
            }

            requireStream(remaining == 1, "CORRUPT_ZSTD") { "FSE distribution sum mismatch (rem=$remaining)" }
            val table = fromNormalizedFrequencies(norm, maxSymbol = (symbol - 1).coerceAtLeast(0), accuracyLog = accuracyLog)
            return table to reader.bytesConsumed
        }

        private fun skipRepeatZeroSymbols(
            reader: ZstdForwardBitReader,
            startSymbol: Int,
            maxSymbolLimit: Int,
        ): Int {
            var symbol = startSymbol
            while (true) {
                val repeat = reader.readBits(2)
                symbol += repeat
                if (repeat < 3 || symbol > maxSymbolLimit) break
            }
            return symbol
        }

        private fun decodeNextNormalizedCount(
            reader: ZstdForwardBitReader,
            remaining: Int,
            threshold: Int,
            nbBits: Int,
        ): Int {
            val max = (2 * threshold - 1) - remaining
            val low = reader.peekBits(nbBits - 1)
            val rawCount =
                if (low < max) {
                    reader.consumeBits(nbBits - 1)
                    low
                } else {
                    var high = reader.peekBits(nbBits)
                    if (high >= threshold) high -= max
                    reader.consumeBits(nbBits)
                    high
                }
            return rawCount - 1
        }
    }
}

/**
 * Canonical Huffman lookup table for Zstandard compressed literal blocks.
 */
internal class ZstdHuffmanTable(
    val maxBits: Int,
    private val symbols: ByteArray,
    private val bitLengths: IntArray,
) {
    fun decodeSymbol(reader: ZstdBackwardBitReader): Byte {
        val code = reader.peekBitsPadded(maxBits)
        reader.consumeBitsStrict(bitLengths[code])
        return symbols[code]
    }

    companion object {
        fun readTable(
            data: ByteArray,
            startOffset: Int,
            endOffset: Int,
        ): Pair<ZstdHuffmanTable, Int> {
            requireStream(startOffset < endOffset, "CORRUPT_ZSTD") { "Truncated Zstd Huffman header" }
            val headerByte = data[startOffset].toInt() and 0xFF
            val weights = IntArray(MAX_HUFFMAN_SYMBOLS)
            val (numWeights, headerBytesConsumed) =
                if (headerByte >= 128) {
                    val count = headerByte - 127
                    readDirectWeights(data, startOffset + 1, endOffset, count, weights)
                    count to (1 + (count + 1) / 2)
                } else {
                    val compLen = headerByte
                    requireStream(startOffset + 1 + compLen <= endOffset, "CORRUPT_ZSTD") {
                        "Truncated FSE-compressed Huffman weights"
                    }
                    val count = readFseWeights(data, startOffset + 1, startOffset + 1 + compLen, weights)
                    count to (1 + compLen)
                }
            val table = buildFromWeights(weights, numWeights)
            return table to headerBytesConsumed
        }

        private fun readDirectWeights(
            data: ByteArray,
            offset: Int,
            endOffset: Int,
            count: Int,
            weights: IntArray,
        ) {
            val byteCount = (count + 1) / 2
            requireStream(offset + byteCount <= endOffset, "CORRUPT_ZSTD") { "Truncated direct Huffman weights" }
            for (idx in 0 until count) {
                val packed = data[offset + (idx ushr 1)].toInt() and 0xFF
                weights[idx] = if ((idx and 1) == 0) packed ushr 4 else packed and 0x0F
            }
        }

        private fun readFseWeights(
            data: ByteArray,
            startOffset: Int,
            endOffset: Int,
            weights: IntArray,
        ): Int {
            val (fseTable, nCountBytes) =
                ZstdFseTable.readTable(data, startOffset, endOffset, maxSymbolLimit = 255, maxAccuracyLog = 6)
            val bitReader = ZstdBackwardBitReader(data, startOffset + nCountBytes, endOffset)
            val states =
                intArrayOf(
                    bitReader.readBitsStrict(fseTable.accuracyLog),
                    bitReader.readBitsStrict(fseTable.accuracyLog),
                )
            var turn = 0
            var count = 0

            while (count < MAX_HUFFMAN_SYMBOLS - 1) {
                val active = states[turn]
                weights[count++] = fseTable.symbols[active]
                val nb = fseTable.nbBits[active]
                if (bitReader.remainingBits() < nb) {
                    requireStream(count < MAX_HUFFMAN_SYMBOLS, "CORRUPT_ZSTD") { "Huffman weights exceeded 255 symbols" }
                    weights[count++] = fseTable.symbols[states[turn xor 1]]
                    break
                }
                states[turn] = fseTable.newStates[active] + bitReader.readBitsStrict(nb)
                turn = turn xor 1
            }
            bitReader.verifyFullyConsumed()
            return count
        }

        private fun buildFromWeights(
            weights: IntArray,
            numExplicitWeights: Int,
        ): ZstdHuffmanTable {
            requireStream(numExplicitWeights in 1 until MAX_HUFFMAN_SYMBOLS, "CORRUPT_ZSTD") {
                "Invalid Huffman symbol count: $numExplicitWeights"
            }
            var sumWeight = 0
            for (i in 0 until numExplicitWeights) {
                val w = weights[i]
                requireStream(w <= MAX_HUFFMAN_BITS, "CORRUPT_ZSTD") { "Invalid Huffman weight: $w" }
                if (w > 0) sumWeight += 1 shl (w - 1)
            }
            requireStream(sumWeight > 0, "CORRUPT_ZSTD") { "Empty Huffman weight table" }

            val maxBits = 32 - sumWeight.countLeadingZeroBits()
            requireStream(maxBits <= MAX_HUFFMAN_BITS, "CORRUPT_ZSTD") { "Huffman maxBits $maxBits > 11" }
            val leftOver = (1 shl maxBits) - sumWeight
            requireStream(leftOver > 0 && (leftOver and (leftOver - 1)) == 0, "CORRUPT_ZSTD") {
                "Non-prefix Huffman weight sum ($sumWeight)"
            }
            weights[numExplicitWeights] = 32 - leftOver.countLeadingZeroBits()
            val totalSymbols = numExplicitWeights + 1

            return populateDecodingTable(weights, totalSymbols, maxBits)
        }

        private fun populateDecodingTable(
            weights: IntArray,
            totalSymbols: Int,
            maxBits: Int,
        ): ZstdHuffmanTable {
            val rankCount = IntArray(MAX_HUFFMAN_BITS + 2)
            for (sym in 0 until totalSymbols) {
                val w = weights[sym]
                if (w > 0) rankCount[w]++
            }
            val rankIndex = IntArray(MAX_HUFFMAN_BITS + 2)
            var currentIndex = 0
            for (w in 1..maxBits) {
                val span = rankCount[w] shl (w - 1)
                rankIndex[w] = currentIndex
                currentIndex += span
            }
            val tableSize = 1 shl maxBits
            requireStream(currentIndex == tableSize, "CORRUPT_ZSTD") { "Incomplete Huffman table ($currentIndex != $tableSize)" }

            val symbols = ByteArray(tableSize)
            val lengths = IntArray(tableSize)
            for (sym in 0 until totalSymbols) {
                val w = weights[sym]
                if (w == 0) continue
                val codeLen = maxBits + 1 - w
                val entryCount = 1 shl (w - 1)
                val start = rankIndex[w]
                rankIndex[w] += entryCount
                for (slot in start until (start + entryCount)) {
                    symbols[slot] = sym.toByte()
                    lengths[slot] = codeLen
                }
            }
            return ZstdHuffmanTable(maxBits = maxBits, symbols = symbols, bitLengths = lengths)
        }
    }
}

/**
 * Standard RFC 8878 predefined FSE and baseline tables for sequence decoding.
 */
internal object ZstdPredefinedTables {
    val literalLengthFse: ZstdFseTable =
        ZstdFseTable.fromNormalizedFrequencies(
            norm =
                parseCsvInts(
                    "4,3,2,2,2,2,2,2,2,2,2,2,2,1,1,1," +
                        "2,2,2,2,2,2,2,2,2,3,2,1,1,1,1,1,-1,-1,-1,-1",
                ),
            maxSymbol = 35,
            accuracyLog = 6,
        )

    val matchLengthFse: ZstdFseTable =
        ZstdFseTable.fromNormalizedFrequencies(
            norm =
                parseCsvInts(
                    "1,4,3,2,2,2,2,2,2,1,1,1,1,1,1,1," +
                        "1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1," +
                        "1,1,1,1,1,1,1,1,1,1,1,1,1,1,-1,-1,-1,-1,-1,-1,-1",
                ),
            maxSymbol = 52,
            accuracyLog = 6,
        )

    val offsetFse: ZstdFseTable =
        ZstdFseTable.fromNormalizedFrequencies(
            norm = parseCsvInts("1,1,1,1,1,1,2,2,2,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,-1,-1,-1,-1,-1"),
            maxSymbol = 28,
            accuracyLog = 5,
        )

    val literalLengthBase: IntArray =
        parseCsvInts(
            "0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,18,20,22,24,28,32,40," +
                "48,64,128,256,512,1024,2048,4096,8192,16384,32768,65536",
        )

    val literalLengthExtraBits: IntArray =
        parseCsvInts("0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,1,1,1,2,2,3,3,4,6,7,8,9,10,11,12,13,14,15,16")

    val matchLengthBase: IntArray =
        parseCsvInts(
            "3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26," +
                "27,28,29,30,31,32,33,34,35,37,39,41,43,47,51,59,67,83,99,131," +
                "259,515,1027,2051,4099,8195,16387,32771,65539",
        )

    val matchLengthExtraBits: IntArray =
        parseCsvInts(
            "0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0," +
                "1,1,1,1,2,2,3,3,4,4,5,7,8,9,10,11,12,13,14,15,16",
        )
}

private fun parseCsvInts(csv: String): IntArray =
    csv
        .split(',')
        .map { token -> token.toInt() }
        .toIntArray()
