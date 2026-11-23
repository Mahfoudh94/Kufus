package io.github.drlacheheb.kufus.domain.repository

interface HostUserEnvironment {
    val defaultUsername: String?
        get() = null

    val defaultLocaleTag: String?
        get() = null

    data object Empty : HostUserEnvironment
}
