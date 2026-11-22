package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.disk.CompressionFormat
import io.github.drlacheheb.kufus.disk.DiskJson
import io.github.drlacheheb.kufus.disk.ImageFormat
import io.github.drlacheheb.kufus.disk.ImageInfo
import io.github.drlacheheb.kufus.disk.WorkerEvent
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class WorkerEventDecoderTest {
    @Test
    fun decodesImageInspectedEventAndWorkerErrorsCleanly() {
        val info =
            ImageInfo(
                path = "/tmp/debian.iso.zst",
                sizeBytes = 900_000_000L,
                format = ImageFormat.COMPRESSED_IMAGE,
                compression = CompressionFormat.ZSTD,
                uncompressedSizeHintBytes = 2_400_000_000L,
            )
        val successLine = DiskJson.encodeEvent(WorkerEvent.ImageInspected(info))
        val errorLine = DiskJson.encodeEvent(WorkerEvent.Error(code = "INSPECT_FAILED", message = "Permission denied"))
        val wrongEventLine = DiskJson.encodeEvent(WorkerEvent.DrivesListed(emptyList()))

        decodeWorkerImageLine(successLine).getOrThrow() shouldBe info
        decodeWorkerImageLine(errorLine).exceptionOrNull()?.message shouldBe "INSPECT_FAILED: Permission denied"
        decodeWorkerImageLine(wrongEventLine).isFailure shouldBe true
    }
}
