// SPDX-License-Identifier: AGPL-3.0-only
const test = require("node:test");
const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const { NowPlayingBridge } = require("../electron/now-playing.cjs");

class FakeInput {
  constructor() { this.writable = true; this.lines = []; }
  write(line) { this.lines.push(JSON.parse(line)); return true; }
  end() { this.writable = false; }
}

class FakeOutput extends EventEmitter {
  resume() {}
  pause() {}
}

class FakeChild extends EventEmitter {
  constructor() { super(); this.stdin = new FakeInput(); this.stdout = new FakeOutput(); }
}

test("publishes current track once per second and forwards remote commands", () => {
  const child = new FakeChild();
  const commands = [];
  const bridge = new NowPlayingBridge({
    platform: "darwin",
    executable: process.execPath,
    spawnImpl: () => child,
    onCommand: (event) => commands.push(event),
  });
  assert.equal(bridge.start(), true);
  const state = {
    playback: {
      track: { id: "1", title: "Первый свет", artist: "Лад Session", album: "Тихий город", cover: "https://example.test/cover.jpg" },
      playing: true, position: 10.2, duration: 216,
    },
  };
  bridge.update(state);
  bridge.update(state);
  assert.equal(child.stdin.lines.length, 1);
  assert.equal(child.stdin.lines[0].method, "set");
  assert.equal(child.stdin.lines[0].params.title, "Первый свет");
  state.playback.position = 11.1;
  bridge.update(state);
  assert.equal(child.stdin.lines.length, 2);
  child.stdout.emit("data", Buffer.from('{"event":"command","action":"seek","value":42}\n'));
  assert.deepEqual(commands, [{ event: "command", action: "seek", value: 42 }]);
  bridge.clear();
  assert.equal(child.stdin.lines.at(-1).method, "clear");
});

test("clears Now Playing when no track exists", () => {
  const child = new FakeChild();
  const bridge = new NowPlayingBridge({ platform: "darwin", executable: process.execPath, spawnImpl: () => child });
  bridge.start();
  bridge.update({ playback: { track: null } });
  assert.deepEqual(child.stdin.lines, [{ method: "clear" }]);
});
