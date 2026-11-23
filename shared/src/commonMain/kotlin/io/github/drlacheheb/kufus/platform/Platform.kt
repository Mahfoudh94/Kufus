package io.github.drlacheheb.kufus.platform

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
