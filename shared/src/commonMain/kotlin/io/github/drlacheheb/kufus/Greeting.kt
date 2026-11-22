package io.github.drlacheheb.kufus

class Greeting {
    private val platform = getPlatform()

    fun greet(): String = sayHello(platform.name)
}
