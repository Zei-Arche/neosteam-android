"""Verify a signed NeoSteam APK and stage its release and download files."""

import argparse
from hashlib import sha256
from html import escape
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from tempfile import TemporaryDirectory
from urllib.parse import urlsplit
from zipfile import BadZipFile, ZipFile


MAX_VERSION_CODE = 2_100_000_000
MAX_APK_BYTES = 2 * 1024**3 - 1
PACKAGE = "org.neosteam.revival"
BUILD_TOOLS = "36.0.0"
TAG = re.compile(r"android-v([1-9][0-9]{0,9})")
VERSION_NAME = re.compile(r"[0-9A-Za-z][0-9A-Za-z._+\-]{0,63}")
REPOSITORY = re.compile(r"[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}")
FINGERPRINT = re.compile(r"[a-f0-9]{64}")


def release_values(tag, version_name, minimum, signer):
    match = TAG.fullmatch(tag)
    if not match or not 1 <= (version := int(match[1])) <= MAX_VERSION_CODE:
        raise ValueError("tag must be android-v followed by an Android version code")
    if not VERSION_NAME.fullmatch(version_name):
        raise ValueError("invalid version name")
    minimum = version if minimum is None else minimum
    if not 1 <= minimum <= version:
        raise ValueError("minimum version code must be positive and at most the release version")
    signer = signer.lower().replace(":", "")
    if not FINGERPRINT.fullmatch(signer):
        raise ValueError("signer must be the release certificate's SHA-256 fingerprint")
    return version, minimum, signer


def https_base(value):
    uri = urlsplit(value)
    if (uri.scheme != "https" or not uri.hostname or uri.username is not None
            or "?" in value or "#" in value or "\\" in value or not value.isascii()
            or any(ord(char) <= 32 or ord(char) == 127 for char in value)
            or any(segment in {".", ".."} for segment in uri.path.split("/"))):
        raise ValueError("download base must be an HTTPS directory URL")
    if uri.port is not None and not 1 <= uri.port <= 65535:
        raise ValueError("invalid HTTPS port")
    return value.rstrip("/")


def run_tool(command):
    result = subprocess.run(command, capture_output=True, encoding="utf-8", errors="replace", check=False)
    if result.returncode:
        raise ValueError(f"{Path(command[0]).name} rejected the APK: {result.stderr.strip()}")
    return result.stdout


def verify_apk(apk, sdk, version, version_name, signer):
    sdk_tools = sdk / "build-tools" / BUILD_TOOLS
    # Calling the Java archive directly avoids shell interpretation of Windows batch arguments.
    jars = sdk_tools / "lib" / "apksigner.jar"
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")) if java_home else "java"
    certs = run_tool([java, "-jar", str(jars), "verify", "--print-certs", str(apk)])
    digests = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest:\s*([a-fA-F0-9]{64})\s*$", certs, re.M)
    if [value.lower() for value in digests] != [signer]:
        raise ValueError("APK signer does not match the expected release certificate")
    aapt2 = sdk_tools / ("aapt2.exe" if os.name == "nt" else "aapt2")
    badging = run_tool([str(aapt2), "dump", "badging", str(apk)])
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", badging, re.M)
    if not package or package.groups() != (PACKAGE, str(version), version_name):
        raise ValueError("APK package/version does not match the requested release")
    if re.search(r"^application-debuggable\s*$", badging, re.M):
        raise ValueError("a debuggable APK cannot be published")
    with ZipFile(apk) as archive:
        libraries = [item for item in archive.namelist() if item.startswith("lib/") and item.endswith(".so")]
        library = "lib/arm64-v8a/libneosteam.so"
        if libraries.count(library) != 1 or any(not item.startswith("lib/arm64-v8a/") for item in libraries):
            raise ValueError("expected only ARM64 native libraries with one libneosteam.so")
        with archive.open(library) as binary:
            elf = binary.read(20)
        if len(elf) != 20 or elf[:6] != b"\x7fELF\x02\x01" or int.from_bytes(elf[18:20], "little") != 183:
            raise ValueError("libneosteam.so is not little-endian ELF64 AArch64")


def launcher(manifest, apk_name):
    label = escape(manifest["version_name"])
    apk_url = escape(manifest["apk_url"], quote=True)
    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>NeoSteam for Android</title>
  <style>
    :root {{ color-scheme: light dark; font-family: system-ui, sans-serif; line-height: 1.55; }}
    body {{ margin: 8vh auto; padding: 0 1.25rem; max-width: 38rem; }}
    h1 {{ line-height: 1.1; }}
    code {{ overflow-wrap: anywhere; }}
    a {{ text-underline-offset: .17em; }}
  </style>
