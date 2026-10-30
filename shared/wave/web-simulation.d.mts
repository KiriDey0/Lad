export class WebSimulation {
  constructor(count?: number, seed?: number);
  count: number;
  points: Float32Array;
  lines: Float32Array;
  lineCount: number;
  step(
    dt: number,
    audio?: {
      bass?: number;
      mid?: number;
      high?: number;
      spectrum?: ArrayLike<number>;
    },
    smoothing?: number,
  ): this;
}
