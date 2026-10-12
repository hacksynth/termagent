package com.termagent.shell

import android.content.Context
import android.os.Build
import android.os.Process
import com.termagent.BuildConfig
import java.io.File

/**
 * Prefers the short `/data/data/<pkg>` spelling of the app's private directory.
 *
 * `Context.filesDir` returns `/data/user/0/<pkg>/files` on modern Android. That is the same
 * directory as `/data/data/<pkg>/files`, but two characters longer, and [BootstrapInstaller]
 * repoints the bootstrap's compiled-in `/data/data/com.termux/files/usr` with a strictly
 * same-length substitution. Two extra characters would silently disable that for every ELF
 * binary, costing `apt`/`dpkg`. Falls back to the framework value if the short form somehow
 * does not resolve.
 */
private fun canonicalFilesDir(context: Context): File {
    // Reading `context.filesDir` also has the side effect of creating the directory, so do it
    // first — otherwise the short form could be checked before files/ exists.
    val framework = context.filesDir
    val short = File("/data/data/${context.packageName}/files")
    return if (short.isDirectory) short else framework
}

/**
 * Filesystem layout and process environment for the forked Termux backend.
 *
 * The layout deliberately mirrors Termux so that the upstream bootstrap packages
 * work completely unmodified:
 *
 * ```
 * filesDir/                 ->  (Termux: TERMUX_FILES_DIR_PATH)
 * filesDir/usr              ->  $PREFIX
 * filesDir/usr/bin          ->  $PATH
 * filesDir/usr/lib          ->  (DT_RUNPATH target of the bootstrap binaries)
 * filesDir/usr/tmp          ->  $TMPDIR
 * filesDir/usr-staging      ->  staging dir used while unpacking the bootstrap
 * filesDir/home             ->  $HOME
 * ```
 */
class TermuxEnvironment(context: Context) {

    /**
     * Termux calls this `TERMUX_FILES_DIR_PATH`, i.e. `/data/data/<pkg>/files`.
     *
     * ## Why the `/data/data/…` spelling and not `context.filesDir`
     *
     * Modern Android hands back `/data/user/0/<pkg>/files`, while the Termux bootstrap is
     * compiled against `/data/data/<pkg>/…`. The two are the **same directory** (verified: same
     * inode) but their *string* lengths differ by two characters — 33 vs 31 for our prefix —
     * and [BootstrapInstaller] repoints the compiled-in path with a strictly same-length byte
     * substitution. Using the longer spelling silently disables that for every ELF binary in
     * the bootstrap, which costs package management (`apt`/`dpkg`).
     *
     * `/data/data` is a stable synonym for `/data/user/0` for the owner of the app's own data
     * directory, so the short spelling is used when it resolves; otherwise we fall back to
     * whatever the framework gave us and the installer degrades gracefully.
     */
    val filesDir: File = canonicalFilesDir(context)

    val prefixDir: File = File(filesDir, DIR_USR)
    val stagingPrefixDir: File = File(filesDir, DIR_USR_STAGING)
    val binDir: File = File(prefixDir, "bin")
    val libDir: File = File(prefixDir, "lib")
    val tmpDir: File = File(prefixDir, "tmp")
    val etcDir: File = File(prefixDir, "etc")
    val homeDir: File = File(filesDir, DIR_HOME)

    /** Set once the bootstrap has been unpacked; used to pick the login shell. */
    val bashFile: File = File(binDir, "bash")

    val isBootstrapInstalled: Boolean
        get() = bashFile.exists()

    /** Path of the executable spawned inside the PTY. */
    val loginShellPath: String
        get() = if (isBootstrapInstalled) bashFile.absolutePath else FALLBACK_SHELL

    /**
     * `argv` handed to the PTY child.
     *
     * `argv[0] == "-bash"` makes bash start as a *login* shell, which is exactly how
     * Termux gets `$PREFIX/etc/profile` (and with it `libtermux-exec`, the PATH fix-ups,
     * `$PREFIX` helpers, …) sourced on every session.
     */
    val loginShellArguments: Array<String>
        get() = if (isBootstrapInstalled) arrayOf("-bash") else arrayOf("sh")

    /**
     * `$LD_PRELOAD` library that carries Termux's `exec` shim, or `null` before the bootstrap
     * exists.
     *
     * ## Why this is required, and why it is *not* the same nothing as upstream
     *
     * On Android the dynamic linker runs with `AT_SECURE`, which makes it ignore
     * `LD_LIBRARY_PATH`. Termux therefore cannot rely on library search paths for the handful of
     * libc entry points it must intercept, and instead ships a small `LD_PRELOAD` shim
     * (`libtermux-exec-ld-preload.so`, installed by the `termux-exec` package). Real Termux
     * exports exactly this variable into every session.
     *
     * Leaving it unset is not fatal for simple commands, but it *is* the difference between the
     * forked environment and Termux: the shim is what keeps `$PATH` lookups, shebang handling and
     * `exec()` of bare command names consistent once packages are installed. We set the same
     * variable the upstream app does, to the file the `termux-exec` postinst would have installed.
     *
     * The path is only returned when the file is actually present, so a half-built prefix cannot
     * break the dynamic linker (a missing `LD_PRELOAD` target aborts every process with
     * "CANNOT LINK EXECUTABLE").
     */
    private fun termuxExecPreload(): String? {
        val preload = File(prefixDir, "lib/libtermux-exec-ld-preload.so")
        return if (preload.isFile) preload.absolutePath else null
    }

