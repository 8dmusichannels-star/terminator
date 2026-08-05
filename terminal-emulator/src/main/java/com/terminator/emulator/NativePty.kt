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
        cols: Int,
        // Settings > Keyboard > SECCOMP. Some OEM kernels ship a seccomp-bpf
        // policy that rejects the clone flags glibc/bionic's fork() adds
        // (via the clone3 path), surfacing as "fork failed: Operation not
        // permitted" even though the process has every other permission it
        // needs. When true, pty.c spawns the child via a raw, minimal
        // syscall(SYS_clone, SIGCHLD, ...) instead of fork(), which avoids
        // the flags those policies filter.
        seccompWorkaround: Boolean
    ): Int

    @JvmStatic
    external fun setWindowSize(fd: Int, rows: Int, cols: Int)

    @JvmStatic
    external fun waitFor(pid: Int): Int

    @JvmStatic
    external fun sendSignal(pid: Int, signal: Int)

    @JvmStatic
    external fun closeFd(fd: Int)

    // See pty.c's doc comment - reports which process group currently owns
    // the terminal's foreground (tcgetpgrp). Returns -1 on error.
    @JvmStatic
    external fun getForegroundPgrp(fd: Int): Int
}
