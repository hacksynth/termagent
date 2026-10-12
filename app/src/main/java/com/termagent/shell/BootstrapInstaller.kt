package com.termagent.shell

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Unpacks the Termux bootstrap (`$PREFIX`) into app-private storage.
 *
 * ## The archive is bundled in the APK, not downloaded
 *
 * The official `bootstrap-<abi>.zip` is fetched **at build time** by
 * `scripts/build-on-termux.sh` and placed in `assets/bootstrap/`, so a release install needs no
 * network at all: the archive is copied out of the APK and unpacked locally.
 *
 * Two benefits beyond working offline: the bootstrap's integrity is covered by the *APK
 * signature* (so no separate checksum is needed), and a first launch cannot be broken by a CDN
 * being unreachable. `Build.SUPPORTED_ABIS` decides which asset is used.
 *
 * The OkHttp download path is retained as a fallback for builds assembled without the asset —
 * the UI says which of the two routes is in play.
 *
 * The unpacking algorithm is a faithful port of upstream
 * `com.termux.app.TermuxInstaller`:
 *
 *  1. every zip entry is relative to `$PREFIX`, so it is extracted into a *staging*
 *     prefix first (`usr-staging`) so a failed install can never leave a half-written
 *     `$PREFIX` behind;
 *  2. `SYMLINKS.txt` holds `target←link-name` pairs (separator U+2190) which are
 *     recreated with `symlink(2)` *after* extraction;
 *  3. entries under `bin/`, `libexec`, `lib/apt/apt-helper` and `lib/apt/methods`
 *     are marked executable — zip archives do not carry the POSIX mode bits, and
 *     without this `bash` cannot be `exec`'d;
 *  4. the staging directory is finally `rename(2)`d onto `$PREFIX`.
 */
