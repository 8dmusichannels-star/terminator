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

package com.terminator.app.session

import java.util.UUID

enum class SessionType { COMMAND_ARG, FILE_BASE }

/**
 * A saved session definition, as configured in Settings > Sessions.
 * The default Android-shell session is stored the same way, just flagged
 * isDefault = true and non-deletable-without-replacement (repository enforces
 * "at least one default must always exist").
 */
data class SessionEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: SessionType,
    // COMMAND_ARG
    val commandPath: String? = null,
    // FILE_BASE
    val filePath: String? = null,
    val fileName: String? = null,
    // Directory the spawned process starts in (its cwd / $HOME). Null
    // falls back to whatever TerminalSession.start() already used before
    // this existed - the session's own per-session history directory.
    val workingDirectory: String? = null,
    val useRoot: Boolean = false,
    val isFavorite: Boolean = false,
    val isDefault: Boolean = false,
    val allowMultipleInstances: Boolean = false
) {
    fun resolvedExecutable(): String = when (type) {
        SessionType.COMMAND_ARG -> commandPath ?: "/system/bin/sh"
        SessionType.FILE_BASE -> {
            val dir = filePath ?: "/sdcard/Terminator"
            val name = fileName ?: "session.sh"
            "$dir/$name".replace("//", "/")
        }
    }

    companion object {
        fun defaultAndroidShell(): SessionEntry = SessionEntry(
            id = "default-android-shell",
            name = "Android Shell",
            type = SessionType.COMMAND_ARG,
            commandPath = "/system/bin/sh",
            isDefault = true
        )
    }
}
