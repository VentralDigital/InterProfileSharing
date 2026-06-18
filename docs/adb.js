// adb.js — a minimal host-side implementation of the ADB transport protocol
// running entirely in the browser over WebUSB. No native `adb` executable and
// no third-party libraries are involved: this speaks the wire protocol directly
// to `adbd` on the Android device.
//
// Protocol reference: platform/system/core/adb/protocol.txt
//
// Each ADB packet is a 24-byte little-endian header optionally followed by a
// payload. Several logical streams are multiplexed over the single USB bulk
// pipe pair using local/remote stream ids.

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

// Command codes. The 32-bit value is the four ASCII letters read little-endian.
export const A_CNXN = 0x4e584e43; // "CNXN" — connect / banner exchange
export const A_AUTH = 0x48545541; // "AUTH" — RSA authentication
export const A_OPEN = 0x4e45504f; // "OPEN" — open a stream to a service
export const A_OKAY = 0x59414b4f; // "OKAY" — stream ready / write ack
export const A_CLSE = 0x45534c43; // "CLSE" — close a stream
export const A_WRTE = 0x45545257; // "WRTE" — stream payload
export const A_STLS = 0x534c5453; // "STLS" — switch to TLS (not supported here)

// Auth sub-types carried in arg0 of an AUTH packet.
const ADB_AUTH_TOKEN = 1;        // device -> host: please sign this token
const ADB_AUTH_SIGNATURE = 2;    // host -> device: here is the signed token
const ADB_AUTH_RSAPUBLICKEY = 3; // host -> device: here is my public key

// Protocol version we advertise. 0x01000001 means "checksums may be skipped".
// We still send correct checksums (every adbd accepts them) and never verify
// incoming ones, so we interoperate with both old and new daemons.
const A_VERSION = 0x01000001;
const MAX_PAYLOAD = 1024 * 1024; // 1 MiB, the modern adb default

// The USB interface Android exposes for ADB.
export const ADB_INTERFACE_FILTER = {
  classCode: 0xff,
  subclassCode: 0x42,
  protocolCode: 0x01,
};

// SHA-1 token length used by the ADB auth handshake.
const TOKEN_SIZE = 20;

// ASN.1 DigestInfo prefix for a SHA-1 hash. ADB signs the auth token as if it
// were already a SHA-1 digest (RSA_sign with NID_sha1), so we prepend this.
const SHA1_DIGEST_INFO = new Uint8Array([
  0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
  0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
]);

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

function concat(a, b) {
  const out = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
}

const textEncoder = new TextEncoder();
const textDecoder = new TextDecoder();

// BigInt <-> byte conversions (RSA works on arbitrary-precision integers).
function bytesBEToBigInt(bytes) {
  let v = 0n;
  for (const b of bytes) v = (v << 8n) | BigInt(b);
  return v;
}

function bigIntToBytesBE(value, length) {
  const out = new Uint8Array(length);
  let v = value;
  for (let i = length - 1; i >= 0; i--) {
    out[i] = Number(v & 0xffn);
    v >>= 8n;
  }
  return out;
}

function bigIntToBytesLE(value, length) {
  const out = new Uint8Array(length);
  let v = value;
  for (let i = 0; i < length; i++) {
    out[i] = Number(v & 0xffn);
    v >>= 8n;
  }
  return out;
}

