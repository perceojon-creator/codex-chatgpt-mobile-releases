"""
File Watcher & Auto-Build Service for Codex-ChatGPT Mobile APK.
Watches app source files for modifications, automatically increments version code/name,
recompiles the APK, and notifies the OTA server so devices receive the update immediately.
"""

import os
import sys
import time
import subprocess

WATCH_DIR = r"C:\Users\Admin\Desktop\ChatGPT-Android-Studio\app\src\main"
BUMP_SCRIPT = r"C:\Users\Admin\Desktop\ChatGPT-Android-Studio\bump_and_build.py"


def get_dir_mtime(directory: str) -> float:
    max_mtime = 0.0
    for root, _, files in os.walk(directory):
        for f in files:
            if f.endswith((".kt", ".xml", ".gradle", ".kts")):
                path = os.path.join(root, f)
                try:
                    mt = os.path.getmtime(path)
                    if mt > max_mtime:
                        max_mtime = mt
                except OSError:
                    pass
    return max_mtime


def main():
    print("=" * 65)
    print("🚀 Auto-Build & Versioning Watcher Service Started")
    print(f"Watching directory: {WATCH_DIR}")
    print("When you save a change, APK will auto-compile & increment version.")
    print("=" * 65)

    last_mtime = get_dir_mtime(WATCH_DIR)

    while True:
        try:
            time.sleep(2.0)
            current_mtime = get_dir_mtime(WATCH_DIR)

            if current_mtime > last_mtime:
                print("\n[DETECTED CHANGE] File modification detected in src/main!")
                print("Debouncing 3 seconds for ongoing edits...")
                time.sleep(3.0)

                last_mtime = get_dir_mtime(WATCH_DIR)

                print("Triggering auto-bump and build...")
                subprocess.run([sys.executable, BUMP_SCRIPT, "Actualización automática por cambio de código en vivo"])

                print("\n[READY] Waiting for next modification...")

        except KeyboardInterrupt:
            print("\nWatcher stopped.")
            break
        except Exception as e:
            print(f"Watcher error: {e}")
            time.sleep(2.0)


if __name__ == "__main__":
    main()
