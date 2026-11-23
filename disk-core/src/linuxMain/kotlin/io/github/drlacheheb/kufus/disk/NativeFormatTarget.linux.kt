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
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.EINTR
import platform.posix.ENOENT
import platform.posix.O_RDONLY
import platform.posix.STDERR_FILENO
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.S_IFBLK
import platform.posix.S_IFMT
import platform.posix.X_OK
import platform.posix._exit
import platform.posix.access
import platform.posix.close
import platform.posix.dup2
import platform.posix.errno
import platform.posix.execvp
import platform.posix.fork
import platform.posix.getenv
import platform.posix.open
import platform.posix.pipe
import platform.posix.read
import platform.posix.stat
import platform.posix.usleep
import platform.posix.waitpid

private const val LINUX_PART_POLL_ATTEMPTS = 50
private const val LINUX_PART_POLL_MICROS = 100_000u
private const val COMMAND_OUTPUT_BUFFER_BYTES = 4096
private const val MKFS_NOT_FOUND_EXIT = 127

actual fun currentNativeHostOs(): HostOsKind = HostOsKind.LINUX

actual fun formatPlatformPartitionFilesystem(
    devicePath: String,
    partitionIndex: Int,
    request: FormatRequest,
    onProgress: (Long, Long) -> Unit,
): Result<Unit> =
    runCatching {
        val canonicalDisk = resolveRealPath(devicePath) ?: devicePath
        val partNode = waitForLinuxPartitionNode(canonicalDisk, partitionIndex)
        val argv =
            buildLinuxMkfsCommand(
                filesystem = request.filesystem,
                partitionPath = partNode,
                volumeLabel = request.volumeLabel,
                clusterSizeBytes = request.clusterSizeBytes,
            )
        val tool = argv.first()
        if (!isPosixExecutableAvailable(tool)) {
            throw FlashStepException(
                code = "TOOL_NOT_FOUND",
                message = "Required formatter '$tool' was not found (${packageHintForLinuxTool(tool)}).",
            )
        }
        onProgress(20L, 100L)
        val result = runPosixCommandNoShell(argv)
        if (result.exitCode != 0) {
            val detail = result.output.trim().ifEmpty { "exit code ${result.exitCode}" }
            throw FlashStepException(
                code = "FORMAT_FAILED",
                message = "Formatting '$partNode' with '$tool' failed: $detail",
            )
        }
        onProgress(100L, 100L)
    }

actual fun finalizePlatformFormattedDisk(
    devicePath: String,
    request: FormatRequest,
) = Unit

@OptIn(ExperimentalForeignApi::class)
private fun waitForLinuxPartitionNode(
    canonicalDisk: String,
    partitionIndex: Int,
): String {
    if (isPosixExecutableAvailable("udevadm")) {
        runPosixCommandNoShell(listOf("udevadm", "settle", "--timeout=5"))
    }
    val expected = resolveLinuxPartitionNode(canonicalDisk, partitionIndex)
    repeat(LINUX_PART_POLL_ATTEMPTS) {
        if (isLinuxBlockDeviceNode(expected)) {
            return expected
        }
        usleep(LINUX_PART_POLL_MICROS)
    }
    throw FlashStepException(
        code = "PARTITION_NOT_FOUND",
        message = "Kernel did not expose partition node '$expected' after partitioning '$canonicalDisk'.",
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun isLinuxBlockDeviceNode(path: String): Boolean =
    memScoped {
        val st = alloc<stat>()
        stat(path, st.ptr) == 0 && (st.st_mode.toInt() and S_IFMT) == S_IFBLK
    }

private fun packageHintForLinuxTool(tool: String): String =
    when (tool) {
        "mkfs.exfat" -> "install exfatprogs"
        "mkfs.ntfs" -> "install ntfs-3g / ntfsprogs"
        "mkfs.ext4" -> "install e2fsprogs"
        else -> "ensure '$tool' is installed"
    }

private data class PosixCommandOutput(
    val exitCode: Int,
    val output: String,
)

@OptIn(ExperimentalForeignApi::class)
private fun isPosixExecutableAvailable(executable: String): Boolean {
    if (executable.contains('/')) {
        return access(executable, X_OK) == 0
    }
    val pathEnv = getenv("PATH")?.toKString().orEmpty()
    val candidates =
        pathEnv.split(':').filter { it.isNotBlank() } +
            listOf("/usr/sbin", "/sbin", "/usr/bin", "/bin")
    return candidates.any { dir -> access("$dir/$executable", X_OK) == 0 }
}

@OptIn(ExperimentalForeignApi::class)
private fun runPosixCommandNoShell(command: List<String>): PosixCommandOutput {
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
            redirectChildStdio(writeFd)
            close(writeFd)
            val argv = allocArray<CPointerVar<ByteVar>>(command.size + 1)
            for (index in command.indices) {
                argv[index] = command[index].cstr.ptr
            }
            argv[command.size] = null
            execvp(command.first(), argv)
            _exit(if (errno == ENOENT) MKFS_NOT_FOUND_EXIT else 126)
        }
        close(writeFd)
        val captured = readAllFdText(readFd)
        close(readFd)
        val exitCode = waitForChildExit(pid)
        return PosixCommandOutput(exitCode = exitCode, output = captured)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun redirectChildStdio(outputFd: Int) {
    val devNull = open("/dev/null", O_RDONLY)
    if (devNull >= 0) {
        dup2(devNull, STDIN_FILENO)
        close(devNull)
    }
    dup2(outputFd, STDOUT_FILENO)
    dup2(outputFd, STDERR_FILENO)
}

@OptIn(ExperimentalForeignApi::class)
private fun readAllFdText(fd: Int): String {
    val chunk = ByteArray(COMMAND_OUTPUT_BUFFER_BYTES)
    val builder = StringBuilder()
    var done = false
    chunk.usePinned { pinned ->
        while (!done) {
            val n = read(fd, pinned.addressOf(0), chunk.size.toULong())
            when {
                n > 0L -> builder.append(chunk.decodeToString(startIndex = 0, endIndex = n.toInt()))
                n == 0L || errno != EINTR -> done = true
            }
        }
    }
    return builder.toString()
}

@OptIn(ExperimentalForeignApi::class)
private fun waitForChildExit(pid: Int): Int =
    memScoped {
        val statusVar = alloc<IntVar>()
        while (waitpid(pid, statusVar.ptr, 0) < 0) {
            if (errno != EINTR) return -1
        }
        val status = statusVar.value
        if ((status and 0x7F) == 0) {
            (status ushr 8) and 0xFF
        } else {
            128 + (status and 0x7F)
        }
    }
