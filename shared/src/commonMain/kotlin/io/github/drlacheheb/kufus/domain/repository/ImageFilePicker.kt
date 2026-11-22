package io.github.drlacheheb.kufus.domain.repository

fun interface ImageFilePicker {
    suspend fun pickImagePath(): String?
}
