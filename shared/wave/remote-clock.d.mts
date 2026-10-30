export function remoteTarget(playback: {position: number;positionAt: number;playing: boolean;transitioning: boolean;duration: number}, now?: number): number | null;
export function remoteCorrection(target: number, actual: number): {seek: number | null;rate: number};
