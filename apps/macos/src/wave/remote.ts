// SPDX-License-Identifier: AGPL-3.0-only
import {useEffect, useRef} from 'react';
import type {Playback} from '../types';
import {bridge} from '../bridge';
import {attachWaveAudio, detachWaveAudio, resetWaveAudio, setWaveVolume, suspendWaveAudio} from './audio';
import {remoteTarget, remoteCorrection} from '../../../../shared/wave/remote-clock.mjs';

/** Analysis-only mirror: no playback reports, media session, focus or audible output. */
export function useRemoteWaveAudio(playback: Playback | undefined, canvas: React.RefObject<HTMLCanvasElement | null>, intensity: number) {
  const latest = useRef(playback); latest.current = playback;
  const id = playback?.track?.id;
  useEffect(() => {
    if (!id || !playback?.playing || intensity <= 0) return;
    const audio = new Audio();
    audio.crossOrigin = 'anonymous'; audio.preload = 'auto';
    setWaveVolume(audio, 0);
    let disposed = false, visible = true, pending = false, loaded = false, retryAt = 0, lastSeek = 0;
    const reduced = matchMedia('(prefers-reduced-motion: reduce)');
    const enabled = () => !disposed && visible && !document.hidden && !reduced.matches;
    const stop = () => { audio.pause(); audio.removeAttribute('src'); audio.load(); loaded = false; suspendWaveAudio(audio); };
    const sync = () => {
      const p = latest.current;
      if (!p || p.track?.id !== id) return;
      if (!enabled()) { if (loaded) stop(); return; }
      const target = remoteTarget(p);
      if (target === null) { audio.pause(); return; }
      if (!loaded) {
        if (pending || Date.now() < retryAt) return;
        pending = true;
        void bridge.rpc<{url:string}>('waveStream', {trackId:id}).then(async ({url}) => {
          if (!enabled()) return;
          audio.src = url; audio.load(); loaded = true;
          await attachWaveAudio(audio);
          if (!enabled()) {stop();detachWaveAudio(audio);}
        }).catch(() => {retryAt = Date.now() + 15000; stop();}).finally(() => {pending = false;});
        return;
      }
      if (audio.readyState < 2 || audio.seeking) return;
      const correction = remoteCorrection(target, audio.currentTime);
      if (correction.seek !== null && Date.now() - lastSeek > 700) {
        audio.currentTime = correction.seek; lastSeek = Date.now();
      }
      audio.playbackRate = correction.rate;
      if (audio.paused && !audio.ended) void audio.play().catch(() => {retryAt = Date.now()+15000;stop();});
    };
    const failed = () => {retryAt = Date.now()+15000;stop();};
    const observer = new IntersectionObserver(entries => {visible = entries[0].isIntersecting;sync();});
    if (canvas.current) observer.observe(canvas.current);
    audio.addEventListener('canplay', sync); audio.addEventListener('error', failed);
    document.addEventListener('visibilitychange', sync); reduced.addEventListener('change', sync);
    resetWaveAudio(); sync();
    const timer = setInterval(sync, 200);
    return () => {
      disposed = true; clearInterval(timer);observer.disconnect();
      audio.removeEventListener('canplay', sync);audio.removeEventListener('error', failed);
      document.removeEventListener('visibilitychange', sync);reduced.removeEventListener('change', sync);
      stop(); detachWaveAudio(audio);
    };
  }, [id, playback?.playing, intensity > 0]);
}
