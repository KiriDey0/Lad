// SPDX-License-Identifier: AGPL-3.0-only
import { WaveFeatures } from "../../../../../shared/wave/audio-features.mjs";
import type { AudioSettings } from "../types";
const features = new WaveFeatures();
let active: HTMLAudioElement | null = null;
let analysisEnabled = true;
const volumes = new WeakMap<HTMLAudioElement, number>();
type AudioGraph = {
  context: AudioContext;
  analyser: AnalyserNode;
  source: MediaElementAudioSourceNode;
  analysing: boolean;
  output: GainNode;
  preamp: GainNode;
  loudLow: BiquadFilterNode;
  loudHigh: BiquadFilterNode;
  splitter: ChannelSplitterNode;
  merger: ChannelMergerNode;
  widthGains: GainNode[];
  dryGain: GainNode;
  wetGain: GainNode;
  mix: GainNode;
  convolver: ConvolverNode;
  limiter: DynamicsCompressorNode;
  filters: BiquadFilterNode[];
  data: Float32Array<ArrayBuffer>;
};
const graphs = new WeakMap<HTMLAudioElement, AudioGraph>();
let audioSettings: AudioSettings | null = null;
const frequencies = [31.5, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];

// Only the analyser input is switched; the audible DSP chain is connected once.
function reconnectGraph(graph: AudioGraph) {
  let chain: AudioNode = graph.source;
  if (graph.analysing) chain = chain.connect(graph.analyser);
  chain.connect(graph.preamp);
}

function connectDspGraph(graph: AudioGraph) {
  let chain: AudioNode = graph.preamp;
  graph.filters.forEach((filter) => { chain = chain.connect(filter); });
  chain.connect(graph.loudLow).connect(graph.loudHigh).connect(graph.splitter);
  graph.splitter.connect(graph.widthGains[0], 0);
  graph.splitter.connect(graph.widthGains[1], 1);
  graph.splitter.connect(graph.widthGains[2], 0);
  graph.splitter.connect(graph.widthGains[3], 1);
  graph.widthGains[0].connect(graph.merger, 0, 0);
  graph.widthGains[1].connect(graph.merger, 0, 0);
  graph.widthGains[2].connect(graph.merger, 0, 1);
  graph.widthGains[3].connect(graph.merger, 0, 1);
  graph.merger.connect(graph.dryGain).connect(graph.mix);
  graph.merger.connect(graph.convolver).connect(graph.wetGain).connect(graph.mix);
  graph.mix.connect(graph.limiter).connect(graph.output).connect(graph.context.destination);
}

function applySettings(graph: AudioGraph) {
  const now = graph.context.currentTime;
  const enabled = audioSettings?.enabled === true;
  graph.preamp.gain.setTargetAtTime(enabled ? 10 ** (audioSettings!.preamp / 20) : 1, now, 0.015);
  graph.filters.forEach((filter, index) => {
    filter.gain.setTargetAtTime(enabled ? audioSettings!.gains[index] : 0, now, 0.015);
  });
  graph.loudLow.gain.setTargetAtTime(enabled && audioSettings?.loudness ? 3 : 0, now, 0.03);
  graph.loudHigh.gain.setTargetAtTime(enabled && audioSettings?.loudness ? 2 : 0, now, 0.03);
  const width = enabled ? Math.max(0, Math.min(1.5, audioSettings!.stereoWidth / 100)) : 1;
  [0.5 * (1 + width), 0.5 * (1 - width), 0.5 * (1 - width), 0.5 * (1 + width)].forEach((value, index) => {
    graph.widthGains[index].gain.setTargetAtTime(value, now, 0.03);
  });
  graph.dryGain.gain.setTargetAtTime(1, now, 0.03);
  graph.wetGain.gain.setTargetAtTime(enabled && audioSettings?.reverb ? 0.18 : 0, now, 0.03);
  graph.limiter.threshold.setTargetAtTime(audioSettings?.limiter && enabled ? -2 : 0, now, 0.015);
  graph.limiter.knee.setTargetAtTime(audioSettings?.limiter && enabled ? 8 : 0, now, 0.015);
  graph.limiter.ratio.setTargetAtTime(audioSettings?.limiter && enabled ? 8 : 1, now, 0.015);
  graph.limiter.attack.setTargetAtTime(0.003, now, 0.015);
  graph.limiter.release.setTargetAtTime(0.08, now, 0.015);
}

export function setWaveAudioSettings(settings: AudioSettings) {
  audioSettings = settings;
  const graph = active && graphs.get(active);
  if (graph) applySettings(graph);
}
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
      const preamp = context.createGain();
      const filters = frequencies.map((frequency) => {
        const filter = context.createBiquadFilter();
        filter.type = "peaking";
        filter.frequency.value = frequency;
        filter.Q.value = 1.1;
        return filter;
      });
      const loudLow = context.createBiquadFilter();
      loudLow.type = "lowshelf";
      loudLow.frequency.value = 120;
      const loudHigh = context.createBiquadFilter();
      loudHigh.type = "highshelf";
      loudHigh.frequency.value = 8000;
      // Upmix mono as speakers before splitting, so both output channels survive.
      loudHigh.channelCount = 2;
      loudHigh.channelCountMode = "explicit";
      const splitter = context.createChannelSplitter(2);
      const merger = context.createChannelMerger(2);
      const widthGains = [1, 0, 0, 1].map(value => {
        const gain = context.createGain(); gain.gain.value = value; return gain;
      });
      const dryGain = context.createGain();
      const wetGain = context.createGain();
      wetGain.gain.value = 0;
      const mix = context.createGain();
      const convolver = context.createConvolver();
      const impulse = context.createBuffer(2, Math.floor(context.sampleRate * 0.9), context.sampleRate);
      for (let channel = 0; channel < impulse.numberOfChannels; channel++) {
        const data = impulse.getChannelData(channel);
        for (let index = 0; index < data.length; index++) data[index] = (Math.random() * 2 - 1) * (1 - index / data.length) ** 2;
      }
      convolver.buffer = impulse;
      const limiter = context.createDynamicsCompressor();
      limiter.threshold.value = 0;
      limiter.ratio.value = 1;
      const output = context.createGain();
      output.gain.value = volumes.get(audio) ?? audio.volume;
      audio.volume = 1;
      graph = {
        context,
        analyser,
        source,
        analysing: analysisEnabled,
        output,
        preamp,
        loudLow,
        loudHigh,
        splitter,
        merger,
        widthGains,
        dryGain,
        wetGain,
        mix,
        convolver,
        limiter,
        filters,
        data: new Float32Array(analyser.frequencyBinCount),
      };
      graphs.set(audio, graph);
      connectDspGraph(graph);
      reconnectGraph(graph);
      applySettings(graph);
    }
  }
  if (graph.analysing !== analysisEnabled) {
    graph.source.disconnect();
    graph.analyser.disconnect();
    graph.analysing = analysisEnabled;
    reconnectGraph(graph);
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
  reconnectGraph(graph);
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
