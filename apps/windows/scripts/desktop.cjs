// SPDX-License-Identifier: AGPL-3.0-only
const { spawn } = require("node:child_process");
const path = require("node:path");
const fs = require("node:fs");
const root = path.resolve(__dirname, "..");
const local = path.join(
  root,
  ".venv",
  process.platform === "win32" ? "Scripts/python.exe" : "bin/python",
);
const python =
  process.env.LAD_PYTHON || local;
const electron = require("electron");
const proc = spawn(electron, [root], {
  stdio: "inherit",
  env: { ...process.env, LAD_PYTHON: python },
});
proc.on("exit", (code) => process.exit(code || 0));
