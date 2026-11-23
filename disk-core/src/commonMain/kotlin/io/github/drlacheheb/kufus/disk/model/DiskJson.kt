package io.github.drlacheheb.kufus.disk.model

import kotlinx.serialization.json.Json

object DiskJson {
    val codec: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun encodeEvent(event: WorkerEvent): String = codec.encodeToString(WorkerEvent.serializer(), event)

    fun decodeEvent(line: String): WorkerEvent = codec.decodeFromString(WorkerEvent.serializer(), line)
}
