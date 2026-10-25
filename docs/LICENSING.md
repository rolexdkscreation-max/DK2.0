# DK AI — Access-key licensing (Part 13)

Admin: **धीरज कुमार सिंह** (`AssistantBrand.OWNER_NAME`). Only the admin issues, blocks and unblocks keys.

## Default: licensing not configured
If `app/src/main/assets/dk_license.json` does not exist (the normal case — it is git-ignored),
the app builds and runs exactly as before. Settings → Profile → Access key shows
"लाइसेंस सेट नहीं है (licensing not configured)". Nothing is blocked.

## Turning it on
1. Run your own HTTPS backend (any server, Cloud Function, Worker...).
2. Copy `docs/dk_license.example.json` to `app/src/main/assets/dk_license.json` and set `endpoint`
   (must start with `https://`). **Never commit this file.** It is listed in `.gitignore`.
3. Build the APK. From now on DK Live and commands need an active key.

## Backend contract
The app sends `POST <endpoint>` with JSON:

```json
{ "action": "activate" | "check", "key": "DK-ABCD-1234", "device": "<sha-256 hash, 32 hex chars>" }
```

- `activate` — validate the key and bind it to this device (the raw Android ID is never sent, only a hash).
- `check` — re-check a remembered key (the app does this at most once every 3 hours, on Home open).

Reply (any HTTP status; 4xx without JSON counts as "not active"):

```json
{ "ok": true, "active": true, "plan": "monthly", "expires_at": 1767225600000, "blocked": false, "message": "" }
```

- `expires_at` — milliseconds since epoch; `0` or plan `lifetime` = never expires.
- `blocked: true` — the key stops working immediately; a running DK Live session is stopped on next Home open.
- `message` — optional text shown to the user when activation fails (max 200 chars).

## Behaviour
| Situation | Result |
|---|---|
| No config file | everything works ("licensing not configured") |
| Config present, no key | DK Live and commands refuse: "Access key चालू नहीं है, कृपया एडमिन धीरज कुमार सिंह से संपर्क करें" |
| Key active | works |
| Expired / blocked | refuses with the matching message |
| Network error during check | **fail open** — the last known state is kept |

The key is stored only in private app storage (`dk_access_key` SharedPreferences) and is never written to the log.

## तीसरे पक्ष का डेटा (Third-party data)

- **MediaPipe canonical face model** (`app/src/main/java/com/dk/assistant/FaceMeshData.kt`) - इंसानी चेहरे के 3D ढाँचे के बिंदु, त्रिकोण और रेखाएँ।
  स्रोत: https://github.com/google-ai-edge/mediapipe (`mediapipe/modules/face_geometry/data/canonical_face_model.obj`, `mediapipe/python/solutions/face_mesh_connections.py`)।
  Copyright The MediaPipe Authors. Apache License 2.0 - https://www.apache.org/licenses/LICENSE-2.0
