package io.github.drlacheheb.kufus.disk

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.EINTR
import platform.posix.ENOENT
import platform.posix.O_RDONLY
import platform.posix.STDERR_FILENO
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix._exit
import platform.posix.close
import platform.posix.dup2
import platform.posix.errno
import platform.posix.execvp
import platform.posix.fork
import platform.posix.open
import platform.posix.pipe
import platform.posix.read
import platform.posix.waitpid

private const val MACOS_OUTPUT_BUFFER_BYTES = 4096

actual fun currentNativeHostOs(): HostOsKind = HostOsKind.MACOS

actual fun finalizePlatformFormattedDisk(
    devicePath: String,
    request: FormatRequest,
) {
    val bsdName = devicePath.removePrefix("/dev/").removePrefix("r")
    runCatching {
        runMacosCommandNoShell(listOf("/usr/sbin/diskutil", "mountDisk", "/dev/$bsdName"))
    }
}

internal data class MacosCommandOutput(
    val exitCode: Int,
    val output: String,
)

@OptIn(ExperimentalForeignApi::class)
internal fun runMacosCommandNoShell(command: List<String>): MacosCommandOutput {
    memScoped {
        val pipeFds = allocArray<IntVar>(2)
        if (pipe(pipeFds) != 0) {
            throw FlashStepException("FORMAT_FAILED", "Unable to create pipe for '${command.first()}'")
        }
        val readFd = pipeFds[0]
        val writeFd = pipeFds[1]
        val pid = fork()
        if (pid < 0) {
            close(readFd)
            close(writeFd)
            throw FlashStepException("FORMAT_FAILED", "fork() failed starting '${command.first()}'")
        }
        if (pid == 0) {
            close(readFd)
            val devNull = open("/dev/null", O_RDONLY)
            if (devNull >= 0) {
                dup2(devNull, STDIN_FILENO)
                close(devNull)
            }
            dup2(writeFd, STDOUT_FILENO)
            dup2(writeFd, STDERR_FILENO)
            close(writeFd)
            val argv = allocArray<CPointerVar<ByteVar>>(command.size + 1)
            for (index in command.indices) {
                argv[index] = command[index].cstr.ptr
            }
            argv[command.size] = null
            execvp(command.first(), argv)
            _exit(if (errno == ENOENT) 127 else 126)
        }
        close(writeFd)
        val output = readMacosPipeText(readFd)
        close(readFd)
        val statusVar = alloc<IntVar>()
        while (waitpid(pid, statusVar.ptr, 0) < 0) {
            if (errno != EINTR) return MacosCommandOutput(-1, output)
        }
        val status = statusVar.value
        val code = if ((status and 0x7F) == 0) (status ushr 8) and 0xFF else 128 + (status and 0x7F)
        return MacosCommandOutput(code, output)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readMacosPipeText(fd: Int): String {
    val buf = ByteArray(MACOS_OUTPUT_BUFFER_BYTES)
    val sb = StringBuilder()
    var done = false
    buf.usePinned { pinned ->
        while (!done) {
            val n = read(fd, pinned.addressOf(0), buf.size.toULong())
            when {
                n > 0L -> sb.append(buf.decodeToString(0, n.toInt()))
                n == 0L || errno != EINTR -> done = true
            }
        }
    }
    return sb.toString()
}
