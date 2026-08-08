/*
 * Modern terminal for Terminator android
 * Copyright (C) 2026 Zaman Huseyinli
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

// Minimal PTY backend for the Terminator terminal emulator.
//
// Opens a pseudo-terminal master via /dev/ptmx, forks, and in the child
// makes the corresponding slave device the controlling terminal before
// exec'ing the requested program. This gives the child process a real
// TTY (job control, ioctl(TIOCGWINSZ/TIOCSWINSZ), correct isatty(), etc.)
// instead of the plain pipe you get from ProcessBuilder.

#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

// Some OEM kernels install a seccomp-bpf filter that rejects the specific
// clone flags bionic's fork() passes (it goes through the clone3 syscall,
// or falls back to clone() with CLONE_CHILD_CLEARTID/CLONE_CHILD_SETTID),
// which shows up to the app as "fork failed: Operation not permitted" even
// though nothing else about the call is disallowed. A plain
// syscall(SYS_clone, SIGCHLD, 0, ...) with every other argument zeroed asks
// for the same "act like fork()" behavior but without any of the flags
// those policies filter, so it's used instead when the user has enabled
// Settings > Keyboard > SECCOMP.
static pid_t seccomp_safe_fork(void) {
#if defined(__NR_clone)
    return (pid_t) syscall(__NR_clone, SIGCHLD, 0, 0, 0, 0);
#else
    return fork();
#endif
}

static int throw_ioexception(JNIEnv *env, const char *msg) {
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls != NULL) {
        (*env)->ThrowNew(env, cls, msg);
    }
    return -1;
}

static char **string_array_to_native(JNIEnv *env, jobjectArray array, int *outCount) {
    int count = array != NULL ? (*env)->GetArrayLength(env, array) : 0;
    char **result = (char **) calloc((size_t) count + 1, sizeof(char *));
    for (int i = 0; i < count; i++) {
        jstring element = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        const char *chars = (*env)->GetStringUTFChars(env, element, NULL);
        result[i] = strdup(chars);
        (*env)->ReleaseStringUTFChars(env, element, chars);
        (*env)->DeleteLocalRef(env, element);
    }
    result[count] = NULL;
    *outCount = count;
    return result;
}

static void free_native_array(char **array, int count) {
    for (int i = 0; i < count; i++) {
        free(array[i]);
    }
    free(array);
}

JNIEXPORT jint JNICALL
Java_com_terminator_emulator_NativePty_createSubprocess(
        JNIEnv *env, jclass clazz,
        jstring j_cmd, jstring j_cwd, jobjectArray j_argv, jobjectArray j_envp,
        jintArray j_pidOut, jint rows, jint cols, jboolean j_seccompWorkaround) {

    int masterFd = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (masterFd < 0) {
        return throw_ioexception(env, "Could not open /dev/ptmx");
    }
    if (grantpt(masterFd) != 0) {
        close(masterFd);
        return throw_ioexception(env, "grantpt() failed");
    }
    if (unlockpt(masterFd) != 0) {
        close(masterFd);
        return throw_ioexception(env, "unlockpt() failed");
    }

    char slaveDeviceName[64];
    if (ptsname_r(masterFd, slaveDeviceName, sizeof(slaveDeviceName)) != 0) {
        close(masterFd);
        return throw_ioexception(env, "ptsname_r() failed");
    }

    struct winsize windowSize;
    memset(&windowSize, 0, sizeof(windowSize));
    windowSize.ws_row = (unsigned short) rows;
    windowSize.ws_col = (unsigned short) cols;
    ioctl(masterFd, TIOCSWINSZ, &windowSize);

    const char *cmd = (*env)->GetStringUTFChars(env, j_cmd, NULL);
    const char *cwd = j_cwd != NULL ? (*env)->GetStringUTFChars(env, j_cwd, NULL) : NULL;

    int argc = 0;
    char **argv = string_array_to_native(env, j_argv, &argc);
    int envc = 0;
    char **envp = string_array_to_native(env, j_envp, &envc);

    pid_t pid = j_seccompWorkaround ? seccomp_safe_fork() : fork();
    if (pid < 0) {
        (*env)->ReleaseStringUTFChars(env, j_cmd, cmd);
        if (cwd != NULL) (*env)->ReleaseStringUTFChars(env, j_cwd, cwd);
        free_native_array(argv, argc);
        free_native_array(envp, envc);
        close(masterFd);
        return throw_ioexception(env, "fork() failed");
    }

    if (pid == 0) {
        // Child process: detach from the parent's session, attach the pty
        // slave as our controlling terminal, then replace our image.
        setsid();

        int slaveFd = open(slaveDeviceName, O_RDWR);
        if (slaveFd < 0) {
            _exit(1);
        }
        ioctl(slaveFd, TIOCSCTTY, 0);

        dup2(slaveFd, STDIN_FILENO);
        dup2(slaveFd, STDOUT_FILENO);
        dup2(slaveFd, STDERR_FILENO);
        if (slaveFd > STDERR_FILENO) {
            close(slaveFd);
        }
        close(masterFd);

        if (cwd != NULL && chdir(cwd) != 0) {
            // Fall back silently to the process's default working directory.
        }

        signal(SIGPIPE, SIG_DFL);

        // execvpe (not execve) so a bare command name like "su" is resolved
        // against $PATH from envp, matching ProcessBuilder's old behavior.
        execvpe(cmd, argv, envp);

        // execvpe only returns on failure - surface it on the pty and bail.
        char message[256];
        snprintf(message, sizeof(message), "terminator: exec failed for %s: %s\r\n",
                  cmd, strerror(errno));
        write(STDOUT_FILENO, message, strlen(message));
        _exit(127);
    }

    // Parent process continues here.
    (*env)->ReleaseStringUTFChars(env, j_cmd, cmd);
    if (cwd != NULL) (*env)->ReleaseStringUTFChars(env, j_cwd, cwd);
    free_native_array(argv, argc);
    free_native_array(envp, envc);

    jint pidValue = (jint) pid;
    (*env)->SetIntArrayRegion(env, j_pidOut, 0, 1, &pidValue);

    return masterFd;
}

JNIEXPORT void JNICALL
Java_com_terminator_emulator_NativePty_setWindowSize(
        JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {
    struct winsize windowSize;
    memset(&windowSize, 0, sizeof(windowSize));
    windowSize.ws_row = (unsigned short) rows;
    windowSize.ws_col = (unsigned short) cols;
    ioctl(fd, TIOCSWINSZ, &windowSize);
}

JNIEXPORT jint JNICALL
Java_com_terminator_emulator_NativePty_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    int status = 0;
    waitpid((pid_t) pid, &status, 0);
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return -1;
}

JNIEXPORT void JNICALL
Java_com_terminator_emulator_NativePty_sendSignal(JNIEnv *env, jclass clazz, jint pid, jint signal) {
    kill((pid_t) pid, signal);
}

JNIEXPORT void JNICALL
Java_com_terminator_emulator_NativePty_closeFd(JNIEnv *env, jclass clazz, jint fd) {
    close(fd);
}

// Returns the pgid of the pty's current foreground process group, or -1 on
// error. Used to implement "Ctrl+D only force-kills when nothing else is in
// the foreground": tcgetpgrp() reports which process group the terminal
// driver is currently delivering keyboard-generated signals/input to. When
// the shell is what's in the foreground (its own pgid, since a shell that
// hasn't launched a job is its own process group leader), Ctrl+D forcing an
// exit is safe. When some other program (vim, top, a long-running build)
// has taken the foreground - which happens the instant it starts, because
// job control makes the shell hand foreground status to whatever it just
// launched - tcgetpgrp() returns THAT program's pgid instead, and the
// caller uses that to decide to send plain EOT (0x04) rather than a signal,
// so Ctrl+D doesn't kill work in progress out from under the user.
JNIEXPORT jint JNICALL
Java_com_terminator_emulator_NativePty_getForegroundPgrp(JNIEnv *env, jclass clazz, jint fd) {
    pid_t pgrp = tcgetpgrp(fd);
    return (jint) pgrp;
}