    /** Create every directory the shell expects to exist before it starts. */
    fun prepareDirectories() {
        for (dir in listOf(prefixDir, binDir, libDir, tmpDir, etcDir, homeDir)) {
            if (!dir.exists()) dir.mkdirs()
        }
    }

    /**
     * Environment for the PTY child, in `KEY=VALUE` form (this is the array
     * `JNI.createSubprocess` turns into `envp`).
     *
     * ## `LD_LIBRARY_PATH` — required, and deliberately *unlike* upstream Termux
     *
     * Upstream Termux leaves `LD_LIBRARY_PATH` unset because its binaries carry
     * `DT_RUNPATH = /data/data/com.termux/files/usr/lib`, which is exactly where its libs live.
     *
     * We ship the same bootstrap under a **different package name**, so that baked-in path does
     * not exist for us. `DT_RUNPATH` is consulted *after* `LD_LIBRARY_PATH`, so pointing the
     * latter at our own `$PREFIX/lib` is what makes the binaries link — verified: with a
     * RUNPATH aimed at a nonexistent directory the loader fails with
     * "library ... not found: needed by main executable", and setting this variable fixes it.
     */
    fun buildEnvironment(): Array<String> {
        val installed = isBootstrapInstalled
        val path = if (installed) {
            binDir.absolutePath
        } else {
            // Failsafe PATH (no bootstrap yet): system binaries only.
            "$FALLBACK_BIN:$binDir"
        }

        val env = linkedMapOf(
            "PREFIX" to prefixDir.absolutePath,
            "HOME" to homeDir.absolutePath,
            "TMPDIR" to tmpDir.absolutePath,
            "PATH" to path,
            // See the note above: compensates for the bootstrap's baked-in Termux RUNPATH.
            "LD_LIBRARY_PATH" to libDir.absolutePath,
            "SHELL" to loginShellPath,
            // See [termuxExecPreload] below: drives `termux-exec`.
            "TERMUX__PREFIX" to prefixDir.absolutePath,
            "TERMUX__ROOTFS_DIR" to filesDir.absolutePath,
            "TERMUX__HOME" to homeDir.absolutePath,
            "TERMUX__USER_ID" to Process.myUid().toString(),
            "TERM" to TERM,
            "COLORTERM" to "truecolor",
            "LANG" to "en_US.UTF-8",
            "TZ" to "UTC",
        )

        // Android-visible roots, as set by Termux's AndroidShellEnvironment.
        env["ANDROID_ROOT"] = System.getenv("ANDROID_ROOT") ?: "/system"
        env["ANDROID_DATA"] = System.getenv("ANDROID_DATA") ?: "/data"
        env["EXTERNAL_STORAGE"] = "/sdcard"
        env["BOOTCLASSPATH"] = System.getenv("BOOTCLASSPATH") ?: ""
        // `termux-exec`'s helper and several `$PREFIX` scripts read the double-underscore
        // spelling of the SDK level (Termux exports both). Without it
        // `termux-exec-ld-preload-lib setup` bails out and `$LD_PRELOAD` is never configured.
        env["ANDROID__BUILD_VERSION_SDK"] = Build.VERSION.SDK_INT.toString()

        // Some `$PREFIX` recipes probe for these; keep the surface small but honest.
        env["TERMAGENT__VERSION"] = BuildConfig.VERSION_NAME
        env["TERMAGENT__PREFIX"] = prefixDir.absolutePath
        env["TERMAGENT__HOME"] = homeDir.absolutePath

        // Termux compatibility shims: a handful of scripts key off these.
        if (installed) {
            env["TERMUX_VERSION"] = BuildConfig.VERSION_NAME
            env["TERMUX_APP__PACKAGE_NAME"] = BuildConfig.APPLICATION_ID
            env["TERMUX_MAIN_PACKAGE_FORMAT"] = "debian"
            termuxExecPreload()?.let { env["LD_PRELOAD"] = it }
        }

        env["SDK__VERSION"] = Build.VERSION.SDK_INT.toString()

        // `envp` must be NUL-terminated by the native layer, so an array is enough;
        // drop empty values because `KEY=` confuses some tools.
        return env.filterValues { it.isNotEmpty() }
            .map { (k, v) -> "$k=$v" }
            .toTypedArray()
    }

    private companion object {
        const val DIR_USR = "usr"
        const val DIR_USR_STAGING = "usr-staging"
        const val DIR_HOME = "home"

        /** Termux itself falls back to the system shell if the bootstrap is missing. */
        const val FALLBACK_SHELL = "/system/bin/sh"
        const val FALLBACK_BIN = "/system/bin"

        const val TERM = "xterm-256color"
    }
}
