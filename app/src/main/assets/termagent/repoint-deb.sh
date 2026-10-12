#!__TERMAGENT_PREFIX__/bin/bash
# termagent: repoint the upstream Termux package path (com.termux) at this app's own
# package id (__TERMAGENT_PACKAGE__) inside every .deb before dpkg unpacks it.
#
# Why this is necessary
# ---------------------
# The official Termux `.deb` packages record *absolute* file paths of the form
#   /data/data/com.termux/files/usr/...
# Real Termux can use them verbatim because its $PREFIX *is* that path. Under a fork
# with a different applicationId, dpkg resolves the same paths against the real root
# filesystem — i.e. the *other* app's private data directory — and dies with
#   unable to stat './data/data/com.termux/files' (which was about to be installed):
#   Permission denied
# The bootstrap's own repointPrefix only rewrites the bootstrap itself, never the
# packages downloaded later, so it has to happen here.
#
# `com.termux` and the app's id are both 10 bytes, so the substitution is
# length-preserving and can rewrite file *contents* (scripts, shebangs, and prefix
# strings compiled into ELF binaries) in place without shifting any offset.
#
# Invoked by apt through `DPkg::Pre-Install-Pkgs`; the archive paths arrive on stdin,
# one per line, *after* apt has verified them and *before* dpkg runs.
set -u

PREFIX=__TERMAGENT_PREFIX__
FROM_RE='com\.termux'
TO='__TERMAGENT_PACKAGE__'

export PATH="$PREFIX/bin:$PATH"
export LD_LIBRARY_PATH="$PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

# Store .deb files uncompressed; a compressed data.tar cannot be byte-patched.
command -v dpkg-deb >/dev/null 2>&1 || exit 0

TMP_BASE="$PREFIX/tmp"
[ -d "$TMP_BASE" ] || TMP_BASE=/tmp

status=0
while IFS= read -r deb; do
    case "$deb" in
        *.deb) ;;
        *) continue ;;
    esac
    [ -f "$deb" ] || continue

    work="$(mktemp -d "$TMP_BASE/.termagent-repoint.XXXXXX" 2>/dev/null)" || {
        echo "termagent-repoint: cannot create work dir under $TMP_BASE" >&2
        status=1
        continue
    }

    if ! dpkg-deb -R "$deb" "$work/x" 2>/dev/null; then
        echo "termagent-repoint: cannot unpack $deb" >&2
        status=1
        rm -rf "$work"
        continue
    fi

    # 1. Rename the path component itself: data/data/com.termux -> data/data/<app>
    if [ -d "$work/x/data/data/com.termux" ]; then
        mv "$work/x/data/data/com.termux" "$work/x/data/data/$TO"
    fi

    # 2. Rewrite every remaining reference inside file contents (scripts, shebangs,
    #    md5sums paths, and strings compiled into ELF binaries). `sed -i` is used on
    #    purpose: the replacement is length-preserving, so GNU sed rewrites the file
    #    in place without changing its size.
    grep -rla "$FROM_RE" "$work/x" 2>/dev/null | while IFS= read -r f; do
        sed -i "s/$FROM_RE/$TO/g" "$f" 2>/dev/null
    done

    # 3. `dpkg-deb -R` hands back maintainer scripts as 0700, and `dpkg-deb -b`
    #    refuses to build a package whose maintainer scripts are not at least
    #    group/other readable ("bad permissions 700 (must be >=0555 and <=0775)").
    for s in preinst postinst prerm postrm config; do
        [ -f "$work/x/DEBIAN/$s" ] && chmod 0755 "$work/x/DEBIAN/$s"
    done
    for f in control md5sums conffiles; do
        [ -f "$work/x/DEBIAN/$f" ] && chmod 0644 "$work/x/DEBIAN/$f"
    done

    # 4. Repack over the original archive so dpkg unpacks the patched one.
    if ! dpkg-deb -b "$work/x" "$deb.termagent" 2>/dev/null; then
        echo "termagent-repoint: cannot repack $deb" >&2
        rm -rf "$work" "$deb.termagent"
        status=1
        continue
    fi
    mv -f "$deb.termagent" "$deb"
    rm -rf "$work"
done

exit $status
