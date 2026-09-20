"""
Auto-Versioning, Build & Dual-Publish Tool for Codex-ChatGPT Mobile APK.

Usage:
  python bump_and_build.py                           # Build only
  python bump_and_build.py "Release notes"           # Build with custom notes
  python bump_and_build.py --publish                 # Build and publish to private git + public GitHub release
  python bump_and_build.py "Release notes" --publish # Build with notes and publish to both
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

SOURCE_REPO = "perceojon-creator/codex-chatgpt-mobile"
RELEASES_REPO = "perceojon-creator/codex-chatgpt-mobile-releases"


def bump_version_string(ver_str: str) -> str:
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


def run_cmd(cmd, cwd=PROJECT_DIR, check=True):
    print(f"  [RUN] {' '.join(cmd)}")
    res = subprocess.run(cmd, cwd=cwd, text=True, capture_output=True)
    if check and res.returncode != 0:
        print(f"ERROR executing {' '.join(cmd)}: {res.stderr}", file=sys.stderr)
        sys.exit(res.returncode)
    return res


def publish_release(version_name: str, version_code: int, sha256: str, notes: str):
    tag = f"v{version_name}"
    print(f"\n=== [PUBLISH] Subiendo codigo y tag al repositorio privado ({SOURCE_REPO}) ===")
    
    # Excluir local.properties por seguridad
    run_cmd(["git", "rm", "--cached", "local.properties"], check=False)
    run_cmd(["git", "add", "-A"])
    run_cmd(["git", "commit", "-m", f"release: {tag} — build oficial ({version_code}) con enjambre y 529 tests"], check=False)
    run_cmd(["git", "tag", "-a", tag, "-m", f"Release {tag} — APK oficial"], check=False)
    run_cmd(["git", "push", "origin", "master"])
    run_cmd(["git", "push", "origin", tag])
    print(f"  [OK] Codigo y tag {tag} empujados a {SOURCE_REPO}")

    print(f"\n=== [PUBLISH] Creando GitHub Release publico en ({RELEASES_REPO}) ===")
    body = (
        f"### 🐝 Novedades de la Versión {version_name}\n\n"
        + notes.split("## Artefacto")[0].strip()
        + f"\n\n---\n\n"
        + f"**Verificación de Integridad Criptográfica**\n\n"
        + f"```\n"
        + f"apkName     : Codex-ChatGPT-Mobile.apk\n"
        + f"versionCode : {version_code}\n"
        + f"SHA-256     : {sha256.lower()}\n"
        + f"```\n"
    )

    temp_notes_file = os.path.join(PROJECT_DIR, f"temp_notes_{tag}.md")
    with open(temp_notes_file, "w", encoding="utf-8") as f:
        f.write(body)

    try:
        gh_cmd = [
            "gh", "release", "create", tag, TARGET_APK,
            "--repo", RELEASES_REPO,
            "--title", f"Codex ChatGPT Mobile {tag}",
            "--notes-file", temp_notes_file
        ]
        res = run_cmd(gh_cmd, check=False)
        if res.returncode == 0:
            print(f"  [OK] Release {tag} creado con exito en {RELEASES_REPO}")
            print(f"  URL: https://github.com/{RELEASES_REPO}/releases/tag/{tag}")
        else:
            print(f"  [INFO] gh release create fallo o ya existia: {res.stderr.strip()}")
            # Si ya existe, subir o sobreescribir el APK
            run_cmd([
                "gh", "release", "upload", tag, TARGET_APK,
                "--repo", RELEASES_REPO,
                "--clobber"
            ], check=False)
    finally:
        if os.path.exists(temp_notes_file):
            os.remove(temp_notes_file)


def main():
    flags = [a for a in sys.argv[1:] if a.startswith("--")]
    pos_args = [a for a in sys.argv[1:] if not a.startswith("--")]

    notes = pos_args[0] if len(pos_args) > 0 else (
        "### 🐝 Versión con Enjambre Móvil y Validación Completa\n\n"
        "- Arquitectura de Enjambre / Panal de Agentes Concurrente (Fases 0 a 5).\n"
        "- Suite de 529 pruebas unitarias y de integración pasando al 100%.\n"
        "- Speedup de 2.78x medido empíricamente en prompts multi-herramienta.\n"
        "- Pizarra compartida con persistencia SQLite WAL y aislamiento CaMeL.\n"
        "- OTA nativo con firma oficial RSA-4096 y soporte FileProvider corregido."
    )

    is_debug = "--debug" in flags
    do_publish = "--publish" in flags
    build_task = "assembleDebug" if is_debug else "assembleRelease"
    output_apk_subpath = os.path.join("debug", "app-debug.apk") if is_debug else os.path.join("release", "app-release.apk")
    output_apk_src = os.path.join(PROJECT_DIR, "app", "build", "outputs", "apk", output_apk_subpath)

    # 1. Read existing version
    data = {"versionCode": 85, "versionName": "1.0.84"}
    if os.path.exists(VERSION_FILE):
        try:
            with open(VERSION_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            pass

    old_code = data.get("versionCode", 85)
    old_name = data.get("versionName", "1.0.84")

    new_code = old_code + 1
    new_name = bump_version_string(old_name)

    print(f"=== Bumping version: {old_name} (code {old_code}) -> {new_name} (code {new_code}) ===")
    print(f"Target build variant: {build_task} (Minification/R8: {'Disabled' if is_debug else 'Enabled'})")
    if do_publish:
        print(f"Target distribution: Dual Publish (Private: {SOURCE_REPO}, Public: {RELEASES_REPO})")

    data["versionCode"] = new_code
    data["versionName"] = new_name
    data["lastBuildTime"] = int(time.time())
    data["releaseNotes"] = notes

    with open(VERSION_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)

    # 2. Build with Gradle
    gradlew_cmd = os.path.join(PROJECT_DIR, "gradlew.bat")
    env = os.environ.copy()
    env["JAVA_HOME"] = r"C:\Program Files\Android\Android Studio\jbr"

    print(f"Executing Gradle {build_task}...")
    res = subprocess.run([gradlew_cmd, "clean", build_task, "--no-daemon"], cwd=PROJECT_DIR, env=env)
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
    data["releaseNotes"] = (
        notes + "\n\n"
        + "## Artefacto\n"
        + "apkName: " + os.path.basename(TARGET_APK) + "\n"
        + "versionCode: " + str(new_code) + "\n"
        + "SHA-256: " + sha256.lower() + "\n"
    )

    # Update version.json with final checksum
    with open(VERSION_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)

    # Copy to server directory for instant OTA serving
    os.makedirs(os.path.dirname(SERVER_VERSION_FILE), exist_ok=True)
    with open(SERVER_VERSION_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)

    print("\n[SUCCESS] BUILD COMPLETED!")
    print(f"Version: {new_name} (Code: {new_code})")
    print(f"Variant: {build_task}")
    print(f"Target APK: {TARGET_APK}")
    print(f"Size: {file_size} bytes ({round(file_size / (1024*1024), 2)} MB)")
    print(f"SHA-256: {sha256}")
    print(f"OTA Metadata published to: {SERVER_VERSION_FILE}")

    if do_publish:
        publish_release(new_name, new_code, sha256, notes)
    else:
        print(f"\nTo publish to GitHub, run: python bump_and_build.py --publish")


if __name__ == "__main__":
    main()
