import {
  AdbConnection,
  AdbCrypto,
  ADB_INTERFACE_FILTER,
  isWebUsbSupported,
} from "./adb.js";

// The InterProfileSharing app and the permission it needs across user profiles.
const TARGET_PACKAGE = "digital.ventral.ips";
const PERMISSION = "android.permission.INTERACT_ACROSS_USERS";

const els = {
  connectBtn: document.getElementById("connect"),
  grantBtn: document.getElementById("grant"),
  step4Status: document.getElementById("step4-status"),
  step5Status: document.getElementById("step5-status"),
  step5Intro: document.getElementById("step5-intro"),
  deviceInfo: document.getElementById("device-info"),
  profilesTable: document.getElementById("profiles-table"),
  support: document.getElementById("support-warning"),
  log: document.getElementById("log"),
  step4: document.getElementById("step-4"),
  step5: document.getElementById("step-5"),
};

let connection = null;
let adbKey = null;
let busy = false;

// Profiles discovered on the device, plus per-row DOM references.
let profiles = []; // [{ id, name, running, installed }]
const rowEls = new Map(); // id -> { checkbox, resultCell }

function log(line) {
  const time = new Date().toLocaleTimeString();
  els.log.textContent += `[${time}] ${line}\n`;
  els.log.scrollTop = els.log.scrollHeight;
}

function setStatus(el, text, kind) {
  el.textContent = text;
  el.className = "status " + (kind || "");
}

function setStepDone(stepEl, done) {
  stepEl.classList.toggle("done", done);
}

// --- Step 4: connect & authenticate ----------------------------------------

async function connect() {
  if (!isWebUsbSupported() || busy) return;
  busy = true;
  els.connectBtn.disabled = true;
  setStatus(els.step4Status, "Asking you to pick a device…", "pending");
  try {
    adbKey = adbKey || (await AdbCrypto.load());

    const device = await navigator.usb.requestDevice({
      filters: [ADB_INTERFACE_FILTER],
    });
    connection = new AdbConnection(device, log);
    log(`Selected: ${device.productName || "Unknown device"}`);

    setStatus(els.step4Status, "Connecting…", "pending");
    await connection.open();

    setStatus(els.step4Status, "Waiting for you to tap \"Allow\" on the phone…", "pending");
    await connection.connect(adbKey);

    els.deviceInfo.textContent = "Connected to " + (device.productName || "your phone") + ".";
    els.deviceInfo.classList.remove("hidden");
    setStatus(els.step4Status, "Connected ✓", "ok");
    setStepDone(els.step4, true);
    log("Phone connected and authenticated.");

    // Step 5 becomes interactive: list the profiles and show the table.
    await scanProfiles();
  } catch (e) {
    log(`Connection failed: ${e.message}`);
    setStatus(els.step4Status, "Connection failed", "error");
    els.connectBtn.disabled = false;
    if (/claim/i.test(e.message)) {
      showSupportWarning(
        "Could not access the phone — another program is using it. If you are a " +
          "developer, run \"adb kill-server\" and close Android Studio or other " +
          "browser tabs, then click Connect again.",
      );
    }
    await cleanup();
  } finally {
    busy = false;
  }
}

// --- Step 5: discover profiles, then grant on confirmation -----------------

