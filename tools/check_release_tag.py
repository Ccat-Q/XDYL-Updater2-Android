#!/usr/bin/env python3
from pathlib import Path
import os
import re

text = Path("app/build.gradle.kts").read_text()
version = re.search(r'versionName = "([^"]+)"', text).group(1)
ref = os.environ.get("GITHUB_REF", "")
if ref.startswith("refs/tags/") and ref != "refs/tags/v" + version:
    raise SystemExit("Release tag must match the tracked Android versionName; increment versionCode for future updates.")
print("Release version configuration checked.")
