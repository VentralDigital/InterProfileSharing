import {
  AdbConnection,
  AdbCrypto,
  ADB_INTERFACE_FILTER,
  isWebUsbSupported,
} from "./adb.js";

const els = {
  connectBtn: document.getElementById("connect"),
  disconnectBtn: document.getElementById("disconnect"),
  status: document.getElementById("status"),
  deviceInfo: document.getElementById("device-info"),
  log: document.getElementById("log"),
  tests: document.getElementById("tests"),
  customCmd: document.getElementById("custom-cmd"),
  runCustom: document.getElementById("run-custom"),
  support: document.getElementById("support-warning"),
};

let connection = null;
let adbKey = null;
let busy = false;

function log(line) {
  const time = new Date().toLocaleTimeString();
  els.log.textContent += `[${time}] ${line}\n`;
  els.log.scrollTop = els.log.scrollHeight;
}

function setStatus(text, kind) {
  els.status.textContent = text;
  els.status.className = "status " + (kind || "");
}

function setConnectedUI(connected) {
  els.connectBtn.disabled = connected;
  els.disconnectBtn.disabled = !connected;
  els.tests.classList.toggle("hidden", !connected);
  els.deviceInfo.classList.toggle("hidden", !connected);
}

// Pre-defined proof-of-life commands.
const TEST_COMMANDS = [
  { label: "Model", service: "shell:getprop ro.product.model" },
  { label: "Android version", service: "shell:getprop ro.build.version.release" },
  { label: "Serial", service: "shell:getprop ro.serialno" },
  { label: "Uptime", service: "shell:uptime" },
  { label: "Whoami / id", service: "shell:id" },
  { label: "Echo test", service: "shell:echo 'WebADB is talking to your device!'" },
  { label: "List /sdcard", service: "shell:ls -la /sdcard" },
];

function buildTestButtons() {
  els.tests.querySelectorAll(".test-grid")[0]?.remove();
  const grid = document.createElement("div");
  grid.className = "test-grid";
  for (const test of TEST_COMMANDS) {
    const btn = document.createElement("button");
    btn.textContent = test.label;
    btn.className = "test-btn";
    btn.addEventListener("click", () => runCommand(test.service, test.label));
    grid.appendChild(btn);
  }
  els.tests.insertBefore(grid, els.tests.querySelector(".custom-row"));
}

async function runCommand(service, label) {
  if (!connection || busy) return;
  busy = true;
  log(`$ ${service}`);
  try {
    const output = await connection.runService(service);
    const trimmed = output.replace(/\s+$/, "");
    log((label ? `${label}:\n` : "") + (trimmed || "(no output)"));
  } catch (e) {
    log(`Error running "${service}": ${e.message}`);
  } finally {
    busy = false;
  }
}

async function connect() {
  if (!isWebUsbSupported()) return;
  setStatus("Requesting device…", "pending");
  try {
    adbKey = adbKey || (await AdbCrypto.load());

    const device = await navigator.usb.requestDevice({
      filters: [ADB_INTERFACE_FILTER],
    });

    connection = new AdbConnection(device, log);
    log(`Selected: ${device.productName || "Unknown"} (${vendorProduct(device)})`);

    setStatus("Claiming interface…", "pending");
    await connection.open();

    setStatus("Authenticating…", "pending");
    const banner = await connection.connect(adbKey);

    renderDeviceInfo(device, banner);
    setStatus("Connected ✓", "ok");
    setConnectedUI(true);
    buildTestButtons();
    log("Ready. Click a test command above.");
  } catch (e) {
    log(`Connection failed: ${e.message}`);
    setStatus("Connection failed", "error");
    if (/claim/i.test(e.message)) {
      els.support.classList.remove("hidden");
    }
    await disconnect();
  }
}

function vendorProduct(device) {
  const hex = (n) => "0x" + n.toString(16).padStart(4, "0");
  return `${hex(device.vendorId)}:${hex(device.productId)}`;
}

function renderDeviceInfo(device, banner) {
  const rows = [];
  rows.push(["USB device", `${device.productName || "Unknown"} (${vendorProduct(device)})`]);
  if (device.manufacturerName) rows.push(["Manufacturer", device.manufacturerName]);
  if (device.serialNumber) rows.push(["USB serial", device.serialNumber]);

  // The banner looks like "device::ro.product.name=...;ro.product.model=...;features=..."
  const featureMatch = banner.match(/features=([^;]*)/);
  if (featureMatch) rows.push(["ADB features", featureMatch[1]]);

  els.deviceInfo.innerHTML = "<h2>Device</h2>";
  const table = document.createElement("table");
  for (const [k, v] of rows) {
    const tr = document.createElement("tr");
    const th = document.createElement("th");
    th.textContent = k;
    const td = document.createElement("td");
    td.textContent = v;
    tr.append(th, td);
    table.appendChild(tr);
  }
  els.deviceInfo.appendChild(table);

  const rawBanner = document.createElement("p");
  rawBanner.id = "banner";
  rawBanner.textContent = banner;
  els.deviceInfo.appendChild(rawBanner);
}

async function disconnect() {
  if (connection) {
    await connection.close();
    connection = null;
  }
  setConnectedUI(false);
  if (els.status.className.indexOf("error") === -1) {
    setStatus("Disconnected", "");
  }
}

function init() {
  if (!isWebUsbSupported()) {
    els.support.classList.remove("hidden");
    els.support.querySelector("p").textContent =
      "WebUSB is not available in this browser. Use a Chromium-based browser " +
      "(Chrome, Edge, Opera, Brave) on desktop or Android — Firefox and Safari " +
      "do not support WebUSB.";
    els.connectBtn.disabled = true;
    return;
  }

  els.connectBtn.addEventListener("click", connect);
  els.disconnectBtn.addEventListener("click", disconnect);
  els.runCustom.addEventListener("click", () => {
    const cmd = els.customCmd.value.trim();
    if (cmd) runCommand("shell:" + cmd, null);
  });
  els.customCmd.addEventListener("keydown", (e) => {
    if (e.key === "Enter") els.runCustom.click();
  });

  // Re-attach if a previously authorised device gets unplugged/replugged.
  navigator.usb.addEventListener("disconnect", (e) => {
    if (connection && e.device === connection.device) {
      log("Device was disconnected.");
      disconnect();
    }
  });
}

init();
