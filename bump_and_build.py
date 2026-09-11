"""
Auto-Versioning and Build Tool for Codex-ChatGPT Mobile APK.
Automatically increments version (1.0 -> 1.01 -> 1.02...),
compiles APK with gradle, calculates hashes, and publishes version metadata for OTA updates.
"""

import os
import sys
import json
import time
import hashlib
import subprocess

PROJECT_DIR = r"C:\Users\Admin\Desktop\ChatGPT-Android-Studio"
VERSION_FILE = os.path.join(PROJECT_DIR, "version.json")
OUTPUT_APK_SRC = os.path.join(PROJECT_DIR, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
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
    notes = sys.argv[1] if len(sys.argv) > 1 else "Actualización automática de funcionalidades y correcciones."
    
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

    print("Executing Gradle assembleDebug...")
    res = subprocess.run([gradlew_cmd, "assembleDebug", "--no-daemon"], cwd=PROJECT_DIR, env=env)
    if res.returncode != 0:
        print("ERROR: Gradle build failed!", file=sys.stderr)
        sys.exit(res.returncode)

    if not os.path.exists(OUTPUT_APK_SRC):
        print(f"ERROR: Output APK not found at {OUTPUT_APK_SRC}", file=sys.stderr)
        sys.exit(1)

    # 3. Copy to Target APK location
    import shutil
    shutil.copy2(OUTPUT_APK_SRC, TARGET_APK)

    file_size = os.path.getsize(TARGET_APK)
    sha256 = get_file_sha256(TARGET_APK)

    data["sizeBytes"] = file_size
    data["sha256"] = sha256
    data["apkName"] = os.path.basename(TARGET_APK)

    # Update version.json with final checksum
    with open(VERSION_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)

    # Copy to server directory for instant OTA serving
    with open(SERVER_VERSION_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)

    print("\n[SUCCESS] BUILD COMPLETED!")
    print(f"Version: {new_name} (Code: {new_code})")
    print(f"Target APK: {TARGET_APK}")
    print(f"Size: {file_size} bytes")
    print(f"SHA-256: {sha256}")
    print(f"OTA Metadata published to: {SERVER_VERSION_FILE}")


if __name__ == "__main__":
    main()
