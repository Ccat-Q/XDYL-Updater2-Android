#!/usr/bin/env python3
"""Create the long-lived signing identity without building the app or exposing secrets."""
from pathlib import Path
import os
import secrets
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
directory = root / ".local/signing"
directory.mkdir(parents=True, exist_ok=True)
directory.chmod(0o700)
keystore = directory / "starwave-release.keystore"
properties = directory / "signing.properties"
if keystore.exists() or properties.exists():
    if not keystore.exists() or not properties.exists():
        raise SystemExit("Incomplete existing signing configuration. Restore it; do not generate a replacement key.")
    print("Existing signing identity preserved.")
    raise SystemExit(0)
keytool = shutil.which("keytool")
if not keytool:
    raise SystemExit("keytool is unavailable. Generate the signing identity on a trusted machine; no tool is downloaded.")
password = secrets.token_urlsafe(40)
env = dict(os.environ, STARWAVE_SIGNING_PASSWORD=password)
subprocess.run([
    keytool, "-genkeypair", "-keystore", str(keystore), "-storetype", "PKCS12",
    "-alias", "starwave", "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
    "-storepass:env", "STARWAVE_SIGNING_PASSWORD", "-keypass:env", "STARWAVE_SIGNING_PASSWORD",
    "-dname", "CN=StarWave Android, O=Ccat-Q"
], env=env, check=True, capture_output=True)
keystore.chmod(0o600)
with properties.open("x") as output:
    output.write("storeFile=.local/signing/starwave-release.keystore\nkeyAlias=starwave\n")
    output.write("storePassword=" + password + "\nkeyPassword=" + password + "\n")
properties.chmod(0o600)
print("New long-lived signing identity created in .local/signing. Back up both files; no secrets printed.")
