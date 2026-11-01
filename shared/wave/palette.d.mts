export const neutralWavePalette: number[][];
export function luminance(rgb: number[]): number;
export function coverWavePalette(data: ArrayLike<number>, width: number, height: number): {
  colors: number[][]; lineGain: number; achromatic: boolean;
};
