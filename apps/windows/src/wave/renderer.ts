// SPDX-License-Identifier: AGPL-3.0-only
import type { WebSimulation } from "../../../../shared/wave/web-simulation.mjs";

/** Two bounded uploads/draws per frame. No textures, extra player or high-power GPU request. */
export class WaveRenderer {
  private gl: WebGLRenderingContext | null;
  private ctx: CanvasRenderingContext2D | null = null;
  private lines?: WebGLProgram;
  private dots?: WebGLProgram;
  private lineBuffer?: WebGLBuffer;
  private dotBuffer?: WebGLBuffer;
  private lineData = new Float32Array(2048 * 18);
  private dotData = new Float32Array(300 * 4);
  private lineUniforms: Record<string, WebGLUniformLocation | null> = {};
  private dotUniforms: Record<string, WebGLUniformLocation | null> = {};
  private lineAttribute = 0;
  private dotAttribute = 0;
  private lineCapacity = 0;
  private dotCapacity = 0;
  private width = 1;
  private height = 1;
  private dpr = 1;
  private fallbackCanvas?: HTMLCanvasElement;
  private readonly restored = () => {
    try {
      this.init();
    } catch {
      this.fallback();
    }
    this.wake();
  };
  private readonly lost = (event: Event) => {
    event.preventDefault();
    this.wake();
  };
  constructor(
    private canvas: HTMLCanvasElement,
    private wake: () => void,
  ) {
    this.gl = canvas.getContext("webgl", {
      alpha: true,
      antialias: true,
      depth: false,
      stencil: false,
      premultipliedAlpha: true,
      powerPreference: "low-power",
    });
    if (this.gl) {
      try {
        this.init();
      } catch {
        this.fallback();
        return;
      }
      canvas.addEventListener("webglcontextlost", this.lost);
      canvas.addEventListener("webglcontextrestored", this.restored);
    } else this.ctx = canvas.getContext("2d");
  }
  private fallback() {
    // A canvas cannot switch context types after WebGL creation. Keep the original
    // as the visibility/size anchor and add a matching 2D surface if a driver fails.
    this.dispose();
    this.gl = null;
    const surface = this.canvas.cloneNode(false) as HTMLCanvasElement;
    this.canvas.insertAdjacentElement("afterend", surface);
    this.canvas = this.fallbackCanvas = surface;
    this.ctx = surface.getContext("2d");
    this.resize(this.width, this.height, this.dpr);
  }
  private program(vertex: string, fragment: string) {
    const gl = this.gl!;
    const shader = (type: number, text: string) => {
      const shader = gl.createShader(type)!;
      gl.shaderSource(shader, text);
      gl.compileShader(shader);
      if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS))
        throw Error(
          "Wave shader compilation failed: " + gl.getShaderInfoLog(shader),
        );
      return shader;
    };
    const vs = shader(gl.VERTEX_SHADER, vertex),
      fs = shader(gl.FRAGMENT_SHADER, fragment);
    const program = gl.createProgram()!;
    gl.attachShader(program, vs);
    gl.attachShader(program, fs);
    gl.linkProgram(program);
    gl.deleteShader(vs);
    gl.deleteShader(fs);
    if (!gl.getProgramParameter(program, gl.LINK_STATUS))
      throw Error(
        "Wave shader linking failed: " + gl.getProgramInfoLog(program),
      );
    return program;
  }
  private init() {
    const gl = this.gl!;
    this.lines = this.program(
      "attribute vec3 a; uniform vec2 viewport; varying mediump float alpha; void main(){gl_Position=vec4(a.x/viewport.x*2.-1.,1.-a.y/viewport.y*2.,0.,1.);alpha=a.z;}",
      "precision mediump float; uniform vec3 tint; uniform float gain; varying mediump float alpha; void main(){float a=min(1.,alpha*gain);gl_FragColor=vec4(tint*a,a);}",
    );
    this.dots = this.program(
      "attribute vec4 a; uniform vec2 viewport; uniform mediump float dpr; varying mediump float depth; varying mediump float radius; void main(){gl_Position=vec4(a.x/viewport.x*2.-1.,1.-a.y/viewport.y*2.,0.,1.);gl_PointSize=a.z*3.2*dpr;radius=a.z;depth=a.w;}",
      "precision mediump float; uniform vec3 first; uniform vec3 second; uniform float strength; uniform mediump float dpr; varying mediump float radius; varying mediump float depth; void main(){float d=length(gl_PointCoord-vec2(.5))*3.2*radius;float aa=.5/dpr;float core=1.-smoothstep(max(.5,radius*.42)-aa,max(.5,radius*.42)+aa,d);float halo=1.-smoothstep(radius*1.4-aa,radius*1.4+aa,d);float a=min(1.,min(1.,(.2+depth*.48)*2.)*strength)*core+.07*strength*halo;vec3 color=mix(second,first,depth);gl_FragColor=vec4(color*a,a);}",
    );
    this.lineUniforms = Object.fromEntries(
      ["viewport", "tint", "gain"].map((n) => [
        n,
        gl.getUniformLocation(this.lines!, n),
      ]),
    );
    this.dotUniforms = Object.fromEntries(
      ["viewport", "dpr", "strength", "first", "second"].map((n) => [
        n,
        gl.getUniformLocation(this.dots!, n),
      ]),
    );
    this.lineAttribute = gl.getAttribLocation(this.lines!, "a");
    this.dotAttribute = gl.getAttribLocation(this.dots!, "a");
    this.lineCapacity = this.dotCapacity = 0;
    this.lineBuffer = gl.createBuffer()!;
    this.dotBuffer = gl.createBuffer()!;
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.ONE, gl.ONE);
  }
  available() {
    return this.gl ? !this.gl.isContextLost() : this.ctx !== null;
  }
  resize(width: number, height: number, dpr: number) {
    this.width = width;
    this.height = height;
    this.dpr = dpr;
    this.canvas.width = Math.round(width * dpr);
    this.canvas.height = Math.round(height * dpr);
    this.ctx?.setTransform(dpr, 0, 0, dpr, 0, 0);
    this.gl?.viewport(0, 0, this.canvas.width, this.canvas.height);
  }
  clear() {
    if (this.gl) {
      this.gl.clearColor(0, 0, 0, 0);
      this.gl.clear(this.gl.COLOR_BUFFER_BIT);
    } else this.ctx?.clearRect(0, 0, this.width, this.height);
  }
  draw(
    sim: WebSimulation,
    colors: number[][],
    strength: number,
    lineGain: number,
  ) {
    this.clear();
    const w = this.width,
      h = this.height,
      size = Math.min(w, h) * 1.08,
      cx = w * 0.5,
      cy = h * 0.42;
    const tint = colors[0].map((v, i) =>
      Math.round(v * 0.55 + colors[1][i] * 0.45),
    );
    if (!this.gl) {
      const ctx = this.ctx;
      if (!ctx) return;
      ctx.globalCompositeOperation = "lighter";
      ctx.lineWidth = 0.8;
      for (let i = 0; i < sim.lineCount; i += 2) {
        const o = i * 3;
        ctx.strokeStyle = `rgba(${tint.join(",")},${Math.min(1, sim.lines[o + 2] * strength * 1.65 * lineGain)})`;
        ctx.beginPath();
        ctx.moveTo(cx + sim.lines[o] * size, cy + sim.lines[o + 1] * size);
        ctx.lineTo(cx + sim.lines[o + 3] * size, cy + sim.lines[o + 4] * size);
        ctx.stroke();
      }
      for (let i = 0; i < sim.count; i++) {
        const o = i * 4,
          x = cx + sim.points[o] * size,
          y = cy + sim.points[o + 1] * size,
          r = sim.points[o + 2] * size * 0.5,
          depth = sim.points[o + 3];
        const color = colors[1].map((v, j) =>
          Math.round(v + (colors[0][j] - v) * depth),
        );
        ctx.fillStyle = `rgba(${color.join(",")},${Math.min(1, Math.min(1, (0.2 + depth * 0.48) * 2) * strength)})`;
        ctx.beginPath();
        ctx.arc(x, y, Math.max(0.5, r * 0.42), 0, Math.PI * 2);
        ctx.fill();
        ctx.fillStyle = `rgba(${color.join(",")},${0.07 * strength})`;
        ctx.beginPath();
        ctx.arc(x, y, r * 1.4, 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.globalCompositeOperation = "source-over";
      return;
    }
    const gl = this.gl;
    if (gl.isContextLost()) return;
    const length = (sim.lineCount / 2) * 18;
    if (this.lineData.length < length)
      this.lineData = new Float32Array(length * 2);
    let n = 0;
    for (let i = 0; i < sim.lineCount; i += 2) {
      const o = i * 3,
        ax = cx + sim.lines[o] * size,
        ay = cy + sim.lines[o + 1] * size;
      const bx = cx + sim.lines[o + 3] * size,
        by = cy + sim.lines[o + 4] * size;
      const dx = bx - ax,
        dy = by - ay,
        d = Math.sqrt(dx * dx + dy * dy);
      if (d < 0.001) continue;
      const px = (-dy / d) * 0.4,
        py = (dx / d) * 0.4,
        alpha = sim.lines[o + 2];
      // Butt-capped 0.8 CSS px lines, independent of GPU line-width limits.
      const v = this.lineData;
      v[n++] = ax + px;
      v[n++] = ay + py;
      v[n++] = alpha;
      v[n++] = bx + px;
      v[n++] = by + py;
      v[n++] = alpha;
      v[n++] = ax - px;
      v[n++] = ay - py;
      v[n++] = alpha;
      v[n++] = ax - px;
      v[n++] = ay - py;
      v[n++] = alpha;
      v[n++] = bx + px;
      v[n++] = by + py;
      v[n++] = alpha;
      v[n++] = bx - px;
      v[n++] = by - py;
      v[n++] = alpha;
    }
    const bind = (
      program: WebGLProgram,
      buffer: WebGLBuffer,
      data: Float32Array,
      count: number,
      size: number,
    ) => {
      gl.useProgram(program);
      gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
      const line = program === this.lines;
      const capacity = line ? this.lineCapacity : this.dotCapacity;
      if (capacity === 0 || capacity < count) {
        gl.bufferData(gl.ARRAY_BUFFER, data.byteLength, gl.DYNAMIC_DRAW);
        if (line) this.lineCapacity = data.length;
        else this.dotCapacity = data.length;
      }
      gl.bufferSubData(gl.ARRAY_BUFFER, 0, data.subarray(0, count));
      const a = line ? this.lineAttribute : this.dotAttribute;
      gl.enableVertexAttribArray(a);
      gl.vertexAttribPointer(a, size, gl.FLOAT, false, 0, 0);
      gl.uniform2f(
        (line ? this.lineUniforms : this.dotUniforms).viewport,
        w,
        h,
      );
    };
    bind(this.lines!, this.lineBuffer!, this.lineData, n, 3);
    gl.uniform3fv(
      this.lineUniforms.tint,
      tint.map((v) => v / 255),
    );
    gl.uniform1f(this.lineUniforms.gain, strength * 1.65 * lineGain);
    gl.drawArrays(gl.TRIANGLES, 0, n / 3);
    if (this.dotData.length < sim.count * 4)
      this.dotData = new Float32Array(sim.count * 4);
    for (let i = 0; i < sim.count; i++) {
      const o = i * 4;
      this.dotData[o] = cx + sim.points[o] * size;
      this.dotData[o + 1] = cy + sim.points[o + 1] * size;
      this.dotData[o + 2] = sim.points[o + 2] * size * 0.5;
      this.dotData[o + 3] = sim.points[o + 3];
    }
    bind(this.dots!, this.dotBuffer!, this.dotData, sim.count * 4, 4);
    gl.uniform1f(this.dotUniforms.dpr, this.dpr);
    gl.uniform1f(this.dotUniforms.strength, strength);
    gl.uniform3fv(
      this.dotUniforms.first,
      colors[0].map((v) => v / 255),
    );
    gl.uniform3fv(
      this.dotUniforms.second,
      colors[1].map((v) => v / 255),
    );
    gl.drawArrays(gl.POINTS, 0, sim.count);
  }
  dispose() {
    this.fallbackCanvas?.remove();
    this.canvas.removeEventListener("webglcontextlost", this.lost);
    this.canvas.removeEventListener("webglcontextrestored", this.restored);
    if (this.gl) {
      this.gl.deleteBuffer(this.lineBuffer!);
      this.gl.deleteBuffer(this.dotBuffer!);
      this.gl.deleteProgram(this.lines!);
      this.gl.deleteProgram(this.dots!);
    }
  }
}
