package com.terminator.emulator

/**
 * JNI bridge to pty.c. Spawns [cmd] with a real pseudo-terminal attached as
 * its controlling terminal (instead of the plain pipes ProcessBuilder gives
 * you), so interactive programs (shells, editors, `top`, tab completion,
 * job control / Ctrl-C, etc.) behave correctly.
 *
 * [createSubprocess] returns the master pty file descriptor and writes the
 * spawned process's pid into pidOut[0]. Reading/writing that fd is done on
 * the Kotlin side by adopting it into a ParcelFileDescriptor.
 */
object NativePty {
    init {
        System.loadLibrary("terminator-pty")
    }

    // @JvmStatic matters here: without it, Kotlin compiles `external fun` on
    // an object as an instance method (JNI signature `(JNIEnv*, jobject, ...)`),
    // but pty.c's functions are written to take `jclass` - i.e. real static
    // methods. @JvmStatic makes that match.

    @JvmStatic
    external fun createSubprocess(
        cmd: String,
        cwd: String?,
        argv: Array<String>,
        envp: Array<String>,
        pidOut: IntArray,
        rows: Int,
        cols: Int
    ): Int

    @JvmStatic
    external fun setWindowSize(fd: Int, rows: Int, cols: Int)

    @JvmStatic
    external fun waitFor(pid: Int): Int

    @JvmStatic
    external fun sendSignal(pid: Int, signal: Int)

    @JvmStatic
    external fun closeFd(fd: Int)
}
