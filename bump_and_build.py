"""
Auto-Versioning and Build Tool for Codex-ChatGPT Mobile APK.
Automatically increments version (1.0 -> 1.01 -> 1.02...),
compiles APK with gradle (defaults to R8-minified assembleRelease, or assembleDebug via --debug),
calculates hashes, and publishes version metadata for OTA updates.
"""

import os
import sys
import json
import time
import hashlib
import subprocess
import shutil

PROJECT_DIR = r"C:\Users\Admin\Desktop\ChatGPT-Android-Studio"
VERSION_FILE = os.path.join(PROJECT_DIR, "version.json")
TARGET_APK = r"C:\Users\Admin\Desktop\Codex-ChatGPT-Mobile.apk"
SERVER_VERSION_FILE = r"C:\Users\Admin\Desktop\ChatGPT-Remote-Control\version_info.json"


def bump_version_string(ver_str: str) -> str:
    """Increment version string e.g. '1.0.0' -> '1.0.1' or '1.0' -> '1.01'."""
    parts = ver_str.split(".")
    try:
        last = int(parts[-1])
        parts[-1] = str(last + 1)
        return ".".join(parts)
    except Exception:
        return ver_str + ".1"


def get_file_sha256(filepath: str) -> str:
    sha = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            sha.update(chunk)
    return sha.hexdigest().upper()


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    notes = args[0] if len(args) > 0 else "Actualización automática de funcionalidades y correcciones."
    is_debug = "--debug" in sys.argv
    build_task = "assembleDebug" if is_debug else "assembleRelease"
    output_apk_subpath = os.path.join("debug", "app-debug.apk") if is_debug else os.path.join("release", "app-release.apk")
    output_apk_src = os.path.join(PROJECT_DIR, "app", "build", "outputs", "apk", output_apk_subpath)

    # 1. Read existing version
    data = {"versionCode": 1, "versionName": "1.0.0"}
    if os.path.exists(VERSION_FILE):
        try:
            with open(VERSION_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            pass

    old_code = data.get("versionCode", 1)
    old_name = data.get("versionName", "1.0.0")

    new_code = old_code + 1
    new_name = bump_version_string(old_name)

    print(f"=== Bumping version: {old_name} (code {old_code}) -> {new_name} (code {new_code}) ===")
    print(f"Target build variant: {build_task} (Minification/R8: {'Disabled' if is_debug else 'Enabled'})")

    data["versionCode"] = new_code
    data["versionName"] = new_name
    data["lastBuildTime"] = int(time.time())
    data["releaseNotes"] = notes

    with open(VERSION_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)

    # 2. Build with Gradle
    gradlew_cmd = os.path.join(PROJECT_DIR, "gradlew.bat")
    env = os.environ.copy()
    env["JAVA_HOME"] = r"C:\Program Files\Android\Android Studio\jbr"

    print(f"Executing Gradle {build_task}...")
    res = subprocess.run([gradlew_cmd, build_task, "--no-daemon"], cwd=PROJECT_DIR, env=env)
    if res.returncode != 0:
        print("ERROR: Gradle build failed!", file=sys.stderr)
        sys.exit(res.returncode)

    if not os.path.exists(output_apk_src):
        print(f"ERROR: Output APK not found at {output_apk_src}", file=sys.stderr)
        sys.exit(1)

    # 3. Copy to Target APK location
    shutil.copy2(output_apk_src, TARGET_APK)

    file_size = os.path.getsize(TARGET_APK)
    sha256 = get_file_sha256(TARGET_APK)

    data["sizeBytes"] = file_size
    data["sha256"] = sha256
    data["apkName"] = os.path.basename(TARGET_APK)
    # Publica los metadatos de integridad EN las notas del release.
    # ReleaseMetadataParser los lee para verificar la descarga antes de instalar.
    data["releaseNotes"] = (
        notes + "\n\n"
        + "## Artefacto\n"
        + "apkName: " + os.path.basename(TARGET_APK) + "\n"
        + "versionCode: " + str(new_code) + "\n"
        + "SHA-256: " + sha256.lower() + "\n"
    )

    # Update version.json with final checksum
    with open(VERSION_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)

    # Copy to server directory for instant OTA serving
    with open(SERVER_VERSION_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)

    print("\n[SUCCESS] BUILD COMPLETED!")
    print(f"Version: {new_name} (Code: {new_code})")
    print(f"Variant: {build_task}")
    print(f"Target APK: {TARGET_APK}")
    print(f"Size: {file_size} bytes ({round(file_size / (1024*1024), 2)} MB)")
    print(f"SHA-256: {sha256}")
    print(f"OTA Metadata published to: {SERVER_VERSION_FILE}")


if __name__ == "__main__":
    main()
