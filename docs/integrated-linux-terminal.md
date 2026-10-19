# Integrated Linux terminal

The app's terminal route is now an app-private Linux workspace. It downloads the official Alpine ARMv7 minirootfs on first use, verifies its SHA-256 digest, validates guest archive paths before mapping them into a host-side staging directory, preserves the extracted rootfs under `files/linux-runtime/alpine-armv7`, and configures Alpine's matching `latest-stable` repositories.

Archive paths and Android host paths are deliberately separate namespaces. For example, guest `var/spool/cron/crontabs` is mapped under the staging root; it is never compared directly with `/data/user/0/...`. The verified Alpine archive also contains guest-absolute links `/etc/ssl/certs` and `/etc/ssl/cert.pem`. After checksum verification, those two links are converted to equivalent root-relative links while being written; no host-absolute symlink is created. Untrusted extraction tests continue to reject absolute link targets.

Extraction uses a unique app-private staging directory, validates parent directories without following symlinks, validates the complete guest rootfs, and activates it with a backup/restore transaction. A failed extraction or activation leaves the previous rootfs in place. Failures include the archive entry, host destination, host staging root, validation stage, and underlying cause in `ArchiveExtractionException`; the Android layer records the full exception in the debug log and shows the actionable message.

The runtime uses the Termux-maintained PRoot source, pinned to commit `b8c1f5c5c6730dc9d521d5bd5ce1cfeedecde087`. That revision contains the `link2symlink` extension and the `--link2symlink` command-line option. CI packages the verified static ARM32 executable at APK path `lib/armeabi-v7a/libproot.so` with legacy native-library extraction. The app resolves it through `ApplicationInfo.nativeLibraryDir`; it never copies the executable into writable `files/` storage and never calls `System.loadLibrary()`. Before recording the verified commit, it checks the native file's SHA-256, ELF header, permissions, and bounded `libproot.so --link2symlink --version` result.

The integrated terminal's **Diagnostics** and **Copy Diagnostics** actions report the Android/API version, target SDK, supported ABIs, native-library directory, resolved PRoot path, file size, SHA-256, ELF type, permission metadata, process stdout/stderr, exit status, timeout, exception stack trace, and Alpine installation stage. Secrets and environment values are not included. A PRoot repair reuses an existing rootfs when `bin/sh` is present, so the user's Alpine home and installed packages are not replaced; resetting that data requires a separate explicit workflow. A successful CI/emulator run does not establish Samsung Galaxy M10 compatibility; physical-device verification remains separate.

## ARM32 build gate

The CI cross-build uses `arm-linux-gnueabihf-gcc` with ARMHF Ubuntu ports packages for `libtalloc-dev:armhf` and `libseccomp-dev:armhf`, plus `-static -ltalloc -Wl,-z,noexecstack` and the ARM register compatibility defines required by this pinned source. CI requires `file` to report an ELF 32-bit ARM EABI5 statically linked executable and verifies the same bytes inside the debug APK. This proves packaging and ELF shape, not Android kernel compatibility; the binary must still execute on the Android 9 ARM32 device before the runtime is called ready.

A minimum review checklist is:

```text
git clone https://github.com/termux/proot
cd proot
git checkout b8c1f5c5c6730dc9d521d5bd5ce1cfeedecde087
grep -R --line-number link2symlink src
make -C src CROSS_COMPILE=arm-linux-gnueabihf- CC=arm-linux-gnueabihf-gcc STRIP=arm-linux-gnueabihf-strip CFLAGS='-marm -DARM_cpsr=uregs[16] -DPSR_T_BIT=0x00000020' LDFLAGS='-static -ltalloc -Wl,-z,noexecstack'
file src/proot
readelf -l src/proot | grep -E 'INTERP|LOAD'
readelf -d src/proot
src/proot --help | grep -- --link2symlink
```

The exact linker flags and dependency strategy are recorded in CI. A successful host compilation, APK inclusion, or `file` output alone is not sufficient evidence of Android compatibility.

## Runtime acceptance

After the executable and rootfs are installed on a physical Samsung Galaxy M10 SM-M105F, run these commands inside the app and retain their output and exit codes:

```sh
apk --version
apk update
apk add bash python3 git
apk info
echo USMAN_AND_UMAR_TERMINAL_OK
pwd
ls
id
uname -m
bash --version
python3 --version
git --version
```

Also create, modify, rename, link, and delete files; close and reopen the app; and confirm the home directory remains intact. Network failures must be reported separately from PRoot or Alpine failures.
