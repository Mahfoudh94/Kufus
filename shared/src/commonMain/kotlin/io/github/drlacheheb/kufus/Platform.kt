package io.github.drlacheheb.kufus

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform