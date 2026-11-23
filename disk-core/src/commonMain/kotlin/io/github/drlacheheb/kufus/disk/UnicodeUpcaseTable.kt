package io.github.drlacheheb.kufus.disk

private const val BMP_CODE_POINT_COUNT = 65_536
private const val EXFAT_SKIP_MARKER = 0xFFFF

/**
 * Shared Unicode Basic Multilingual Plane (U+0000..U+FFFF) uppercase mapping tables used by
 * [ExfatFormatter] (compressed up-case table) and [NtfsFormatter] (`$UpCase` system file).
 */
internal object UnicodeUpcaseTable {
    /**
     * Step/delta runs encoding almost all exFAT BMP uppercase mappings concisely.
     * Tuple layout: startCodePoint, step, count, delta.
     */
    private val exfatRuns =
        parseIntTable(
            """
            97 1 26 -32 224 1 23 -32 248 1 7 -32 257 2 24 -1
            307 2 3 -1 314 2 8 -1 331 2 23 -1 378 2 3 -1
            387 2 2 -1 417 2 3 -1 436 2 2 -1 462 2 8 -1
            479 2 9 -1 505 2 20 -1 547 2 9 -1 583 2 5 -1
            598 1 2 -205 650 1 2 -217 891 1 3 130 941 1 3 -37
            945 1 17 -32 963 1 9 -32 973 1 2 -63 985 2 12 -1
            1072 1 32 -32 1104 1 16 -80 1121 2 17 -1 1163 2 27 -1
            1218 2 7 -1 1233 2 34 -1 1377 1 38 -48 7681 2 75 -1
            7841 2 45 -1 7936 1 8 8 7952 1 6 8 7968 1 8 8
            7984 1 8 8 8000 1 6 8 8017 2 4 8 8032 1 8 8
            8048 1 2 74 8050 1 4 86 8054 1 2 100 8056 1 2 128
            8058 1 2 112 8060 1 2 126 8064 1 8 8 8080 1 8 8
            8096 1 8 8 8112 1 2 8 8144 1 2 8 8160 1 2 8
            8560 1 16 -16 9424 1 26 -26 11312 1 47 -48 11368 2 3 -1
            11393 2 50 -1 11520 1 38 -7264 65345 1 26 -32
            """,
        )

    /**
     * Individual exFAT BMP code-point mappings outside [exfatRuns].
     * Pair layout: codePoint, upperCodePoint.
     */
    private val exfatSingles =
        parseIntTable(
            """
            255 376 384 579 392 391 396 395 402 401 405 502 409 408 410 573
            414 544 424 423 429 428 432 431 441 440 445 444 447 503 454 452
            457 455 460 458 477 398 499 497 501 500 570 11365 572 571 574 11366
            578 577 595 385 596 390 601 399 603 400 608 403 611 404 616 407
            617 406 619 11362 623 412 626 413 629 415 637 11364 640 422 643 425
            648 430 649 580 652 581 658 439 940 902 962 931 972 908 1010 1017
            1016 1015 1019 1018 1231 1216 7549 11363 8115 8124 8140 8131 8165 8172 8188 8179
            8526 8498 8580 8579 11361 11360 11382 11381
            """,
        )

    /**
     * NTFS `$UpCase` differences relative to the exFAT BMP mapping (Unicode 6+ additions
     * and Windows NTFS compatibility mappings). Pair layout: codePoint, ntfsUpperCodePoint.
     */
    private val ntfsOverrides =
        parseIntTable(
            """
            570 570 574 574 592 11375 593 11373 625 11374 881 880 883 882 887 886
            962 962 983 975 1301 1300 1303 1302 1305 1304 1307 1306 1309 1308 1311 1310
            1313 1312 1315 1314 7545 42877 7931 7930 7933 7932 7935 7934 8131 8140 8140 8140
            8179 8188 8188 8188 11365 570 11366 574 11379 11378 42561 42560 42563 42562 42565 42564
            42567 42566 42569 42568 42571 42570 42573 42572 42575 42574 42577 42576 42579 42578 42581 42580
            42583 42582 42585 42584 42587 42586 42589 42588 42591 42590 42595 42594 42597 42596 42599 42598
            42601 42600 42603 42602 42605 42604 42625 42624 42627 42626 42629 42628 42631 42630 42633 42632
            42635 42634 42637 42636 42639 42638 42641 42640 42643 42642 42645 42644 42647 42646 42787 42786
            42789 42788 42791 42790 42793 42792 42795 42794 42797 42796 42799 42798 42803 42802 42805 42804
            42807 42806 42809 42808 42811 42810 42813 42812 42815 42814 42817 42816 42819 42818 42821 42820
            42823 42822 42825 42824 42827 42826 42829 42828 42831 42830 42833 42832 42835 42834 42837 42836
            42839 42838 42841 42840 42843 42842 42845 42844 42847 42846 42849 42848 42851 42850 42853 42852
            42855 42854 42857 42856 42859 42858 42861 42860 42863 42862 42874 42873 42876 42875 42879 42878
            42881 42880 42883 42882 42885 42884 42887 42886 42892 42891
            """,
        )

    fun buildExfatLookup(): IntArray {
        val table = IntArray(BMP_CODE_POINT_COUNT) { it }
        var i = 0
        while (i < exfatRuns.size) {
            val start = exfatRuns[i]
            val step = exfatRuns[i + 1]
            val count = exfatRuns[i + 2]
            val delta = exfatRuns[i + 3]
            for (k in 0 until count) {
                val cp = start + k * step
                table[cp] = cp + delta
            }
            i += 4
        }
        applyPairs(table, exfatSingles)
        return table
    }

    /**
     * Produces a valid exFAT compressed Up-case table using `0xFFFF` skip markers for identity runs.
     */
    fun buildExfatCompressedTable(): ByteArray {
        val table = buildExfatLookup()
        val lastNonIdentity = table.indices.lastOrNull { table[it] != it } ?: 0
        val out = ArrayList<Byte>(4096)
        var i = 0
        while (i <= lastNonIdentity) {
            if (table[i] == i) {
                var j = i
                while (j <= lastNonIdentity && table[j] == j && (j - i) < EXFAT_SKIP_MARKER) {
                    j++
                }
                val skipCount = j - i
                if (skipCount >= 2) {
                    appendLeShort(out, EXFAT_SKIP_MARKER)
                    appendLeShort(out, skipCount)
                    i = j
                    continue
                }
            }
            appendLeShort(out, table[i])
            i++
        }
        return ByteArray(out.size) { idx -> out[idx] }
    }

    /**
     * Produces the full 131,072-byte UTF-16LE `$UpCase` table for NTFS volumes.
     */
    fun buildNtfsUpcaseBytes(): ByteArray {
        val table = buildExfatLookup()
        applyPairs(table, ntfsOverrides)
        val out = ByteArray(BMP_CODE_POINT_COUNT * 2)
        for (cp in 0 until BMP_CODE_POINT_COUNT) {
            writeLeShort(out, cp * 2, table[cp])
        }
        return out
    }

    private fun applyPairs(
        table: IntArray,
        pairs: IntArray,
    ) {
        var i = 0
        while (i < pairs.size) {
            table[pairs[i]] = pairs[i + 1]
            i += 2
        }
    }

    private fun appendLeShort(
        dest: MutableList<Byte>,
        value: Int,
    ) {
        dest.add((value and 0xFF).toByte())
        dest.add(((value ushr 8) and 0xFF).toByte())
    }
}

private fun parseIntTable(text: String): IntArray =
    text
        .trim()
        .split(Regex("\\s+"))
        .map { it.toInt() }
        .toIntArray()
