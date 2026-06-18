# WebADB — host-side ADB in the browser

A static web page that connects to an Android device with USB debugging enabled
and runs shell commands against it, **implementing the host side of the ADB
transport protocol directly in the browser over WebUSB**. There is no native
`adb` executable involved and nothing is forwarded to a local ADB server — the
page speaks the wire protocol straight to `adbd` on the device.

```
Browser
  └─ WebUSB
      └─ USB bulk IN/OUT endpoints
          └─ ADB transport protocol → adbd on Android
```

## Files

| File         | Purpose                                                                 |
| ------------ | ----------------------------------------------------------------------- |
| `index.html` | Page markup and instructions.                                           |
| `style.css`  | Styling (respects light/dark `prefers-color-scheme`).                   |
| `adb.js`     | The ADB protocol implementation: USB transport, framing, RSA auth.      |
| `app.js`     | UI glue: connect flow, device info, test-command buttons, log.          |

## How it works

- **USB interface.** Android exposes ADB as a USB interface with class `0xff`,
  subclass `0x42`, protocol `0x01`. The page filters `requestDevice` on exactly
  that descriptor, claims the interface, and grabs its two bulk endpoints.
- **Framing.** Every packet is a 24-byte little-endian header
  (`command, arg0, arg1, data_length, checksum, magic` where
  `magic = command ^ 0xffffffff`) optionally followed by a payload. Several
  logical streams are multiplexed over the one bulk pipe pair via local/remote
  stream ids.
- **Authentication.** On connect the device sends a 20-byte token. The host
  signs it with an RSA-2048 private key (PKCS#1 v1.5 over the token treated as a
  SHA-1 digest). WebCrypto generates and stores the key, but cannot sign a
  pre-computed digest, so the raw `m^d mod n` step is done with `BigInt`. If the
  key is unknown to the device, the host sends its public key in Android's
  `RSAPublicKey` format, which triggers the on-device **"Allow USB debugging?"**
  prompt. The key is persisted in `localStorage` so "always allow" keeps
  working.
- **Commands.** Each test opens a `shell:<command>` stream, collects the
  `WRTE` payloads (acknowledging each with `OKAY`) until the device sends
  `CLSE`, and prints the result.

## Important: the ADB USB interface is exclusive

If a native ADB server, Android Studio, or another browser tab already owns the
interface, the browser cannot claim it. **Run `adb kill-server` first** (and
close Android Studio / other tabs), exactly as Google's Flash Tool instructions
require.

## Browser support

WebUSB is Chromium-only: Chrome, Edge, Brave, and Opera on desktop or Android.
Firefox and Safari are not supported. The page must be served over HTTPS (or
`localhost`); GitHub Pages satisfies this.

## Publishing to GitHub Pages

The workflow at `.github/workflows/pages.yml` deploys this `docs/` folder. One
time, set **Settings → Pages → Source** to **GitHub Actions**. After that, any
push to `main` or `web-adb` that touches `docs/` republishes the site. You can
also trigger it manually from the Actions tab.

To test locally, serve the folder over HTTP from the repo root, e.g.
`python3 -m http.server` and open `http://localhost:8000/docs/`.
