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
  deviceInfo: document.getElementById("device-info"),
  setupResults: document.getElementById("setup-results"),
  support: document.getElementById("support-warning"),
  log: document.getElementById("log"),
  step4: document.getElementById("step-4"),
  step5: document.getElementById("step-5"),
};

let connection = null;
let adbKey = null;
let busy = false;

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

    setStatus(els.step4Status, "Waiting for you to accept on the phone…", "pending");
    await connection.connect(adbKey);

    els.deviceInfo.textContent = "Connected to " + (device.productName || "your phone") + ".";
    els.deviceInfo.classList.remove("hidden");
    setStatus(els.step4Status, "Connected ✓", "ok");
    setStepDone(els.step4, true);

    els.grantBtn.disabled = false;
    setStatus(els.step5Status, "Ready — click \"Grant permission\".", "");
    log("Phone connected and authenticated.");
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

// --- Step 5: grant the permission across all profiles ----------------------

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

async function grant() {
  if (!connection || busy) return;
  busy = true;
  els.grantBtn.disabled = true;
  els.setupResults.innerHTML = "";
  setStatus(els.step5Status, "Looking for user profiles…", "pending");
  log("=== Granting " + PERMISSION + " to " + TARGET_PACKAGE + " ===");

  try {
    // 1. Get all user profiles.
    const usersOut = await connection.runService("shell:pm list users");
    const users = parseUsers(usersOut);
    if (users.length === 0) {
      throw new Error("Could not read the list of user profiles.\n" + usersOut.trim());
    }
    log(`Found ${users.length} user profile(s): ${users.map((u) => `${u.id}:${u.name}`).join(", ")}`);

    const rows = [];
    let configured = 0;

    for (const user of users) {
      const row = { user, installed: false, grant: null, restart: null };

      // 2. Is the app installed for this user?
      const pkgOut = await connection.runService(
        `shell:pm list packages --user ${user.id} ${TARGET_PACKAGE}`,
      );
      row.installed = pkgOut.includes("package:" + TARGET_PACKAGE);

      if (row.installed) {
        log(`User ${user.id} (${user.name}): app is installed.`);

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
        log(`User ${user.id} (${user.name}): app not installed — skipped.`);
      }

      rows.push(row);
    }

    renderResults(rows);

    const anyGrantFailed = rows.some((r) => r.grant && !r.grant.ok);
    if (configured === 0) {
      setStatus(els.step5Status, "The app is not installed in any profile yet.", "error");
    } else if (anyGrantFailed) {
      setStatus(els.step5Status, `Granted in ${configured} profile(s), but some failed — see below.`, "error");
      setStepDone(els.step5, true);
    } else {
      setStatus(els.step5Status, `✓ All set in ${configured} profile(s).`, "ok");
      setStepDone(els.step5, true);
    }
    log("=== Done ===");
  } catch (e) {
    log(`Failed: ${e.message}`);
    setStatus(els.step5Status, "Something went wrong — see the log.", "error");
  } finally {
    busy = false;
    if (connection) els.grantBtn.disabled = false;
  }
}

function renderResults(rows) {
  const table = document.createElement("table");
  table.className = "results-table";
  table.innerHTML =
    "<thead><tr><th>User</th><th>Profile</th><th>App installed</th>" +
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
      els.connectBtn.disabled = false;
      cleanup();
    }
  });
}

init();