</head>
<body>
  <main>
    <h1>NeoSteam for Android</h1>
    <p>Version {label} · ARM64 · {manifest["bytes"]:,} bytes</p>
    <p><a href="{apk_url}">Download the signed APK</a></p>
    <p>SHA-256 <code>{manifest["sha256"]}</code></p>
    <p><a href="{apk_url}.sha256">Download the checksum for {apk_name}</a></p>
    <p><a href="./latest.json">Release metadata</a></p>
  </main>
</body>
</html>
"""


def stage_release(apk, sdk, repo, apk_base_url, tag, version_name, minimum, signer, output):
    version, minimum, signer = release_values(tag, version_name, minimum, signer)
    if (repo is None) == (apk_base_url is None):
        raise ValueError("supply one release destination")
    apk_name = f"NeoSteam-android-{version}-arm64.apk"
    if repo is not None:
        if not REPOSITORY.fullmatch(repo) or repo.split("/")[1] in {".", ".."}:
            raise ValueError("repository must be an owner/name on github.com")
        apk_url = f"https://github.com/{repo}/releases/download/{tag}/{apk_name}"
        asset_dir = Path("github")
    else:
        apk_url = f"{https_base(apk_base_url)}/releases/{tag}/{apk_name}"
        asset_dir = Path("website", "releases", tag)

    if output.exists() or output.is_symlink():
        raise ValueError("output directory already exists")
    if not apk.is_file() or not 1 <= apk.stat().st_size <= MAX_APK_BYTES:
        raise ValueError("APK is missing or outside the supported size range")
    output.parent.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(prefix=".neosteam-release-", dir=output.parent) as temp:
        stage = Path(temp) / "staged"
        assets = stage / asset_dir
        website = stage / "website"
        assets.mkdir(parents=True)
        website.mkdir(exist_ok=True)
        staged_apk = assets / apk_name
        shutil.copyfile(apk, staged_apk)
        verify_apk(staged_apk, sdk, version, version_name, signer)
        with staged_apk.open("rb") as file:
            digest = sha256()
            while chunk := file.read(1024**2):
                digest.update(chunk)
        bytes_count = staged_apk.stat().st_size
        if not 1 <= bytes_count <= MAX_APK_BYTES:
            raise ValueError("copied APK is outside the supported size range")
        manifest = {
            "schema_version": 1,
            "version_code": version,
            "version_name": version_name,
            "minimum_version_code": minimum,
            "apk_url": apk_url,
            "sha256": digest.hexdigest(),
            "bytes": bytes_count,
        }
        checksum = f'{manifest["sha256"]}  {apk_name}\n'.encode("ascii")
        metadata = (json.dumps(manifest, indent=2) + "\n").encode("utf-8")
        (assets / f"{apk_name}.sha256").write_bytes(checksum)
        (website / "latest.json").write_bytes(metadata)
        (website / "latest.apk.sha256").write_bytes(checksum)
        (website / "index.html").write_bytes(launcher(manifest, apk_name).encode("utf-8"))
        if repo is not None:
            (assets / "latest.json").write_bytes(metadata)
        if output.exists() or output.is_symlink():
            raise ValueError("output directory appeared while staging")
        stage.rename(output)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True, help="signed release APK")
    dest = parser.add_mutually_exclusive_group(required=True)
    dest.add_argument("--repo", help="GitHub owner/repository for tagged release assets")
    dest.add_argument("--apk-base-url", help="HTTPS base directory for direct static hosting")
    parser.add_argument("--tag", required=True, help="release tag android-v<versionCode>")
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--minimum-version-code", type=int, help="default: the release version code")
    parser.add_argument("--signer-sha256", required=True, help="expected app-signing certificate fingerprint")
    parser.add_argument("--output", type=Path, required=True, help="fresh output directory")
    parser.add_argument("--sdk", type=Path, help="Android SDK root")
    args = parser.parse_args()
    sdk = args.sdk or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        parser.error("set ANDROID_HOME or pass --sdk")
    try:
        manifest = stage_release(args.apk.resolve(), Path(sdk).resolve(), args.repo, args.apk_base_url, args.tag,
                                 args.version_name, args.minimum_version_code, args.signer_sha256, args.output)
    except (ValueError, OSError, BadZipFile) as error:
        print(f"Release staging failed: {error}", file=sys.stderr)
        return 1
    print(f'Verified release {manifest["version_code"]}, SHA-256 {manifest["sha256"]}')
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