function base64urlToBytes(s) {
  s = s.replace(/-/g, "+").replace(/_/g, "/");
  const pad = s.length % 4 === 0 ? 0 : 4 - (s.length % 4);
  s += "=".repeat(pad);
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function bytesToBase64(bytes) {
  let bin = "";
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
}

// Modular exponentiation: base^exp mod mod, all BigInt.
function modPow(base, exp, mod) {
  let result = 1n;
  base %= mod;
  while (exp > 0n) {
    if (exp & 1n) result = (result * base) % mod;
    exp >>= 1n;
    base = (base * base) % mod;
  }
  return result;
}

// Modular inverse via the extended Euclidean algorithm: a^-1 mod m.
function modInverse(a, m) {
  let [oldR, r] = [a % m, m];
  let [oldS, s] = [1n, 0n];
  while (r !== 0n) {
    const q = oldR / r;
    [oldR, r] = [r, oldR - q * r];
    [oldS, s] = [s, oldS - q * s];
  }
  return ((oldS % m) + m) % m;
}

// ---------------------------------------------------------------------------
// AdbCrypto — RSA keypair plus ADB-specific signing and public key encoding.
// ---------------------------------------------------------------------------

const STORAGE_KEY = "webadb-rsa-jwk";

export class AdbCrypto {
  constructor(n, e, d) {
    this.n = n; // modulus (BigInt)
    this.e = e; // public exponent (BigInt)
    this.d = d; // private exponent (BigInt)
  }

  // Load a persisted key from localStorage, or generate and persist a new one.
  // Persisting matters: it lets the device's "Always allow from this computer"
  // checkbox keep working across page reloads.
  static async load() {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored) {
      try {
        return AdbCrypto.fromJwk(JSON.parse(stored));
      } catch (e) {
        console.warn("Stored ADB key was unreadable, generating a new one.", e);
      }
    }
    const jwk = await AdbCrypto.generateJwk();
    localStorage.setItem(STORAGE_KEY, JSON.stringify(jwk));
    return AdbCrypto.fromJwk(jwk);
  }

  static async generateJwk() {
    const pair = await crypto.subtle.generateKey(
      {
        name: "RSASSA-PKCS1-v1_5",
        modulusLength: 2048,
        publicExponent: new Uint8Array([0x01, 0x00, 0x01]), // 65537
        hash: "SHA-1",
      },
      true,
      ["sign", "verify"],
    );
    return crypto.subtle.exportKey("jwk", pair.privateKey);
  }

  static fromJwk(jwk) {
    return new AdbCrypto(
      bytesBEToBigInt(base64urlToBytes(jwk.n)),
      bytesBEToBigInt(base64urlToBytes(jwk.e)),
      bytesBEToBigInt(base64urlToBytes(jwk.d)),
    );
  }

  // Sign a 20-byte ADB auth token. WebCrypto cannot sign a pre-computed digest
  // without re-hashing, so we build the PKCS#1 v1.5 block by hand and perform
  // the raw RSA operation (m^d mod n) with BigInt.
  sign(token) {
    const k = 256; // 2048-bit modulus = 256 bytes
    const digest = concat(SHA1_DIGEST_INFO, token); // 15 + 20 = 35 bytes
    const psLen = k - 3 - digest.length; // padding run of 0xFF
    const em = new Uint8Array(k);
    em[0] = 0x00;
    em[1] = 0x01;
    em.fill(0xff, 2, 2 + psLen);
    em[2 + psLen] = 0x00;
    em.set(digest, 3 + psLen);
    const signature = modPow(bytesBEToBigInt(em), this.d, this.n);
    return bigIntToBytesBE(signature, k);
  }

  // Encode the public key in Android's RSAPublicKey wire format and append the
  // " user@host\0" identity, exactly as adb sends it. An unrecognised key here
  // is what makes the "Allow USB debugging?" dialog appear on the device.
  publicKeyBytes() {
    const MOD_BYTES = 256;
    const WORDS = MOD_BYTES / 4; // 64
    const r32 = 1n << 32n;
    const n0 = this.n & (r32 - 1n); // least-significant 32-bit word of n
    const n0inv = (r32 - modInverse(n0, r32)) % r32; // -1 / n[0] mod 2^32
    const rr = modPow(1n << BigInt(MOD_BYTES * 8), 2n, this.n); // R^2 mod n, R=2^2048

    const buf = new Uint8Array(4 + 4 + MOD_BYTES + MOD_BYTES + 4); // 524 bytes
    const dv = new DataView(buf.buffer);
    dv.setUint32(0, WORDS, true);
    dv.setUint32(4, Number(n0inv), true);
    buf.set(bigIntToBytesLE(this.n, MOD_BYTES), 8);
    buf.set(bigIntToBytesLE(rr, MOD_BYTES), 8 + MOD_BYTES);
    dv.setUint32(8 + 2 * MOD_BYTES, Number(this.e), true);

    const encoded = bytesToBase64(buf) + " webadb@webusb\0";
    return textEncoder.encode(encoded);
  }
}

