# Inter Profile Sharing — Android 17+ Workaround

A static web page that walks a non-technical user through granting the
[Inter Profile Sharing](https://github.com/VentralDigital/InterProfileSharing)
app the `INTERACT_ACROSS_USERS` permission it needs on Android 17 and newer.

It **implements the host side of the ADB protocol directly in the browser over
WebUSB** — there is no native `adb` executable and no third-party library, and
nothing leaves the user's machine. The page speaks the wire protocol straight to
`adbd` on the connected phone.

```
Browser
  └─ WebUSB
      └─ USB bulk IN/OUT endpoints
          └─ ADB transport protocol → adbd on Android
```

## The wizard

The page is a 6-step wizard:

1. **Before you start** — install the app in each profile, use a Chromium
   browser, and (for developers) `adb kill-server` first.
2. **Enable Developer Mode** on the phone.
3. **Enable USB Debugging** on the phone.
4. **Connect the Phone** — `Connect` triggers the WebUSB device picker and the
   ADB authentication handshake (the phone shows "Allow USB debugging?").
5. **Grant the Permission** — `Grant permission` lists every user profile, finds
   the ones with the app installed, runs `pm grant … INTERACT_ACROSS_USERS` for
   each, and `am force-stop`s the app so it restarts fresh. Results show per
   profile. Can be re-run later when a new profile gets the app.
6. **Clean Up** — turn USB debugging and Developer options back off.

A collapsible "technical log" at the bottom records every command and response
for debugging.

## Files

| File         | Purpose                                                            |
| ------------ | ----------------------------------------------------------------- |
| `index.html` | The wizard markup.                                                 |
| `style.css`  | Styling (respects light/dark `prefers-color-scheme`).             |
| `adb.js`     | The ADB protocol: USB transport, framing, RSA token auth.         |
| `app.js`     | Wizard flow: connect, grant across profiles, results, log.        |

## How the ADB layer works

- **USB interface.** Android exposes ADB as a USB interface with class `0xff`,
  subclass `0x42`, protocol `0x01`. The page filters `requestDevice` on that,
  claims the interface, and grabs its two bulk endpoints.
- **Framing.** Every packet is a 24-byte little-endian header
  (`command, arg0, arg1, data_length, checksum, magic`) optionally followed by a
  payload. Logical streams are multiplexed over the one bulk pipe pair.
- **Authentication.** The device sends a 20-byte token; the host signs it with an
  RSA-2048 key (PKCS#1 v1.5, raw `m^d mod n` via `BigInt` since WebCrypto cannot
  sign a pre-computed digest). An unknown key triggers the on-device prompt. The
  key is persisted in `localStorage` so "always allow" keeps working.

## Browser support & exclusivity

WebUSB is Chromium-only (Chrome, Edge, Brave, Opera) on desktop or Android. The
ADB interface is exclusive — if a native ADB server, Android Studio, or another
tab owns it, the browser cannot claim it, hence the `adb kill-server` step.

## Publishing to GitHub Pages

`.github/workflows/pages.yml` deploys this `docs/` folder. One time, set
**Settings → Pages → Source** to **GitHub Actions**. After that any push to
`main` or `web-adb` touching `docs/` republishes it. To test locally, serve the
repo root over HTTP (`python3 -m http.server`) and open `/docs/`.
