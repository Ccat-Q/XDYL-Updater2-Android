#!/usr/bin/env python3
"""Check project structure and parse configuration without invoking a compiler."""
from pathlib import Path
import ast
import re
import subprocess
import tomllib
import xml.etree.ElementTree as ET
import yaml

root = Path(__file__).resolve().parents[1]

def require(condition, message):
    if not condition:
        raise SystemExit(message)

for file in (root / 'tools').glob('*.py'):
    ast.parse(file.read_text(), filename=str(file))
for file in (root / 'app/src').rglob('*.xml'):
    ET.parse(file)
with (root / 'gradle/libs.versions.toml').open('rb') as stream:
    versions = tomllib.load(stream)['versions']
require(versions['agp'] == '9.0.1', 'Unexpected AGP version')
require(versions['kotlin'] == '2.2.10', 'Unexpected Kotlin version')
manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot()
ns = '{http://schemas.android.com/apk/res/android}'
application = manifest.find('application')
require(application is not None and application.get(ns + 'allowBackup') == 'false', 'Account data must not be backed up')
require(not any('MANAGE_EXTERNAL_STORAGE' in item.get(ns + 'name', '') for item in manifest.findall('uses-permission')), 'Unexpected broad storage permission')
for element in application:
    name = element.get(ns + 'name', '')
    if name.startswith('.'):
        parts = name[1:].split('.')
        directory = root / 'app/src/main/java/com/ccatq/xdylupdater2' / '/'.join(parts[:-1])
        require(any(re.search(r'\bclass\s+' + re.escape(parts[-1]) + r'\b', file.read_text()) for file in directory.glob('*.kt')), f'Manifest class is missing: {name}')
for legacy in ['StarWave', 'StarWaveTests', 'project.yml', '.github/workflows/ios.yml']:
    require(not (root / legacy).exists(), f'Legacy platform file remains: {legacy}')
require(not list(root.glob('*.exe')), 'Desktop executable remains in Android tree')
require((root / 'app/src/main/res/mipmap-nodpi/ic_launcher.png').is_file(), 'App icon missing')
for file in (root / '.github').rglob('*.yml'):
    yaml.safe_load(file.read_text())
workflow = (root / '.github/workflows/android.yml').read_text()
for script in re.findall(r'python3 (tools/[a-z_]+\.py)', workflow):
    require((root / script).exists(), f'Workflow script is missing: {script}')
require('--no-build-cache --no-configuration-cache' in workflow, 'Signed build must not cache signing outputs')
require('api: [26, 36]' in workflow, 'Minimum and target SDK device coverage missing')
require('apksigner' in workflow, 'Release signature verification missing')
for directory in ['.local/sdk', '.local/jdk', '.local/gradle']:
    require(not (root / directory).exists(), f'Local build tool remains: {directory}')
result = subprocess.run(['git', 'diff', '--check'], cwd=root, capture_output=True, text=True)
require(result.returncode == 0, result.stdout + result.stderr)
print('Static checks passed: Python, XML, TOML, YAML, Android structure and CI wiring. No Android compilation or tests executed.')