// ---------------------------------------------------------------------------
// UsbReader — buffers the bulk IN endpoint so callers can read exact counts.
// ---------------------------------------------------------------------------

class UsbReader {
  constructor(device, endpointNumber) {
    this.device = device;
    this.ep = endpointNumber;
    this.buffer = new Uint8Array(0);
  }

  async read(n) {
    while (this.buffer.length < n) {
      const result = await this.device.transferIn(this.ep, 16384);
      if (result.status !== "ok") {
        throw new Error(`USB transferIn failed: ${result.status}`);
      }
      const chunk = new Uint8Array(
        result.data.buffer,
        result.data.byteOffset,
        result.data.byteLength,
      );
      this.buffer = concat(this.buffer, chunk);
    }
    const out = this.buffer.slice(0, n);
    this.buffer = this.buffer.slice(n);
    return out;
  }
}

// ---------------------------------------------------------------------------
// AdbConnection — owns the USB device and drives the protocol.
// ---------------------------------------------------------------------------

export class AdbConnection {
  constructor(device, log = () => {}) {
    this.device = device;
    this.log = log;
    this.epIn = null;
    this.epOut = null;
    this.interfaceNumber = null;
    this.reader = null;
    this.maxPayload = MAX_PAYLOAD;
    this.banner = "";
    this.nextLocalId = 1;
  }

  // Locate the ADB interface, claim it, and remember its bulk endpoints.
  async open() {
    await this.device.open();
    if (!this.device.configuration) {
      await this.device.selectConfiguration(1);
    }

    let found = null;
    for (const iface of this.device.configuration.interfaces) {
      for (const alt of iface.alternates) {
        if (
          alt.interfaceClass === ADB_INTERFACE_FILTER.classCode &&
          alt.interfaceSubclass === ADB_INTERFACE_FILTER.subclassCode &&
          alt.interfaceProtocol === ADB_INTERFACE_FILTER.protocolCode
        ) {
          found = { iface, alt };
          break;
        }
      }
      if (found) break;
    }
    if (!found) {
      throw new Error("No ADB interface (class 0xff/0x42/0x01) on this device.");
    }

    this.interfaceNumber = found.iface.interfaceNumber;
    try {
      await this.device.claimInterface(this.interfaceNumber);
    } catch (e) {
      throw new Error(
        "Could not claim the ADB interface — it is probably owned by another " +
          "process. Run `adb kill-server`, close Android Studio and other " +
          "browser tabs, then try again. (" + e.message + ")",
      );
    }
    if (found.alt.alternateSetting !== 0) {
      await this.device.selectAlternateInterface(
        this.interfaceNumber,
        found.alt.alternateSetting,
      );
    }

    for (const ep of found.alt.endpoints) {
      if (ep.type !== "bulk") continue;
      if (ep.direction === "in") this.epIn = ep.endpointNumber;
      else if (ep.direction === "out") this.epOut = ep.endpointNumber;
    }
    if (this.epIn == null || this.epOut == null) {
      throw new Error("ADB interface is missing its bulk endpoints.");
    }

    this.reader = new UsbReader(this.device, this.epIn);
    this.log(`Claimed ADB interface ${this.interfaceNumber} (in=${this.epIn}, out=${this.epOut}).`);
  }

  // --- packet I/O -----------------------------------------------------------

  async sendMessage(command, arg0, arg1, data) {
    let payload;
    if (data == null) payload = new Uint8Array(0);
    else if (typeof data === "string") payload = textEncoder.encode(data);
    else payload = data;

    let checksum = 0;
    for (let i = 0; i < payload.length; i++) checksum = (checksum + payload[i]) >>> 0;

    const header = new ArrayBuffer(24);
    const dv = new DataView(header);
    dv.setUint32(0, command >>> 0, true);
    dv.setUint32(4, arg0 >>> 0, true);
    dv.setUint32(8, arg1 >>> 0, true);
    dv.setUint32(12, payload.length, true);
    dv.setUint32(16, checksum, true);
    dv.setUint32(20, (command ^ 0xffffffff) >>> 0, true);

    await this.device.transferOut(this.epOut, header);
    if (payload.length > 0) {
      await this.device.transferOut(this.epOut, payload);
    }
  }