class BootstrapInstaller(
    private val context: Context,
    private val env: TermuxEnvironment,
    private val http: OkHttpClient,
) {

    sealed interface State {
        data object Idle : State

        /** Copying the *bundled* archive out of the APK. No network involved. */
        data class Staging(val bytesRead: Long, val totalBytes: Long) : State

        /** Fallback path only: the archive was not bundled, so it is fetched over HTTP. */
        data class Downloading(val bytesRead: Long, val totalBytes: Long) : State
        data class Extracting(val entries: Int) : State
        data object Linking : State
        /** Repointing the bootstrap's hardcoded Termux prefix at our own. */
        data object Patching : State
        /** Running the bootstrap's `postinst` maintainer scripts (best-effort). */
        data object Configuring : State
        data object Done : State
        data class Failed(val message: String) : State
    }

    /**
     * Install the bootstrap. Safe to call from a coroutine; blocking IO runs on
     * [Dispatchers.IO].
     *
     * @param url      override for the bootstrap archive (see [defaultBootstrapUrl]).
     * @param onState  progress callback, invoked from a background thread.
     */
    suspend fun install(
        url: String = defaultBootstrapUrl(),
        onState: (State) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val archive = File(context.cacheDir, "bootstrap.zip")
        try {
            // Prefer the archive shipped inside the APK; only reach for the network if this
            // build was assembled without one.
            val bundled = bundledAssetName()
            if (bundled != null) {
                Log.i(TAG, "Installing from bundled asset: $BOOTSTRAP_ASSET_DIR/$bundled")
                onState(State.Staging(0, bundledArchiveSize() ?: -1))
                copyAsset(bundled, archive) { read, total -> onState(State.Staging(read, total)) }
            } else {
                Log.w(TAG, "No bundled bootstrap for this ABI; falling back to download from $url")
                onState(State.Downloading(0, -1))
                download(url, archive) { read, total -> onState(State.Downloading(read, total)) }
            }

            onState(State.Extracting(0))
            val entries = extractZip(archive, onState)

            onState(State.Linking)
            val executables = fixExecutablePermissions()
            createSymlinks()
            Log.i(TAG, "Marked $executables file(s) executable")

            onState(State.Patching)
            val patched = repointPrefix(env.prefixDir)
            ensurePackageRepointHook()

            onState(State.Configuring)
            runBootstrapSecondStage()

            onState(State.Done)
            Log.i(
                TAG,
                "Bootstrap installed into ${env.prefixDir.absolutePath} " +
                    "($entries entries, $patched file(s) repointed)",
            )
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "Bootstrap install failed", t)
            // Never leave a partial $PREFIX behind.
            runCatching { env.stagingPrefixDir.deleteRecursively() }
            onState(State.Failed(t.message ?: t.javaClass.simpleName))
            Result.failure(t)
        } finally {
            archive.delete()
        }
    }

    // ------------------------------------------------------------------ package repointing
    //
    // `repointPrefix` above rewrites the *bootstrap*, which is enough to make the shell itself
    // work — but not enough to make `pkg install` work.
    //
    // Every archive in the official repository records **absolute** paths of the form
    // `/data/data/com.termux/files/usr/…`. Real Termux can install them because its `$PREFIX`
    // *is* that path. Here it is `/data/data/<our id>/files/usr`, so dpkg resolves the archive's
    // paths against the real root filesystem — i.e. the *other* app's private directory — and
    // fails with:
    //
    //     unable to stat './data/data/com.termux/files' (which was about to be installed):
    //     Permission denied
    //
    // The fix is to intercept each `.deb` after apt has verified it and before dpkg unpacks it
    // and repoint it, exactly like `repointPrefix` does for the bootstrap. apt provides the hook
    // point for this: `DPkg::Pre-Install-Pkgs`, which is handed the list of archives on stdin.
    //
    // The hook is a script in `$PREFIX/libexec/termagent/` (see the `termagent/repoint-deb.sh`
    // asset) plus one apt configuration file. It unpacks the archive with `dpkg-deb -R`, renames
    // the `com.termux` directory to our id, rewrites the remaining references (scripts, shebangs,
    // md5sums, and prefix strings inside ELF binaries) with a length-preserving `sed -i`, and
    // repacks with `dpkg-deb -b`.

    /**
     * Installs (or refreshes) the apt hook that repoints downloaded `.deb` files at our own
     * package id.
     *
     * Idempotent and cheap, so it is safe to call on every launch: an app whose `$PREFIX` was
     * installed by an older build still gains the hook without a reinstall. Returns whether the
     * hook is in place; a failure is logged, never thrown, because package management is a
     * feature of the shell, not a precondition for it.
     */
    fun ensurePackageRepointHook(): Boolean {
        if (!env.isBootstrapInstalled) return false
        return runCatching { writePackageRepointHook() }
            .onFailure { Log.w(TAG, "Could not install the package-repoint hook", it) }
            .isSuccess
    }

    private fun writePackageRepointHook() {
        val prefix = env.prefixDir.absolutePath

        val hookDir = File(env.prefixDir, HOOK_DIR)
        hookDir.mkdirs()
        val hook = File(hookDir, HOOK_NAME)
        val script = hookScript(prefix)
        // Rewriting an identical file would only churn flash, so compare first.
        if (!hook.isFile || hook.readText(Charsets.UTF_8) != script) {
            hook.writeText(script, Charsets.UTF_8)
        }
        // apt refuses to run a hook it cannot execute.
        Os.chmod(hook.absolutePath, EXECUTABLE_MODE)

        val confDir = File(env.prefixDir, APT_CONF_DIR)
        confDir.mkdirs()
        val conf = File(confDir, APT_CONF_NAME)
        val contents = "DPkg::Pre-Install-Pkgs { \"${hook.absolutePath}\"; };\n"
        if (!conf.isFile || conf.readText(Charsets.UTF_8) != contents) {
            conf.writeText(contents, Charsets.UTF_8)
        }
        Log.i(TAG, "Package-repoint hook in place at ${hook.absolutePath}")
    }

    /** The hook script with our concrete prefix and package id substituted in. */
    private fun hookScript(prefix: String): String {
        val template = context.assets.open(HOOK_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        return template
            .replace(PREFIX_PLACEHOLDER, prefix)
            .replace(PACKAGE_PLACEHOLDER, context.packageName)
    }

    // ------------------------------------------------------------------ bootstrap second stage

    /**
     * Runs the bootstrap's own **second stage**, which upstream Termux runs right after it
     * finishes unpacking the archive.
     *
     * The bootstrap is extracted manually rather than installed with `dpkg`, so its packages
     * never had their `postinst` maintainer scripts executed. The second stage does exactly that:
     * it walks the `postinst` scripts under `$PREFIX/var/lib/dpkg/info/`, makes each executable
     * (zip archives carry no mode bits) and runs it with the `DPKG_MAINTSCRIPT_*` environment
     * dpkg would have set.
     * For this bootstrap those scripts register `update-alternatives` entries (the `pager` and
     * `editor` links) and finalise `termux-exec`'s `$LD_PRELOAD` library.
     *
     * It is **best-effort on purpose**: the shell is already usable without it, upstream only logs
     * its output, and a failing maintainer script must not turn a working install into a failed
     * one. The script's own lock file makes a second invocation a no-op, so this is safe to leave
     * in the normal install path.
     */
    private fun runBootstrapSecondStage() {
        val script = File(env.prefixDir, SECOND_STAGE_SCRIPT)
        if (!script.isFile || !env.bashFile.isFile) {
            Log.i(TAG, "Bootstrap has no second-stage script; skipping")
            return
        }
        runCatching {
            val process = ProcessBuilder(env.bashFile.absolutePath, script.absolutePath)
                .directory(env.homeDir)
                .redirectErrorStream(true)
                .apply {
                    // A clean, fully specified environment: the login environment is what the
                    // maintainer scripts expect, and inheriting the app's is not.
                    environment().clear()
                    for (entry in env.buildEnvironment()) {
                        val separator = entry.indexOf('=')
                        if (separator > 0) {
                            environment()[entry.substring(0, separator)] = entry.substring(separator + 1)
                        }
                    }
                }
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            Log.i(TAG, "Bootstrap second stage exited $code\n$output")
        }.onFailure { Log.w(TAG, "Bootstrap second stage could not be run", it) }
    }

    // ------------------------------------------------------------------ bundled asset
    //
    // The asset is stored *uncompressed* (`androidResources { noCompress += "zip" }`), which is
    // what makes `openFd` usable for progress reporting. The name is derived from the ABI so a
    // single APK can carry more than one bootstrap.

    /** Asset file name for this device's ABI, or `null` when this build bundled none. */
    fun bundledAssetName(): String? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        val candidate = "bootstrap-$abi.zip"
        val available = runCatching { context.assets.list(BOOTSTRAP_ASSET_DIR) }.getOrNull()
        return if (available?.contains(candidate) == true) candidate else null
    }

    /**
     * Size of the bundled archive in bytes, or `null` when there is none (or it is stored
     * compressed, in which case the exact size is not known up front).
     */
    fun bundledArchiveSize(): Long? = bundledAssetName()?.let { name ->
        runCatching { context.assets.openFd("$BOOTSTRAP_ASSET_DIR/$name").length }.getOrNull()
    }

    private fun copyAsset(assetName: String, target: File, onProgress: (Long, Long) -> Unit) {
        val total = bundledArchiveSize() ?: -1L
        context.assets.open("$BOOTSTRAP_ASSET_DIR/$assetName").use { input ->
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    out.write(buffer, 0, n)
                    read += n
                    onProgress(read, total)
                }
            }
        }
    }

    private fun download(url: String, target: File, onProgress: (Long, Long) -> Unit) {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Bootstrap download failed: HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("Bootstrap download had no body")
            val total = body.contentLength()
            target.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n == -1) break
                        out.write(buffer, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
        }
    }

    private fun extractZip(archive: File, onState: (State) -> Unit): Int {
        env.stagingPrefixDir.deleteRecursively()
        if (!env.stagingPrefixDir.mkdirs()) {
            throw IllegalStateException("Cannot create ${env.stagingPrefixDir.absolutePath}")
        }

        val symlinks = ArrayList<Pair<String, String>>(64)
        var entries = 0

        ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name == FILE_SYMLINKS) {
                    // Read this entry to its end and parse it from memory.
                    //
                    // Do NOT wrap `zip` in a Reader and close it: `useLines {}` (or `use {}`)
                    // closes the reader, which closes the ZipInputStream underneath, and the
                    // very next `zip.closeEntry()` then fails with
                    // "java.io.IOException: Stream closed". Kotlin's `readBytes()` is used
                    // rather than `InputStream.readAllBytes()`, which is API 33+.
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    for (line in text.lineSequence()) {
                        if (line.isBlank()) continue
                        val parts = line.split(SYMLINK_SEPARATOR)
                        if (parts.size != 2) {
                            throw IllegalStateException("Malformed symlink line: $line")
                        }
                        // Keep BOTH halves raw. The link name is relative to $PREFIX (e.g.
                        // "./bin/lzmore") and the target is *deliberately* relative too
                        // ("xzmore" resolves against the link's own directory), so it must not
                        // be rewritten. Resolving the link name happens later, in
                        // createSymlinks() — which runs *after* usr-staging has been renamed
                        // onto usr, so a staging-based path would be stale by then.
                        symlinks += parts[0] to parts[1]
                    }
                } else {
                    val target = File(env.stagingPrefixDir, name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out -> zip.copyTo(out) }
                        if (needsExecutableBit(name)) {
                            Os.chmod(target.absolutePath, EXECUTABLE_MODE)
                        }
                        entries++
                        if (entries % 500 == 0) onState(State.Extracting(entries))
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        if (symlinks.isEmpty()) {
            throw IllegalStateException("No $FILE_SYMLINKS encountered — not a Termux bootstrap?")
        }
        symlinkRegistry = symlinks

        // rename(2) the fully-populated staging dir onto $PREFIX: atomic, so a killed
        // process can never leave a corrupt prefix.
        env.prefixDir.deleteRecursively()
        if (!env.stagingPrefixDir.renameTo(env.prefixDir)) {
            // Fall back to a copy for the (rare) cross-filesystem case.
            env.stagingPrefixDir.copyRecursively(env.prefixDir, overwrite = true)
            env.stagingPrefixDir.deleteRecursively()
        }
        return entries
    }

    /**
     * Repoint every reference to the upstream Termux prefix at our own.
     *
     * The bootstrap is *compiled* for `/data/data/com.termux/files/usr`. That string is not only
     * in 113 script shebangs (`#!…/com.termux/…/bin/sh`) but also inside **505 ELF binaries**,
     * and ELF offers no escape hatch: `dpkg` ignores `DPKG_CONF_DIR`, and apt resolves its method
     * drivers through a compiled-in path, so under a foreign package name `pkg install` fails
     * with "Permission denied" opening `/data/data/com.termux/files/usr/etc/dpkg/…`.
     *
     * ## Why a strictly same-length substitution
     *
     * `com.termux` and our package id are both 10 bytes, so replacing one with the other leaves
     * **every byte offset in every file unchanged** — nothing shifts and no embedded offset or
     * pointer is invalidated. NUL-padding is explicitly *not* used: padding would terminate the
     * string at the first NUL, so `/…/usr\0\0/etc/dpkg` would read back as `/…/usr`.
     *
     * Two substitutions are applied, longest first:
     *  1. `/data/data/com.termux/files/usr` → our `$PREFIX`;
     *  2. `com.termux` → our package id, which additionally catches paths that bypass `$PREFIX`
     *     (e.g. `/data/data/com.termux/cache/apt/archives`).
     *
     * If the lengths ever differ (e.g. someone re-adds an `applicationIdSuffix`), text files are
     * still rewritten but ELF binaries are left alone, with a warning — the app keeps a working
     * shell, minus package management.
     *
     * @return how many files were rewritten.
     */
    private fun repointPrefix(root: File): Int {
        val target = env.prefixDir.absolutePath
        val targetPackage = context.packageName

        val replacements = listOf(
            UPSTREAM_PREFIX to target,
            UPSTREAM_PACKAGE to targetPackage,
        )
        val sameLength = replacements.all { (from, to) -> from.length == to.length }
        if (!sameLength) {
            Log.w(
                TAG,
                "Prefix is ${target.length} chars but the bootstrap expects " +
                    "${UPSTREAM_PREFIX.length}; ELF binaries cannot be repointed, so apt/dpkg " +
                    "will not work. The applicationId must be ${UPSTREAM_PACKAGE.length} chars.",
            )
        }

        var patched = 0
        val queue = ArrayDeque<File>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val children = queue.removeLast().listFiles() ?: continue
            for (file in children) {
                if (file.isDirectory) {
                    queue.add(file)
                    continue
                }
                if (file.length() > MAX_PATCH_BYTES) continue

                val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
                // An ELF binary may only be touched when the substitution is length-preserving.
                if (!sameLength && looksLikeElf(bytes)) continue

                val rewritten = if (sameLength) {
                    replaceInPlace(bytes, replacements)
                } else {
                    var text = bytes.toString(Charsets.UTF_8)
                    for ((from, to) in replacements) text = text.replace(from, to)
                    text.toByteArray(Charsets.UTF_8)
                } ?: continue

                runCatching { file.writeBytes(rewritten) }
                    .onSuccess { patched++ }
                    .onFailure { Log.w(TAG, "Failed to repoint ${file.absolutePath}", it) }
            }
        }
        return patched
    }

    /**
     * Applies length-preserving byte substitutions to [bytes] **in place** (the array is mutated
     * and returned). Returns `null` when nothing matched, so callers can skip rewriting the file.
     */
    private fun replaceInPlace(
        bytes: ByteArray,
        replacements: List<Pair<String, String>>,
    ): ByteArray? {
        var hits = 0
        for ((from, to) in replacements) {
            val needle = from.toByteArray(Charsets.UTF_8)
            val replacement = to.toByteArray(Charsets.UTF_8)
            var index = indexOfBytes(bytes, needle, 0)
            while (index >= 0) {
                replacement.copyInto(bytes, index)
                hits++
                index = indexOfBytes(bytes, needle, index + needle.size)
            }
        }
        return if (hits > 0) bytes else null
    }

    /** Plain byte-substring search; `ByteArray` has no built-in equivalent on Android. */
    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, start: Int): Int {
        if (needle.isEmpty() || start < 0) return -1
        outer@ for (i in start..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun looksLikeElf(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x7F.toByte() &&
            bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()

    /**
     * Restore executable permission across the freshly unpacked prefix.
     *
     * A zip archive stores no POSIX mode bits, so `ZipInputStream` + `FileOutputStream` leaves
     * **every** extracted file at 0644 and not a single binary would run. Upstream Termux solves
     * this by chmod-ing entry names one by one during extraction; doing a pass over the finished
     * prefix is both simpler and less fragile, because the bootstrap only ever places runnable
     * files in a few well-known directories.
     *
     * @return how many files were made executable.
     */
    private fun fixExecutablePermissions(): Int {
        val executableDirs = listOf("bin", "sbin", "libexec", "lib/apt", "lib/apt/methods")
        var count = 0
        for (relative in executableDirs) {
            val dir = File(env.prefixDir, relative)
            if (!dir.isDirectory) continue
            for (file in dir.walkTopDown()) {
                if (!file.isFile) continue
                runCatching { Os.chmod(file.absolutePath, EXECUTABLE_MODE) }
                    .onSuccess { count++ }
                    .onFailure { Log.w(TAG, "chmod failed for ${file.absolutePath}", it) }
            }
        }
        return count
    }

    /**
     * Raw `target to linkName` pairs from `SYMLINKS.txt`.
     *
     * They are resolved against [TermuxEnvironment.prefixDir] here rather than at extraction
     * time, because extraction finishes by renaming `usr-staging` onto `usr`.
     */
    private var symlinkRegistry: List<Pair<String, String>> = emptyList()

    private fun createSymlinks() {
        for ((target, linkName) in symlinkRegistry) {
            val linkPath = File(env.prefixDir, linkName).absolutePath
            val link = File(linkPath)
            link.parentFile?.mkdirs()
            // delete() removes the link itself, including a dangling one — `exists()` would
            // report false for a broken symlink and skip the cleanup.
            link.delete()

            // `SYMLINKS.txt` mixes two kinds of target: relative ones (`xzmore`, resolved
            // against the link's own directory) and 20 *absolute* ones that hardcode the
            // upstream prefix, e.g.
            //     /data/data/com.termux/files/usr/bin/unzip <- ./bin/zipinfo
            // The absolute ones must be repointed, otherwise they dangle — or, worse, silently
            // reach into a real Termux installation if one happens to be present.
            val resolvedTarget = target.replace(UPSTREAM_PREFIX, env.prefixDir.absolutePath)
            try {
                Os.symlink(resolvedTarget, linkPath)
            } catch (t: Throwable) {
                Log.w(TAG, "symlink($resolvedTarget, $linkPath) failed", t)
            }
        }
    }

    private fun needsExecutableBit(zipEntryName: String): Boolean =
        zipEntryName.startsWith("bin/") ||
            zipEntryName.startsWith("libexec") ||
            zipEntryName.startsWith("lib/apt/apt-helper") ||
            zipEntryName.startsWith("lib/apt/methods")

    companion object {
        private const val TAG = "BootstrapInstaller"
        private const val FILE_SYMLINKS = "SYMLINKS.txt"

        /** Where `scripts/build-on-termux.sh` stages the bootstrap inside the APK. */
        const val BOOTSTRAP_ASSET_DIR = "bootstrap"

        /** Hook template asset — see [ensurePackageRepointHook]. */
        private const val HOOK_ASSET = "termagent/repoint-deb.sh"
        private const val HOOK_DIR = "libexec/termagent"
        private const val HOOK_NAME = "repoint-deb"
        private const val APT_CONF_DIR = "etc/apt/apt.conf.d"
        private const val APT_CONF_NAME = "99-termagent-repoint"

        /** Placeholders the hook template is rendered with. */
        private const val PREFIX_PLACEHOLDER = "__TERMAGENT_PREFIX__"
        private const val PACKAGE_PLACEHOLDER = "__TERMAGENT_PACKAGE__"

        /** Verbatim path of the bootstrap's second-stage runner — see [runBootstrapSecondStage]. */
        private const val SECOND_STAGE_SCRIPT =
            "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh"

        /** The `$PREFIX` the upstream bootstrap is compiled for — see [repointPrefix]. */
        private const val UPSTREAM_PREFIX = "/data/data/com.termux/files/usr"

        /**
         * The upstream package id. It is exactly [UPSTREAM_PREFIX].length - 21 = 10 characters,
         * which is what makes the same-length repointing possible; our own `applicationId` is
         * pinned to the same width (see app/build.gradle.kts).
         */
        private const val UPSTREAM_PACKAGE = "com.termux"

        /**
         * Per-file ceiling for repointing. Large enough for every bootstrap binary (the biggest
         * are a few MB) while still bounding memory if something unexpected appears in the tree.
         */
        private const val MAX_PATCH_BYTES = 64L * 1024L * 1024L

        /** Upstream uses U+2190 (LEFTWARDS ARROW) to separate target from link name. */
        private const val SYMLINK_SEPARATOR = "\u2190"

        /** `chmod 0700`, as upstream does. */
        private const val EXECUTABLE_MODE = 0b111_000_000

        /**
         * The official Termux bootstrap archives, published alongside every
         * `termux-packages` release.
         */
        fun defaultBootstrapUrl(abi: String = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"): String {
            val asset = when (abi) {
                "arm64-v8a" -> "bootstrap-aarch64.zip"
                "armeabi-v7a", "armeabi" -> "bootstrap-arm.zip"
                "x86_64" -> "bootstrap-x86_64.zip"
                "x86" -> "bootstrap-i686.zip"
                else -> "bootstrap-aarch64.zip"
            }
            return "https://github.com/termux/termux-packages/releases/latest/download/$asset"
        }
    }
}
