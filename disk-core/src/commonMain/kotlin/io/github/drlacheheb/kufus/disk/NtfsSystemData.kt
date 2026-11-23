package io.github.drlacheheb.kufus.disk

/**
 * Static NTFS metadata templates (`$AttrDef`, security descriptors for `$SDS`/`$SDH`/`$SII` and
 * legacy system records, and `$Extend` / `$Quota` / `$ObjId` / `$Reparse` index payloads) shared by
 * [NtfsFormatter].
 */
internal object NtfsSystemData {
    const val ATTRDEF_BYTE_SIZE = 2560
    const val SECURE_SDS_MIRROR_STRIDE = 0x40000 // 262,144
    const val SECURE_SDS_REAL_SIZE = 0x400FC // 262,396
    const val ROOT_SD_REAL_SIZE = 4140

    private const val HEX_CHARS = "0123456789abcdef"

    /**
     * Builds the 16-entry (2,560-byte) standard NTFS 3.1 `$AttrDef` stream.
     */
    fun buildAttrDefBytes(): ByteArray {
        val out = ByteArray(ATTRDEF_BYTE_SIZE)
        writeAttrDefEntry(out, 0, "\$STANDARD_INFORMATION", 0x10, 0x40, 48L, 72L)
        writeAttrDefEntry(out, 1, "\$ATTRIBUTE_LIST", 0x20, 0x80, 0L, -1L)
        writeAttrDefEntry(out, 2, "\$FILE_NAME", 0x30, 0x42, 68L, 578L)
        writeAttrDefEntry(out, 3, "\$OBJECT_ID", 0x40, 0x40, 0L, 256L)
        writeAttrDefEntry(out, 4, "\$SECURITY_DESCRIPTOR", 0x50, 0x80, 0L, -1L)
        writeAttrDefEntry(out, 5, "\$VOLUME_NAME", 0x60, 0x40, 2L, 256L)
        writeAttrDefEntry(out, 6, "\$VOLUME_INFORMATION", 0x70, 0x40, 12L, 12L)
        writeAttrDefEntry(out, 7, "\$DATA", 0x80, 0x00, 0L, -1L)
        writeAttrDefEntry(out, 8, "\$INDEX_ROOT", 0x90, 0x40, 0L, -1L)
        writeAttrDefEntry(out, 9, "\$INDEX_ALLOCATION", 0xA0, 0x80, 0L, -1L)
        writeAttrDefEntry(out, 10, "\$BITMAP", 0xB0, 0x80, 0L, -1L)
        writeAttrDefEntry(out, 11, "\$REPARSE_POINT", 0xC0, 0x80, 0L, 16384L)
        writeAttrDefEntry(out, 12, "\$EA_INFORMATION", 0xD0, 0x40, 8L, 8L)
        writeAttrDefEntry(out, 13, "\$EA", 0xE0, 0x00, 0L, 65536L)
        writeAttrDefEntry(out, 14, "\$LOGGED_UTILITY_STREAM", 0x100, 0x80, 0L, 65536L)
        return out
    }

    /**
     * Security descriptor for `$Volume` and reserved slots 12..15 (100 bytes).
     */
    fun buildVolumeSecurityDescriptor(): ByteArray =
        decodeHex(
            "01000480480000005400000000000000140000000200340002000000000014009f011200010100000000000512000000" +
                "000018009f0112000102000000000005200000002002000001010000000000051200000001020000000000052000000020020000",
        )

    /**
     * Security descriptor for `$AttrDef` and `$Boot` (100 bytes).
     */
    fun buildSystemReadOnlySecurityDescriptor(): ByteArray =
        decodeHex(
            "010004804800000054000000000000001400000002003400020000000000140089001200010100000000000512000000" +
                "00001800890012000102000000000005200000002002000001010000000000051200000001020000000000052000000020020000",
        )

    /**
     * Builds the non-resident 4,140-byte root directory (`.`) `$SECURITY_DESCRIPTOR`.
     */
    fun buildRootSecurityDescriptor(): ByteArray {
        val out = ByteArray(ROOT_SD_REAL_SIZE)
        val head =
            decodeHex(
                "0100048014100000201000000000000014000000020000100800000000001800ff011f0001020000000000052000000020020000" +
                    "000b1800000000100102000000000005200000002002000000001400ff011f00010100000000000512000000" +
                    "000b14000000001001010000000000051200000000001400bf01130001010000000000050b000000" +
                    "000b1400000001e001010000000000050b00000000001800a900120001020000000000052000000021020000" +
                    "000b1800000000a001020000000000052000000021020000",
            )
        val ownerGroup = decodeHex("010100000000000512000000010100000000000512000000")
        head.copyInto(out, 0)
        ownerGroup.copyInto(out, 0x1014)
        return out
    }