  async readMessage() {
    const head = await this.reader.read(24);
    const dv = new DataView(head.buffer, head.byteOffset, 24);
    const command = dv.getUint32(0, true);
    const arg0 = dv.getUint32(4, true);
    const arg1 = dv.getUint32(8, true);
    const length = dv.getUint32(12, true);
    const data = length > 0 ? await this.reader.read(length) : new Uint8Array(0);
    return { command, arg0, arg1, data };
  }

  // --- handshake ------------------------------------------------------------

  // Perform the CNXN + AUTH handshake. Returns the device banner string.
  async connect(adbCrypto) {
    this.log("Sending CNXN (connect)…");
    await this.sendMessage(
      A_CNXN,
      A_VERSION,
      this.maxPayload,
      "host::features=shell_v2,cmd,stat_v2\0",
    );

    let signatureSent = false;
    let publicKeySent = false;

    for (;;) {
      const msg = await this.readMessage();

      if (msg.command === A_CNXN) {
        this.banner = textDecoder.decode(msg.data).replace(/\0+$/, "");
        if (msg.arg1 > 0) this.maxPayload = Math.min(this.maxPayload, msg.arg1);
        this.log("Device accepted the connection.");
        return this.banner;
      }

      if (msg.command === A_AUTH && msg.arg0 === ADB_AUTH_TOKEN) {
        const token = msg.data;
        if (!signatureSent) {
          this.log("Device sent an auth token — signing it with our key…");
          await this.sendMessage(A_AUTH, ADB_AUTH_SIGNATURE, 0, adbCrypto.sign(token));
          signatureSent = true;
        } else if (!publicKeySent) {
          this.log("Key not yet authorised — sending our public key. Check the device for the \"Allow USB debugging?\" prompt.");
          await this.sendMessage(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, adbCrypto.publicKeyBytes());
          publicKeySent = true;
        } else {
          // Re-sign in case the user just tapped "Allow".
          await this.sendMessage(A_AUTH, ADB_AUTH_SIGNATURE, 0, adbCrypto.sign(token));
        }
        continue;
      }

      if (msg.command === A_STLS) {
        throw new Error("Device requested STLS (ADB-over-TLS), which this demo does not implement.");
      }

      this.log(`Ignoring unexpected packet 0x${msg.command.toString(16)} during handshake.`);
    }
  }

  // --- streams --------------------------------------------------------------

  // Open a service stream (e.g. "shell:echo hi"), collect all output until the
  // device closes the stream, and return it decoded as text. Suitable for the
  // legacy one-shot `shell:<cmd>` service used here for the proof-of-life demo.
  async runService(service) {
    const localId = this.nextLocalId++;
    let remoteId = 0;
    let output = new Uint8Array(0);

    await this.sendMessage(A_OPEN, localId, 0, service + "\0");

    for (;;) {
      const msg = await this.readMessage();
      if (msg.arg1 !== localId && msg.command !== A_CLSE) {
        // Not for our stream; ignore.
        continue;
      }

      switch (msg.command) {
        case A_OKAY:
          remoteId = msg.arg0;
          break;
        case A_WRTE:
          output = concat(output, msg.data);
          await this.sendMessage(A_OKAY, localId, msg.arg0); // ack the write
          break;
        case A_CLSE:
          if (msg.arg1 === localId || msg.arg1 === 0) {
            await this.sendMessage(A_CLSE, localId, remoteId);
            return textDecoder.decode(output);
          }
          break;
        default:
          break;
      }
    }
  }

  async close() {
    try {
      if (this.interfaceNumber != null) {
        await this.device.releaseInterface(this.interfaceNumber);
      }
    } catch (e) {
      /* ignore */
    }
    try {
      await this.device.close();
    } catch (e) {
      /* ignore */
    }
  }
}

export function isWebUsbSupported() {
  return typeof navigator !== "undefined" && !!navigator.usb;
}
