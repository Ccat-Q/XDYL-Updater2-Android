#!/usr/bin/env python3
"""Anonymous, read-only availability diagnostics, run only by GitHub Actions."""
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request

endpoints = {
    'announcements': 'https://login.lanternwaves.fun/announcements',
    'resources': 'http://api.lanternwaves.fun:5551/mods/mods.json',
    'android-release': 'https://api.github.com/repos/Ccat-Q/XDYL-Updater2-Android/releases/latest',
}
allowed = {(urllib.parse.urlparse(url).scheme, urllib.parse.urlparse(url).netloc) for url in endpoints.values()}

class RestrictedRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        target = urllib.parse.urlparse(newurl)
        if (target.scheme, target.netloc) not in allowed or target.username or target.password:
            raise urllib.error.URLError('Redirect outside read-only endpoint allowlist')
        return super().redirect_request(req, fp, code, msg, headers, newurl)

opener = urllib.request.build_opener(RestrictedRedirect())
results = {}
for name, url in endpoints.items():
    try:
        request = urllib.request.Request(url, headers={'Accept': 'application/json', 'User-Agent': 'StarWave-Android-CI-readonly'})
        with opener.open(request, timeout=12) as response:
            content = response.read(2_000_001)
            if len(content) > 2_000_000:
                raise ValueError('Response exceeds diagnostic limit')
            value = json.loads(content)
            results[name] = {'status': response.status, 'json_type': type(value).__name__, 'keys': sorted(value.keys()) if isinstance(value, dict) else []}
    except urllib.error.HTTPError as error:
        results[name] = {'status': error.code, 'available': False}
    except (urllib.error.URLError, TimeoutError, ValueError) as error:
        results[name] = {'available': False, 'error_type': type(error).__name__}
output = Path('artifacts/read-only-endpoints.json')
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(results, indent=2) + '\n')
print('Anonymous endpoint diagnostics saved; no writes, credentials or response bodies recorded.')
