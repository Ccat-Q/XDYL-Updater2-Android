#!/usr/bin/env python3
"""Prepare an ignored file for manual GitHub Secrets configuration; never print its content."""
from pathlib import Path
import base64
import json

root = Path(__file__).resolve().parents[1]
directory = root / ".local/signing"
values = dict(line.split("=", 1) for line in (directory / "signing.properties").read_text().splitlines() if "=" in line)
payload = {
    "ANDROID_KEYSTORE_BASE64": base64.b64encode((root / values["storeFile"]).read_bytes()).decode(),
    "ANDROID_KEYSTORE_PASSWORD": values["storePassword"],
    "ANDROID_KEY_ALIAS": values["keyAlias"],
    "ANDROID_KEY_PASSWORD": values["keyPassword"],
}
target = directory / "github-secrets.json"
target.write_text(json.dumps(payload, indent=2) + "\n")
target.chmod(0o600)
print("Prepared .local/signing/github-secrets.json. Configure Secrets privately; never commit this file.")
