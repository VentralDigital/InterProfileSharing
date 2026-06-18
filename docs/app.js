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
  setup: document.getElementById("setup"),
  runSetup: document.getElementById("run-setup"),
  setupStatus: document.getElementById("setup-status"),
  setupResults: document.getElementById("setup-results"),
};

// The InterProfileSharing app and the permission it needs across user profiles.
const TARGET_PACKAGE = "digital.ventral.ips";
const PERMISSION = "android.permission.INTERACT_ACROSS_USERS";

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
  els.setup.classList.toggle("hidden", !connected);
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

// --- InterProfileSharing setup automation ----------------------------------

// Parse `pm list users` output into [{ id, name, running }].
//   Users:
//           UserInfo{0:Owner:4c13} running
//           UserInfo{10:Testuser:410} running
function parseUsers(output) {
  const users = [];
  for (const line of output.split("\n")) {
    const m = line.match(/UserInfo\{(\d+):(.*):[0-9a-fA-F]+\}/);
    if (m) {
      users.push({
        id: parseInt(m[1], 10),
        name: m[2],
        running: /\brunning\b/.test(line),
      });
    }
  }
  return users;
}

// adb shell commands return combined stdout+stderr. pm grant / am force-stop
// print nothing on success, so any non-empty output is an error to surface.
function resultFrom(output) {
  const text = output.trim();
  return text === "" ? { ok: true, message: "OK" } : { ok: false, message: text };
}

async function runSetup() {
  if (!connection || busy) return;
  busy = true;
  els.runSetup.disabled = true;
  els.setupResults.innerHTML = "";
  setSetupStatus("Listing user profiles…", "pending");
  log("=== Setup: configuring " + TARGET_PACKAGE + " across all users ===");

  try {
    // 1. Get all user profiles.
    const usersOut = await connection.runService("shell:pm list users");
    const users = parseUsers(usersOut);
    if (users.length === 0) {
      throw new Error("Could not parse any users from `pm list users`. Raw output:\n" + usersOut.trim());
    }
    log(`Found ${users.length} user profile(s): ${users.map((u) => `${u.id}:${u.name}`).join(", ")}`);

    const rows = [];
    let configured = 0;

    for (const user of users) {
      const row = { user, installed: false, grant: null, restart: null };

      // 2. Is the target package installed for this user?
      const pkgOut = await connection.runService(
        `shell:pm list packages --user ${user.id} ${TARGET_PACKAGE}`,
      );
      row.installed = pkgOut.includes("package:" + TARGET_PACKAGE);

      if (row.installed) {
        log(`User ${user.id} (${user.name}): ${TARGET_PACKAGE} is installed.`);

        // 3. Grant the cross-user permission.
        const grantOut = await connection.runService(
          `shell:pm grant --user ${user.id} ${TARGET_PACKAGE} ${PERMISSION}`,
        );
        row.grant = resultFrom(grantOut);
        log(`  grant --user ${user.id}: ${row.grant.message}`);

        // 4. Force-stop so the app restarts fresh with the new permission.
        const stopOut = await connection.runService(
          `shell:am force-stop --user ${user.id} ${TARGET_PACKAGE}`,
        );
        row.restart = resultFrom(stopOut);
        log(`  force-stop --user ${user.id}: ${row.restart.message}`);

        configured++;
      } else {
        log(`User ${user.id} (${user.name}): ${TARGET_PACKAGE} not installed — skipped.`);
      }

      rows.push(row);
    }

    renderSetupResults(rows);

    const anyGrantFailed = rows.some((r) => r.grant && !r.grant.ok);
    if (configured === 0) {
      setSetupStatus(`Done — ${TARGET_PACKAGE} is not installed for any user.`, "error");
    } else if (anyGrantFailed) {
      setSetupStatus(`Configured ${configured} profile(s), but some grants failed — see below.`, "error");
    } else {
      setSetupStatus(`✓ Configured ${configured} profile(s) successfully.`, "ok");
    }
    log("=== Setup complete ===");
  } catch (e) {
    log(`Setup failed: ${e.message}`);
    setSetupStatus("Setup failed — see log.", "error");
  } finally {
    busy = false;
    els.runSetup.disabled = false;
  }
}

function setSetupStatus(text, kind) {
  els.setupStatus.textContent = text;
  els.setupStatus.className = "status " + (kind || "");
}

function renderSetupResults(rows) {
  const table = document.createElement("table");
  table.className = "results-table";
  table.innerHTML =
    "<thead><tr><th>User</th><th>Name</th><th>Installed</th>" +
    "<th>Permission granted</th><th>Restarted</th></tr></thead>";
  const tbody = document.createElement("tbody");

  for (const r of rows) {
    const tr = document.createElement("tr");
    const cells = [
      String(r.user.id),
      r.user.name,
      r.installed ? "yes" : "no",
      r.grant ? statusCell(r.grant) : skipped(),
      r.restart ? statusCell(r.restart) : skipped(),
    ];
    for (const c of cells) {
      const td = document.createElement("td");
      if (c instanceof Node) td.appendChild(c);
      else td.textContent = c;
      tr.appendChild(td);
    }
    tbody.appendChild(tr);
  }
  table.appendChild(tbody);
  els.setupResults.innerHTML = "";
  els.setupResults.appendChild(table);
}

function statusCell(result) {
  const span = document.createElement("span");
  span.className = result.ok ? "ok" : "error";
  span.textContent = result.ok ? "✓ OK" : "✗ " + result.message;
  span.title = result.message;
  return span;
}

function skipped() {
  const span = document.createElement("span");
  span.className = "muted";
  span.textContent = "—";
  return span;
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
  els.setupResults.innerHTML = "";
  setSetupStatus("", "");
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
  els.runSetup.addEventListener("click", runSetup);
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
