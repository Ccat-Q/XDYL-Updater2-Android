#!/usr/bin/env python3
from pathlib import Path
import base64
import os

names = ["ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD"]
missing = [name for name in names if not os.environ.get(name)]
if missing:
    raise SystemExit("Missing signing Secrets: " + ", ".join(missing) + ". A debug key will never be substituted.")
target = Path(os.environ["RUNNER_TEMP"]) / "starwave-release.keystore"
target.write_bytes(base64.b64decode(os.environ["ANDROID_KEYSTORE_BASE64"], validate=True))
target.chmod(0o600)
print("Signing identity prepared privately on runner.")
