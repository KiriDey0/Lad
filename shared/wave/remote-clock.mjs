// SPDX-License-Identifier: AGPL-3.0-only
/** Remote clock corrections never drive playback on the actual output device. */
export function remoteTarget(playback, now = Date.now()) {
  const age = Math.max(0, (now - playback.positionAt) / 1000);
  if (!playback.playing || playback.transitioning || age > 4) return null;
  const position = Math.max(0, playback.position + age);
  return playback.duration > 0 ? Math.min(position, playback.duration) : position;
}
export function remoteCorrection(target, actual) {
  const error = target - actual;
  return Math.abs(error) > .65
    ? { seek: target, rate: 1 }
    : { seek: null, rate: Math.max(.96, Math.min(1.04, 1 + error * .08)) };
}
