// SPDX-License-Identifier: AGPL-3.0-only
const { randomUUID } = require("node:crypto");
class WaveStreams {
  constructor() {
    this.streams = new Map();
  }
  add(url) {
    const parsed = new URL(url);
    if (parsed.protocol !== "https:") throw Error("Unsupported media URL");
    const id = randomUUID();
    this.streams.set(id, url);
    if (this.streams.size > 8)
      this.streams.delete(this.streams.keys().next().value);
    return "lad-audio://stream/" + id;
  }
  async handle(request, fetch) {
    const url = new URL(request.url),
      source =
        url.hostname === "stream" && this.streams.get(url.pathname.slice(1));
    if (!source || !["GET", "HEAD"].includes(request.method))
      return new Response(null, { status: 404 });
    try {
      const headers = {};
      const range = request.headers.get("Range");
      if (range) headers.Range = range;
      const response = await fetch(source, {
        method: request.method,
        headers,
        signal: request.signal,
      });
      const out = new Headers();
      for (const key of [
        "content-type",
        "content-length",
        "content-range",
        "accept-ranges",
      ])
        if (response.headers.has(key)) out.set(key, response.headers.get(key));
      out.set("Access-Control-Allow-Origin", "*");
      out.set("Cache-Control", "no-store");
      return new Response(response.body, {
        status: response.status,
        headers: out,
      });
    } catch {
      return new Response(null, { status: 502 });
    }
  }
}
module.exports = { WaveStreams };