    /**
     * Populates a sector-aligned or cluster-padded `$Secure.$SDS` buffer (`262,396` real bytes) containing
     * security IDs `0x100` (system read-only) and `0x101` (volume administrators) and their 256 KiB mirror.
     */
    fun buildSecureSdsBytes(): ByteArray {
        val out = ByteArray(SECURE_SDS_REAL_SIZE)
        val entry0Header = decodeHex("f01203f80001000000000000000000007c000000")
        val entry1Header = decodeHex("5124b3000101000080000000000000007c000000")
        val sd0 = buildSystemReadOnlySecurityDescriptor()
        val sd1 = buildVolumeSecurityDescriptor()

        entry0Header.copyInto(out, 0)
        sd0.copyInto(out, 20)
        entry1Header.copyInto(out, 128)
        sd1.copyInto(out, 148)
        out.copyInto(out, destinationOffset = SECURE_SDS_MIRROR_STRIDE, startIndex = 0, endIndex = 252)
        return out
    }

    fun buildSecureSdhIndexRoot(): ByteArray =
        decodeHex(
            "0000000012000000001000000100000010000000800000008000000000000000" +
                "180014000000000030000800000000005124b300010100005124b3000101000080000000000000007c00000049004900" +
                "18001400000000003000080000000000f01203f800010000f01203f80001000000000000000000007c00000049004900" +
                "00000000000000001000000002000000",
        )

    fun buildSecureSiiIndexRoot(): ByteArray =
        decodeHex(
            "0000000010000000001000000100000010000000700000007000000000000000" +
                "1400140000000000280004000000000000010000f01203f80001000000000000000000007c000000" +
                "14001400000000002800040000000000010100005124b3000101000080000000000000007c000000" +
                "00000000000000001000000002000000",
        )

    fun buildUpcaseInfoBytes(): ByteArray = decodeHex("20000000000000000c691b6b777edcda00000000000000000000000000000000")

    fun buildQuotaOIndexRoot(): ByteArray =
        decodeHex(
            "0000000011000000001000000100000010000000480000004800000000000000" +
                "20000400000000002800100000000000010200000000000520000000200200000001000020000000" +
                "00000000000000001000000002000000",
        )

    fun buildQuotaQIndexRoot(timestamp: Long): ByteArray {
        val out =
            decodeHex(
                "0000000010000000001000000100000010000000c0000000c000000000000000" +
                    "1400300000000000480004000000000001000000020000000100000000000000000000000000000000000000" +
                    "ffffffffffffffffffffffffffffffff000000000000000000000000" +
                    "1400400000000000580004000000000000010000020000000100000000000000000000000000000000000000" +
                    "ffffffffffffffffffffffffffffffff000000000000000001020000000000052000000020020000" +
                    "0000000000000000000000001000000002000000",
            )
        writeLeLong(out, 0x3C, timestamp)
        writeLeLong(out, 0x84, timestamp)
        return out
    }

    fun buildEmptyViewIndexRoot(collationRule: Int): ByteArray {
        val out = ByteArray(48)
        writeLeInt(out, 0, 0)
        writeLeInt(out, 4, collationRule)
        writeLeInt(out, 8, 4096)
        out[12] = 1
        writeLeInt(out, 16, 16)
        writeLeInt(out, 20, 32)
        writeLeInt(out, 24, 32)
        writeLeShort(out, 40, 16)
        out[44] = 2 // INDEX_ENTRY_END
        return out
    }

    private fun writeAttrDefEntry(
        dest: ByteArray,
        index: Int,
        name: String,
        type: Int,
        flags: Int,
        minSize: Long,
        maxSize: Long,
    ) {
        val base = index * 160
        FormatBlockIo.writeUtf16Le(dest, base, name, maxChars = 64)
        writeLeInt(dest, base + 128, type)
        writeLeInt(dest, base + 132, 0)
        writeLeInt(dest, base + 136, 0)
        writeLeInt(dest, base + 140, flags)
        writeLeLong(dest, base + 144, minSize)
        writeLeLong(dest, base + 152, maxSize)
    }

    private fun decodeHex(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            val hi = HEX_CHARS.indexOf(hex[i * 2].lowercaseChar())
            val lo = HEX_CHARS.indexOf(hex[i * 2 + 1].lowercaseChar())
            ((hi shl 4) or lo).toByte()
        }
}
