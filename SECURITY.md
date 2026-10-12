# Security Policy

TermAgent runs an AI agent that can execute **arbitrary shell commands** on your
device through a real Termux `$PREFIX`, and stores API credentials for the model
providers you configure. Please read this before reporting or dismissing anything.

## Supported versions

| Version | Supported |
| --- | --- |
| `0.1.x` (current, pre-release) | ✅ |
| anything older | ❌ |

Only the latest `master` is actively maintained. Fixes are not backported.

## Reporting a vulnerability

**Do not open a public issue for a security problem.**

Use GitHub's private vulnerability reporting:
**Security → Advisories → [Report a vulnerability](../../security/advisories/new)**
(or the *Security* tab of the repository).

Please include:

* affected version / commit;
* a description of the impact and the attack path;
* a minimal reproduction (a crafted provider, a malicious model response, a shell
  command that escapes its sandbox, an APK-repacking scenario…);
* any suggested fix.

You can expect an initial acknowledgement within a few days. This is a
volunteer-maintained project, so please be patient; reporters who want to be named
will be credited in the release notes.

## Threat model — what is *by design* and not a vulnerability

Understanding these saves us both time. Several of the following are the whole
point of the app; they are documented rather than fixed.

1. **The agent can run any shell command.**
   `run_shell` executes in a persistent PTY with the app's own UID. There is no
   sandbox beyond Android's app isolation, and there is deliberately **no
   allow-list of commands**. The only guard is the *Settings → Allow command
   execution* switch, which removes the tool from the model's context entirely when
   off. Prompt-injection from file contents, command output or a compromised model
   endpoint leading to command execution is the intended capability, not a bug.
   Enable it only if you accept that.

2. **API keys are stored unencrypted in `SharedPreferences`.**
   They live in the app's private data directory
   (`/data/data/com.tagent/shared_prefs/`), which on a non-rooted device is not
   readable by other apps. They are *not* encrypted at rest.
   Physical access to a rooted/unlocked device means key compromise.

3. **`targetSdk = 28`.**
   This is required for `exec()` of the bootstrap binaries (see the README) and it
   also means the app opts out of several modern platform hardening behaviours and
   cannot be published on Google Play. TermAgent is intended for **sideloading /
   F-Droid only**. If you install an APK from anywhere other than a source you
   trust, treat it as a full shell as your user.

4. **The bundle carries a real Termux `$PREFIX`.**
   Signing the APK is the integrity boundary: `scripts/build-on-termux.sh` downloads
   the official `termux-packages` bootstrap and bundles it, so the APK signature
   covers it. The app installs its shell **offline** from that asset; it does not
   fetch and run an unverified shell from the network on first launch.

5. **A backend that speaks to the model provider.**
   All network traffic is user-configured (their own base URL and API key, or the
   built-in presets). There is no telemetry, no analytics and no first-party server.
   Prompt content, file contents and command output are sent to whichever provider
   you configured — that is inherent to the feature, not a leak.

6. **Package installation repoints archives at the app's prefix.**
   The `DPkg::Pre-Install-Pkgs` hook rewrites downloaded `.deb` archives
   (`com.termux` → `com.tagent`, a length-preserving byte substitution) so dpkg can
   unpack them into this app's private prefix. It only ever rewrites archives that
   apt itself verified against the configured repository keys; it is not a
   general-purpose package-installation vector.

## What we *do* consider a vulnerability

* Command execution **without** the agent or user initiating it (e.g. via a
  malicious file name, a crafted `providers.json`/model response that runs commands
  even when the toggle is off, or an unintended Android component export).
* Any exported activity/service/receiver/provider reachable by a **third-party app**
  that can trigger shell execution or read credentials. (Note `allowBackup`-related
  data extraction is in scope: `tools:ignore`/`android:allowBackup` should not
  silently ship your keys to a backup.)
* Escaping the app's private prefix during extraction/installation (path traversal
  in the bootstrap unpack or the `.deb` repoint hook).
* Leaking the API key to an endpoint other than the configured one, or to logs.
* A build/release artifact that ships a developer's key, keystore or
  `local.properties`.

## Hardening advice for users

* Do **not** enable command execution on a device you rely on for anything sensitive.
* Use a **scoped/limited API key** with a spending cap; keys are stored in plaintext.
* Prefer building the APK yourself from source (`scripts/build-on-termux.sh`) over
  installing a random prebuilt.
* Turn on the Android backup exclusion if you do not want your provider keys leaving
  the device.
