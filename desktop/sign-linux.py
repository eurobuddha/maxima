#!/usr/bin/env python3
"""Sign a versioned Linux installer using an existing OpenPGP identity, then verify it.
No key generation: CI supplies LINUX_SIGNING_KEY/FINGERPRINT/PASSPHRASE.
The detached signature and public key accompany the installer.
"""
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


def sign(installer: Path, version: str) -> None:
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("Expected release version X.Y.Z")
    if not installer.is_file() or installer.suffix != ".deb" or not re.search(r"(?<![0-9.])" + re.escape(version) + r"(?![0-9.])", installer.name):
        raise ValueError("Expected a versioned .deb installer")
    fingerprint = os.environ.get("LINUX_SIGNING_FINGERPRINT", "").upper()
    key = os.environ.get("LINUX_SIGNING_KEY", "")
    if not key or not re.fullmatch(r"[A-F0-9]{40}|[A-F0-9]{64}", fingerprint):
        raise ValueError("Linux signing key and full fingerprint are required")
    signature = installer.with_name(installer.name + ".asc")
    public_key = installer.parent / "Parlons-signing-key.asc"
    if signature.exists() or public_key.exists():
        raise ValueError("Signing outputs already exist")
    with tempfile.TemporaryDirectory(prefix="parlons-release-sign-") as directory:
        env = dict(os.environ, GNUPGHOME=directory)
        def gpg(*args, data=None):
            return subprocess.run(["gpg", "--batch", *args], input=data, env=env,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout
        try:
            gpg("--import", data=key.encode())
            # Explicit fingerprint selects the configured identity, even if the import contains several keys.
            exported = gpg("--armor", "--export", fingerprint)
            if not exported:
                raise ValueError("Configured fingerprint is not present in the imported key")
            gpg("--pinentry-mode", "loopback", "--passphrase-fd", "0", "--local-user", fingerprint,
                "--armor", "--detach-sign", "--output", str(signature), str(installer),
                data=os.environ.get("LINUX_SIGNING_PASSPHRASE", "").encode())
            # Verify against only the selected public identity, not the import's entire keyring.
            with tempfile.TemporaryDirectory(prefix="parlons-release-verify-") as verify_dir:
                subprocess.run(["gpg", "--batch", "--homedir", verify_dir, "--import"], input=exported,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
                subprocess.run(["gpg", "--batch", "--homedir", verify_dir, "--verify", str(signature), str(installer)],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
            public_key.write_bytes(exported)
        except Exception:
            signature.unlink(missing_ok=True)
            public_key.unlink(missing_ok=True)
            raise
        finally:
            subprocess.run(["gpgconf", "--homedir", directory, "--kill", "gpg-agent"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    print(f"Verified Linux signature: {installer.name}; signing identity {fingerprint}")


if __name__ == "__main__":
    try:
        sign(Path(sys.argv[1]).resolve(), sys.argv[2])
    except (ValueError, IndexError, subprocess.CalledProcessError) as error:
        # Never echo secret input or a passphrase in process arguments.
        print(f"Linux signing failed: {error}", file=sys.stderr)
        sys.exit(1)
