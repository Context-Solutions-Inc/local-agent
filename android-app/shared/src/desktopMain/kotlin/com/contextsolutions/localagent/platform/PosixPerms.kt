package com.contextsolutions.localagent.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * Best-effort owner-only file/dir locking for the desktop secret store (security
 * fix H2). Pure JDK `java.nio.file` — no new dependency.
 *
 * POSIX only: on a non-POSIX filesystem (Windows/NTFS, which uses ACLs) every
 * call is a silent no-op, so callers can apply it unconditionally. Failures are
 * swallowed — locking down a secret file must never crash startup.
 */
internal object PosixPerms {
    /** `rw-------` (0600). */
    val FILE_0600: Set<PosixFilePermission> = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
    )

    /** `rwx------` (0700). */
    val DIR_0700: Set<PosixFilePermission> = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
    )

    /**
     * Authoritative POSIX check — handles a non-POSIX filesystem even on Linux,
     * which `os.name` alone can't tell.
     */
    fun isPosix(path: Path): Boolean =
        runCatching { path.fileSystem.supportedFileAttributeViews().contains("posix") }
            .getOrDefault(false)

    /** Apply [perms] to [target] if (and only if) the filesystem is POSIX. Best-effort. */
    fun restrict(target: File, perms: Set<PosixFilePermission>) {
        val path = target.toPath()
        if (!isPosix(path)) return
        runCatching { Files.setPosixFilePermissions(path, perms) }
    }
}
