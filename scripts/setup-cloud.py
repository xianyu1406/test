#!/usr/bin/env python3
"""Install the pinned Linux x86_64 cloud toolchain, verifying every download.

Uses only the Python standard library and writes outside the checkout. For desktop
Android Studio, install SDK 36 / build-tools 36.0.0 and use an existing JDK 21.
"""
import concurrent.futures
import hashlib
import os
from pathlib import Path
import platform
import shutil
import tarfile
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(os.environ.get("ZHUNDIAN_TOOLS_DIR", "/workspace/android-toolchain"))
DOWNLOADS = ROOT / "downloads"
SDK = ROOT / "sdk"
# Checksums obtained over verified TLS from the publisher's release/checksum files
# and https://dl.google.com/android/repository/repository2-3.xml (2026-10-08).
PACKAGES = [
    ("https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.9%2B10/OpenJDK21U-jdk_x64_linux_hotspot_21.0.9_10.tar.gz",
     "sha256", "810d3773df7e0d6c4394e4e244b264c8b30e0b05a0acf542d065fd78a6b65c2f", "jdk-21.0.9+10", "jdk-21.0.9+10"),
    ("https://dl.google.com/android/repository/platform-36_r02.zip",
     "sha1", "2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576", "android-36", "sdk/platforms/android-36"),
    ("https://dl.google.com/android/repository/build-tools_r36_linux.zip",
     "sha1", "b0b6376977657e8ad9b969bacf4093601da2c6fb", "android-16", "sdk/build-tools/36.0.0"),
    ("https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip",
     "sha1", "e025545c62a8e64c7559119566a569fb1dec5f60", "cmdline-tools", "sdk/cmdline-tools/latest"),
    ("https://dl.google.com/android/repository/platform-tools_r37.0.1-linux.zip",
     "sha1", "477254aa5f903c15cf51001717bdf347fb6b53e0", "platform-tools", "sdk/platform-tools"),
]


def install(package):
    url, algorithm, expected, source, target = package
    archive = DOWNLOADS / url.rsplit("/", 1)[1]
    if not archive.exists():
        partial = archive.with_suffix(archive.suffix + ".part")
        urllib.request.urlretrieve(url, partial)
        partial.rename(archive)
    digest = hashlib.new(algorithm)
    with archive.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    if digest.hexdigest() != expected:
        raise RuntimeError("Checksum mismatch: " + archive.name)
    destination = ROOT / target
    if not destination.exists():
        unpack = ROOT / "unpack" / archive.name.replace(".", "-")
        unpack.mkdir(parents=True, exist_ok=True)
        if archive.name.endswith(".tar.gz"):
            with tarfile.open(archive) as stream:
                stream.extractall(unpack, filter="data")
        else:
            with zipfile.ZipFile(archive) as stream:
                for item in stream.infolist():
                    resolved = (unpack / item.filename).resolve()
                    if not resolved.is_relative_to(unpack.resolve()):
                        raise RuntimeError("Unsafe archive path")
                stream.extractall(unpack)
                for item in stream.infolist():
                    mode = item.external_attr >> 16
                    if mode:
                        (unpack / item.filename).chmod(mode)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(unpack / source, destination)
    print("Verified and installed:", target, flush=True)


def configure_gradle_network():
    # Java does not consume HTTPS_PROXY. Do not write authentication or disable TLS.
    proxy = urllib.parse.urlsplit(os.environ.get("HTTPS_PROXY", ""))
    if not proxy.hostname:
        return
    if proxy.username or proxy.password:
        raise RuntimeError("Authenticated proxies require platform-supported Java configuration")
    cache = Path(os.environ.get("GRADLE_USER_HOME", str(ROOT / "gradle-cache")))
    cache.mkdir(parents=True, exist_ok=True)
    props = cache / "gradle.properties"
    updates = {
        "systemProp.https.proxyHost": proxy.hostname,
        "systemProp.https.proxyPort": str(proxy.port or 80),
        "systemProp.http.proxyHost": proxy.hostname,
        "systemProp.http.proxyPort": str(proxy.port or 80),
    }
    # This cloud machine supplies its trusted proxy CA in the OS Java truststore.
    truststore = Path("/etc/ssl/certs/java/cacerts")
    if truststore.exists():
        updates["systemProp.javax.net.ssl.trustStore"] = str(truststore)
    old = props.read_text().splitlines() if props.exists() else []
    kept = [line for line in old if line.split("=", 1)[0] not in updates]
    props.write_text("\n".join(kept + [k + "=" + v for k, v in updates.items()]) + "\n")
    print("Configured local Java proxy and trusted OS certificates; no credentials saved")


def main():
    if platform.system() != "Linux" or platform.machine() not in ("x86_64", "amd64"):
        raise SystemExit("This cloud installer requires Linux x86_64; use Android Studio SDK tools elsewhere")
    DOWNLOADS.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
        list(executor.map(install, PACKAGES))
    configure_gradle_network()
    user_dir = ROOT / "android-user"
    user_dir.mkdir(exist_ok=True)
    adb_default_dir = Path.home() / ".android"
    if not adb_default_dir.exists() and not adb_default_dir.is_symlink():
        try:
            adb_default_dir.symlink_to(user_dir, target_is_directory=True)
        except PermissionError:
            print("ADB needs a writable ~/.android; grant that directory or link it to", user_dir)
    print("Next: source scripts/env.sh && ./gradlew --version")


if __name__ == "__main__":
    main()
