// SPDX-License-Identifier: AGPL-3.0-only
import { WaveFeatures } from "../../../../shared/wave/audio-features.mjs";
const features = new WaveFeatures();
let active: HTMLAudioElement | null = null;
let analysisEnabled = true;
const volumes = new WeakMap<HTMLAudioElement, number>();
const graphs = new WeakMap<
  HTMLAudioElement,
  {
    context: AudioContext;
    analyser: AnalyserNode;
    source: MediaElementAudioSourceNode;
    analysing: boolean;
    output: GainNode;
    data: Float32Array<ArrayBuffer>;
  }
>();
/** One graph per element, including React StrictMode remounts. No microphone access. */
export async function attachWaveAudio(audio: HTMLAudioElement) {
  active = audio;
  let graph = graphs.get(audio);
  if (!graph) {
    // Leave the original media path intact if AudioContext cannot start.
    const context = new AudioContext();
    try {
      await context.resume();
    } catch {
      void context.close();
      return;
    }
    if (context.state !== "running") {
      void context.close();
      return;
    }
    graph = graphs.get(audio);
    if (graph) {
      void context.close();
    } else {
      const analyser = context.createAnalyser();
      analyser.fftSize = 2048;
      analyser.smoothingTimeConstant = 0;
      const source = context.createMediaElementSource(audio);
      // Analyse decoded PCM before listening gain. A quiet output must not make
      // the visualiser quiet, and zero volume must still retain the beat.
      const output = context.createGain();
      output.gain.value = volumes.get(audio) ?? audio.volume;
      if (analysisEnabled) source.connect(analyser).connect(output);
      else source.connect(output);
      output.connect(context.destination);
      audio.volume = 1;
      graph = {
        context,
        analyser,
        source,
        analysing: analysisEnabled,
        output,
        data: new Float32Array(analyser.frequencyBinCount),
      };
      graphs.set(audio, graph);
    }
  }
  if (graph.analysing !== analysisEnabled) {
    graph.source.disconnect();
    graph.analyser.disconnect();
    if (analysisEnabled)
      graph.source.connect(graph.analyser).connect(graph.output);
    else graph.source.connect(graph.output);
    graph.analysing = analysisEnabled;
  }
  if (graph.context.state === "suspended")
    await graph.context.resume().catch(() => {});
}
/** Bypass FFT work without pausing, suspending or changing audible playback/gain. */
export function setWaveAnalysisEnabled(value: boolean) {
  if (analysisEnabled === value) return;
  analysisEnabled = value;
  const graph = active && graphs.get(active);
  if (!graph) return;
  graph.source.disconnect();
  graph.analyser.disconnect();
  graph.analysing = value;
  if (value) graph.source.connect(graph.analyser).connect(graph.output);
  else graph.source.connect(graph.output);
}
export function setWaveVolume(audio: HTMLAudioElement, volume: number) {
  const value = Math.max(0, Math.min(1, volume));
  volumes.set(audio, value);
  const graph = graphs.get(audio);
  if (graph) {
    graph.output.gain.setTargetAtTime(value, graph.context.currentTime, 0.012);
    audio.volume = 1;
  } else audio.volume = value;
}
export function suspendWaveAudio(audio: HTMLAudioElement) {
  const graph = graphs.get(audio);
  if (active === audio) active = null;
  if (graph?.context.state === "running")
    void graph.context.suspend().catch(() => {});
}
export function detachWaveAudio(audio: HTMLAudioElement) {
  const graph = graphs.get(audio);
  if (active === audio) active = null;
  if (graph) {
    graph.output.disconnect();
    void graph.context.close();
    graphs.delete(audio);
  }
}
export function resetWaveAudio() {
  features.reset();
}
export function sampleWaveAudio(dt: number) {
  const graph = active && graphs.get(active);
  const bands = new Float32Array(8);
  if (
    analysisEnabled &&
    graph &&
    active &&
    !active.paused &&
    !active.ended &&
    graph.context.state === "running"
  ) {
    graph.analyser.getFloatFrequencyData(graph.data);
    const hz = [35, 80, 160, 320, 640, 1280, 2560, 5120, 12000],
      step = graph.context.sampleRate / 2048;
    for (let n = 0; n < 8; n++) {
      let sum = 0;
      for (
        let i = Math.max(1, Math.ceil(hz[n] / step));
        i < Math.min(graph.data.length, Math.ceil(hz[n + 1] / step));
        i++
      )
        sum += 10 ** (graph.data[i] / 10);
      bands[n] = Math.sqrt(sum) * 2.4;
    }
  }
  return features.process({ spectrum: bands }, dt);
}
