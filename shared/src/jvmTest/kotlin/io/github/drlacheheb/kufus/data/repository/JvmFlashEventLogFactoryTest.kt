package io.github.drlacheheb.kufus.data.repository

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test

class JvmFlashEventLogFactoryTest {
    @Test
    fun drainsOnlyCompleteLinesLeavesPartialWritesAndCleansUpTempFile() =
        runTest {
            val session = JvmFlashEventLogFactory().createSession()
            val file = File(session.path)
            try {
                file.exists() shouldBe true

                file.appendText("{\"type\":\"started\",")
                val initial = mutableListOf<String>()
                session.drainLines { initial += it }
                initial shouldContainExactly emptyList()

                file.appendText("\"totalBytes\":1024}\n{\"type\":\"completed\"")
                val firstBatch = mutableListOf<String>()
                session.drainLines { firstBatch += it }
                firstBatch shouldContainExactly listOf("{\"type\":\"started\",\"totalBytes\":1024}")

                file.appendText(",\"verified\":true,\"elapsedMs\":15}\n")
                val secondBatch = mutableListOf<String>()
                session.drainLines { secondBatch += it }
                secondBatch shouldContainExactly listOf("{\"type\":\"completed\",\"verified\":true,\"elapsedMs\":15}")
            } finally {
                session.closeAndDelete()
                file.exists() shouldBe false
            }
        }
}
