export type AudioFeatures = {
  bass: number;
  mid: number;
  high: number;
  level: number;
  spectrum: ArrayLike<number>;
};
export class WaveFeatures {
  reset(): void;
  process(input: { spectrum: ArrayLike<number> }, dt: number): AudioFeatures;
}
export class WaveMotion implements AudioFeatures {
  spectrum: Float32Array;
  bass: number;
  mid: number;
  high: number;
  level: number;
  update(input: AudioFeatures, dt: number, responsiveness?: number): this;
}
