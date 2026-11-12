"""Generate an AltStore classic source (apps.json) for a GlassBridge release.

usage: python3 -I scripts/gen-altsource.py <ipa> <version> <tag> <out.json> [repo]
The download URL is the GitHub release asset; AltStore re-signs the unsigned IPA on the phone.
"""
import json, os, sys, datetime

ipa, version, tag, out = sys.argv[1:5]
repo = sys.argv[5] if len(sys.argv) > 5 else "ooah23/glass-ble-bridge"
url = f"https://github.com/{repo}/releases/download/{tag}/{os.path.basename(ipa)}"
date = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
size = os.path.getsize(ipa)
desc = "Bluetooth LE bridge between this iPhone and a Google Glass XE: cards and now-playing to Glass, heartbeat link status."
ver = {"version": version, "date": date, "localizedDescription": f"GlassBridge {version}",
       "downloadURL": url, "size": size, "minOSVersion": "15.0"}
app = {"name": "GlassBridge", "bundleIdentifier": "dev.glassbridge.app", "developerName": "ooah23",
       "subtitle": "iPhone to Google Glass BLE bridge", "localizedDescription": desc,
       "version": version, "versionDate": date, "downloadURL": url, "size": size, "minOSVersion": "15.0",
       "versions": [ver]}
src = {"name": "GlassBridge", "identifier": "dev.glassbridge.source", "apps": [app], "news": []}
with open(out, "w") as f:
    json.dump(src, f, indent=2)
    f.write("\n")
print("wrote", out, version, size)