// Parse `pm list users` output into [{ id, name, running }].
//   Users:
//           UserInfo{0:Owner:4c13} running
//           UserInfo{10:Testuser:410} running
function parseUsers(output) {
  const users = [];
  for (const line of output.split("\n")) {
    const m = line.match(/UserInfo\{(\d+):(.*):[0-9a-fA-F]+\}/);
    if (m) {
      users.push({ id: parseInt(m[1], 10), name: m[2], running: /\brunning\b/.test(line) });
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

// List the user profiles and which of them have the app, and render the table.
async function scanProfiles() {
  setStatus(els.step5Status, "Looking for user profiles…", "pending");
  log("Listing user profiles…");
  try {
    const usersOut = await connection.runService("shell:pm list users");
    profiles = parseUsers(usersOut);
    if (profiles.length === 0) {
      throw new Error("Could not read the list of user profiles.\n" + usersOut.trim());
    }

    for (const p of profiles) {
      const pkgOut = await connection.runService(
        `shell:pm list packages --user ${p.id} ${TARGET_PACKAGE}`,
      );
      p.installed = pkgOut.includes("package:" + TARGET_PACKAGE);
      log(`User ${p.id} (${p.name}): app ${p.installed ? "installed" : "not installed"}.`);
    }

    renderProfilesTable();
    els.step5Intro.classList.add("hidden");

    const installedCount = profiles.filter((p) => p.installed).length;
    if (installedCount === 0) {
      els.grantBtn.disabled = true;
      setStatus(
        els.step5Status,
        "The app is not installed in any profile yet — install it first, then reconnect.",
        "error",
      );
    } else {
      els.grantBtn.disabled = false;
      setStatus(
        els.step5Status,
        `Found ${installedCount} profile(s) with the app. Review the selection and click "Grant permission".`,
        "",
      );
    }
  } catch (e) {
    log(`Failed to list profiles: ${e.message}`);
    setStatus(els.step5Status, "Could not read the user profiles — see the log.", "error");
  }
}

function renderProfilesTable() {
  rowEls.clear();
  const table = document.createElement("table");
  table.className = "results-table";
  table.innerHTML =
    "<thead><tr><th>Grant?</th><th>User</th><th>Profile</th>" +
    "<th>App installed</th><th>Result</th></tr></thead>";
  const tbody = document.createElement("tbody");

  for (const p of profiles) {
    const tr = document.createElement("tr");

    // Checkbox: checked when the app is installed, disabled (and off) otherwise.
    const cbCell = document.createElement("td");
    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.checked = p.installed;
    checkbox.disabled = !p.installed;
    cbCell.appendChild(checkbox);

    const idCell = document.createElement("td");
    idCell.textContent = String(p.id);

    const nameCell = document.createElement("td");
    nameCell.textContent = p.name;

    const installedCell = document.createElement("td");
    installedCell.textContent = p.installed ? "yes" : "no";

    const resultCell = document.createElement("td");
    resultCell.appendChild(skipped());

    tr.append(cbCell, idCell, nameCell, installedCell, resultCell);
    tbody.appendChild(tr);
    rowEls.set(p.id, { checkbox, resultCell });
  }

  table.appendChild(tbody);
  els.profilesTable.innerHTML = "";
  els.profilesTable.appendChild(table);
}

// Apply the grant + restart to every selected (checked) profile.
async function grant() {
  if (!connection || busy) return;

  const selected = profiles.filter((p) => p.installed && rowEls.get(p.id).checkbox.checked);
  if (selected.length === 0) {
    setStatus(els.step5Status, "Select at least one profile to grant.", "error");
    return;
  }

  busy = true;
  els.grantBtn.disabled = true;
  // Lock the checkboxes while we work so the selection can't change mid-run.
  for (const { checkbox } of rowEls.values()) checkbox.disabled = true;
  setStatus(els.step5Status, "Granting permission…", "pending");
  log("=== Granting " + PERMISSION + " to " + TARGET_PACKAGE + " ===");

  let failures = 0;
  try {
    for (const p of selected) {
      const { resultCell } = rowEls.get(p.id);

      // Grant the cross-user permission.
      const grantOut = await connection.runService(
        `shell:pm grant --user ${p.id} ${TARGET_PACKAGE} ${PERMISSION}`,
      );
      const grantRes = resultFrom(grantOut);
      log(`  grant --user ${p.id}: ${grantRes.message}`);

      // Force-stop so the app restarts fresh with the new permission.
      let restartRes = { ok: true, message: "OK" };
      if (grantRes.ok) {
        const stopOut = await connection.runService(
          `shell:am force-stop --user ${p.id} ${TARGET_PACKAGE}`,
        );
        restartRes = resultFrom(stopOut);
        log(`  force-stop --user ${p.id}: ${restartRes.message}`);
      }

      const ok = grantRes.ok && restartRes.ok;
      if (!ok) failures++;
      resultCell.innerHTML = "";
      resultCell.appendChild(
        statusCell(ok ? { ok: true, message: "Granted & restarted" } : (grantRes.ok ? restartRes : grantRes)),
      );
    }

    if (failures === 0) {
      setStatus(els.step5Status, `✓ Done — granted in ${selected.length} profile(s).`, "ok");
      setStepDone(els.step5, true);
    } else {
      setStatus(els.step5Status, `Finished with ${failures} problem(s) — see the table.`, "error");
    }
    log("=== Done ===");
  } catch (e) {
    log(`Failed: ${e.message}`);
    setStatus(els.step5Status, "Something went wrong — see the log.", "error");
  } finally {
    busy = false;
    // Re-enable so the user can adjust the selection and try again.
    if (connection) {
      els.grantBtn.disabled = false;
      for (const p of profiles) {
        const entry = rowEls.get(p.id);
        if (entry) entry.checkbox.disabled = !p.installed;
      }
    }
  }
}

function statusCell(result) {
  const span = document.createElement("span");
  span.className = result.ok ? "ok" : "error";
  span.textContent = result.ok ? "✓ " + result.message : "✗ " + result.message;
  span.title = result.message;
  return span;
}

function skipped() {
  const span = document.createElement("span");
  span.className = "muted";
  span.textContent = "—";
  return span;
}

// --- shared -----------------------------------------------------------------

function showSupportWarning(message) {
  els.support.querySelector("p").textContent = message;
  els.support.classList.remove("hidden");
}

async function cleanup() {
  if (connection) {
    await connection.close();
    connection = null;
  }
  profiles = [];
  rowEls.clear();
  els.profilesTable.innerHTML = "";
  els.step5Intro.classList.remove("hidden");
  els.grantBtn.disabled = true;
  setStepDone(els.step4, false);
  setStepDone(els.step5, false);
  els.deviceInfo.classList.add("hidden");
}

function init() {
  if (!isWebUsbSupported()) {
    showSupportWarning(
      "WebUSB is not available in this browser. Please use a Chromium-based " +
        "browser such as Google Chrome, Microsoft Edge, Brave, or Opera. " +
        "Firefox and Safari do not support WebUSB.",
    );
    els.connectBtn.disabled = true;
    return;
  }

  els.connectBtn.addEventListener("click", connect);
  els.grantBtn.addEventListener("click", grant);

  navigator.usb.addEventListener("disconnect", (e) => {
    if (connection && e.device === connection.device) {
      log("Phone was disconnected.");
      setStatus(els.step4Status, "Phone disconnected — reconnect to continue.", "error");
      setStatus(els.step5Status, "", "");
      els.connectBtn.disabled = false;
      cleanup();
    }
  });
}

init();
