// SPDX-License-Identifier: AGPL-3.0-only
const fs = require("node:fs");
const readline = require("node:readline");
const { spawn } = require("node:child_process");

class NowPlayingBridge {
  constructor({ executable, onCommand = () => {}, spawnImpl = spawn, platform = process.platform }) {
    this.platform = platform;
    this.executable = executable;
    this.onCommand = onCommand;
    this.spawnImpl = spawnImpl;
    this.child = null;
    this.signature = null;
  }

  start() {
    if (this.platform !== "darwin" || !fs.existsSync(this.executable)) return false;
    this.child = this.spawnImpl(this.executable, [], { stdio: ["pipe", "pipe", "ignore"] });
    const lines = readline.createInterface({ input: this.child.stdout });
    lines.on("line", (line) => {
      try {
        const event = JSON.parse(line);
        if (event.event === "command" && typeof event.action === "string") this.onCommand(event);
      } catch {}
    });
    this.child.on("exit", () => { this.child = null; });
    return true;
  }

  update(state) {
    if (!this.child?.stdin?.writable) return;
    const playback = state?.playback;
    const track = playback?.track;
    if (!track) return this.clear();
    const elapsed = Math.max(0, Number(playback.position) || 0);
    const payload = {
      method: "set",
      params: {
        title: String(track.title || "Лад"),
        artist: String(track.artist || ""),
        album: String(track.album || ""),
        artwork: typeof track.cover === "string" ? track.cover : null,
        duration: Math.max(0, Number(playback.duration) || 0),
        elapsed,
        playing: playback.playing === true,
      },
    };
    const signature = JSON.stringify({
      id: track.id,
      title: payload.params.title,
      artist: payload.params.artist,
      album: payload.params.album,
      artwork: payload.params.artwork,
      duration: payload.params.duration,
      playing: payload.params.playing,
      elapsed: Math.floor(elapsed),
    });
    if (signature === this.signature) return;
    this.signature = signature;
    this.write(payload);
  }

  clear() {
    if (!this.child?.stdin?.writable) return;
    if (this.signature !== "clear") this.write({ method: "clear" });
    this.signature = "clear";
  }

  close() {
    if (!this.child) return;
    try { this.write({ method: "clear" }); } catch {}
    this.child.stdin.end();
    this.child = null;
    this.signature = null;
  }

  write(payload) {
    this.child?.stdin?.write(JSON.stringify(payload) + "\n");
  }
}

module.exports = { NowPlayingBridge };
