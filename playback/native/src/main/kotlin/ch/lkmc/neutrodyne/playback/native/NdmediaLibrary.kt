// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.native

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Path

/**
 * The `ndmedia` shared library of :playback:native, loaded once per output
 * (11 ndmedia interface). Downcalls are hand-written [MethodHandle]s; ndmedia
 * is our own C ABI, so no layout lock file is needed — a load failure means
 * the library was not built for this target.
 */
class NdmediaLibrary private constructor(
    private val arena: Arena,
    private val lookup: SymbolLookup,
) : AutoCloseable {
    private val linker = Linker.nativeLinker()
    private val resolved = mutableMapOf<String, MethodHandle>()

    internal fun downcall(
        name: String,
        descriptor: FunctionDescriptor,
    ): MethodHandle =
        resolved.getOrPut(name) {
            linker.downcallHandle(
                lookup.find(name).orElseThrow { IllegalStateException("$name is missing from libndmedia") },
                descriptor,
            )
        }

    fun cString(value: String): MemorySegment = arena.allocateFrom(value)

    fun allocate(byteSize: Long): MemorySegment = arena.allocate(byteSize)

    fun readCString(pointer: MemorySegment): String {
        if (pointer.address() == 0L) return ""
        return pointer.reinterpret(MAX_C_STRING).getString(0)
    }

    override fun close() = arena.close()

    companion object {
        private const val MAX_C_STRING = 4096L
        // OS services may finish native delegates after revocation. Keep code
        // mapped for the process lifetime; close still releases every device,
        // registration and per-instance allocation before returning.
        private val loaded = java.util.concurrent.ConcurrentHashMap<Path, SymbolLookup>()

        /** Library file name per host family (11 ndmedia interface). */
        fun fileName(os: String = System.getProperty("os.name").lowercase()): String =
            when {
                os.startsWith("windows") -> "ndmedia.dll"
                os.startsWith("mac") -> "libndmedia.dylib"
                else -> "libndmedia.so"
            }

        /** Loads `ndmedia` from [directory]; throws when the file is absent. */
        fun load(directory: Path): NdmediaLibrary {
            val file = directory.resolve(fileName())
            require(file.toFile().isFile) { "ndmedia is not built for this target: $file does not exist" }
            val arena = Arena.ofShared()
            val lookup = loaded.computeIfAbsent(file.toAbsolutePath().normalize()) {
                SymbolLookup.libraryLookup(it, Arena.global())
            }
            return NdmediaLibrary(arena, lookup)
        }
    }
}

/** Layout descriptors of the flat ndmedia ABI (ndmedia.h). */
internal object NdmediaAbi {
    val INT = ValueLayout.JAVA_INT
    val LONG = ValueLayout.JAVA_LONG
    val ADDRESS = ValueLayout.ADDRESS
    val OPEN: FunctionDescriptor =
        FunctionDescriptor.of(INT, ADDRESS, ADDRESS)
    val WRITE: FunctionDescriptor =
        FunctionDescriptor.of(INT, ADDRESS, ADDRESS, INT)
    val FRAMES_INT: FunctionDescriptor =
        FunctionDescriptor.of(INT, ADDRESS)
    val FRAMES_LONG: FunctionDescriptor =
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ADDRESS)
    val CONTROL: FunctionDescriptor =
        FunctionDescriptor.of(INT, ADDRESS)
    val CLOSE: FunctionDescriptor =
        FunctionDescriptor.ofVoid(ADDRESS)
    val DESCRIBE: FunctionDescriptor =
        FunctionDescriptor.of(INT, ADDRESS, ADDRESS, INT)

    /** nd_out_config { const char* backend; int sample_rate; int channels; int ring_ms; } */
    const val CONFIG_SIZE = 24L
    const val CONFIG_BACKEND_OFFSET = 0L
    const val CONFIG_SAMPLE_RATE_OFFSET = 8L
    const val CONFIG_CHANNELS_OFFSET = 12L
    const val CONFIG_RING_MS_OFFSET = 16L
}
