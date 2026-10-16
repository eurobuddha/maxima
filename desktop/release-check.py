#!/usr/bin/env python3
"""Fail before packaging if the release cannot meet the signed-build requirement."""
import os
from pathlib import Path
import re
import sys

REQUIRED = {
    "mac": ("MACOS_CERT_P12", "MACOS_CERT_PASSWORD", "MACOS_SIGN_IDENTITY", "APPLE_ID", "APPLE_APP_PASSWORD", "APPLE_TEAM_ID"),
    "win": ("WINDOWS_CERT_PFX", "WINDOWS_CERT_PASSWORD"),
    "linux": ("LINUX_SIGNING_KEY", "LINUX_SIGNING_FINGERPRINT"),
}


def check(root: Path, env) -> str:
    platform = env.get("RELEASE_PLATFORM", "")
    if platform not in REQUIRED:
        raise ValueError("Unknown release platform")
    missing = [key for key in REQUIRED[platform] if not env.get(key, "").strip()]
    if missing:
        raise ValueError("Required signing configuration is missing: " + ", ".join(missing))
    versions = re.findall(r"^version = '(\d+\.\d+\.\d+)'$", (root / "desktop/build.gradle").read_text(), re.M)
    if len(versions) != 1:
        raise ValueError("Expected one desktop release version")
    version = versions[0]
    main = (root / "desktop/src/main/java/com/eurobuddha/maxima/desktop/DesktopMain.java").read_text()
    if not re.search(r'APP_VERSION\s*=\s*"' + re.escape(version) + '"', main):
        raise ValueError("Desktop app metadata and package version differ")
    ref = env.get("GITHUB_REF", "")
    if ref.startswith("refs/tags/") and ref != "refs/tags/desktop-v" + version:
        raise ValueError("Release tag does not match the desktop version")
    return version


if __name__ == "__main__":
    try:
        version = check(Path(__file__).resolve().parent.parent, os.environ)
        if os.environ.get("GITHUB_OUTPUT"):
            with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
                stream.write("version=" + version + "\n")
        print("Signing configuration present; desktop release version " + version)
    except ValueError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
