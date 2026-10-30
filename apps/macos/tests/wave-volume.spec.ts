// SPDX-License-Identifier: AGPL-3.0-only
import {test, expect, _electron} from '@playwright/test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {buildSync} from 'esbuild';

test('decoded PCM remains identical at 0%, 10% and 100% listening volume', async () => {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'lad-volume-'));
  const main = path.join(temp, 'main.cjs');
  fs.writeFileSync(main, `const {app,BrowserWindow}=require('electron');app.setPath('userData',${JSON.stringify(temp)});app.commandLine.appendSwitch('autoplay-policy','no-user-gesture-required');app.whenReady().then(()=>{const w=new BrowserWindow({show:false});w.loadURL('about:blank');});`);
  const app = await _electron.launch({args:[main]});
  try {
    const page = await app.firstWindow();
    const root = path.resolve(__dirname,'..');
    const bundle = buildSync({stdin:{contents:`import {attachWaveAudio,setWaveVolume,sampleWaveAudio,resetWaveAudio} from ${JSON.stringify(path.join(root,'src/wave/audio.ts'))};window.waveTest={attachWaveAudio,setWaveVolume,sampleWaveAudio,resetWaveAudio};`,resolveDir:root},bundle:true,write:false});
    await page.addScriptTag({content:bundle.outputFiles[0].text});
    const result = await page.evaluate(async () => {
      const api = (window as any).waveTest;
      const NativeContext = window.AudioContext;
      let monitor: AnalyserNode;
      // Observe actual output after listening gain, then mute it downstream.
      window.AudioContext = class extends NativeContext {
        get destination() {
          if (!monitor) {
            monitor = this.createAnalyser(); monitor.fftSize=2048;
            const mute=this.createGain();mute.gain.value=0;
            monitor.connect(mute).connect(super.destination);
          }
          return monitor as unknown as AudioDestinationNode;
        }
      };
      const rate=48000, samples=rate*8, data=new ArrayBuffer(44+samples*2),v=new DataView(data);
      const str=(o:number,s:string)=>[...s].forEach((c,i)=>v.setUint8(o+i,c.charCodeAt(0)));
      str(0,'RIFF');v.setUint32(4,36+samples*2,true);str(8,'WAVE');str(12,'fmt ');v.setUint32(16,16,true);v.setUint16(20,1,true);v.setUint16(22,1,true);v.setUint32(24,rate,true);v.setUint32(28,rate*2,true);v.setUint16(32,2,true);v.setUint16(34,16,true);str(36,'data');v.setUint32(40,samples*2,true);
      for(let i=0;i<samples;i++)v.setInt16(44+i*2,Math.round(Math.sin(i*2*Math.PI*125/rate)*6500),true);
      const audio=new Audio(URL.createObjectURL(new Blob([data],{type:'audio/wav'})));
      api.setWaveVolume(audio,.1);
      await Promise.all([api.attachWaveAudio(audio),api.attachWaveAudio(audio)]);
      await audio.play();
      const output=[];
      for(const volume of [.1,1,0]){
        api.setWaveVolume(audio,volume);api.resetWaveAudio();
        let feature:any;
        for(let i=0;i<90;i++) {await new Promise(r=>setTimeout(r,16));feature=api.sampleWaveAudio(1/60);}
        const pcm=new Float32Array(2048);monitor!.getFloatTimeDomainData(pcm);
        const rms=Math.sqrt(pcm.reduce((sum,x)=>sum+x*x,0)/pcm.length);
        output.push({volume,rms,bass:feature.bass,elementVolume:audio.volume});
      }
      audio.pause();URL.revokeObjectURL(audio.src);
      return output;
    });
    expect(result[0].bass).toBeGreaterThan(.1);
    for(const row of result){expect(Math.abs(row.bass-result[0].bass)).toBeLessThan(.015);expect(row.elementVolume).toBe(1);}
    expect(result[1].rms/result[0].rms).toBeCloseTo(10,0);
    expect(result[2].rms).toBeLessThan(.00001);
  } finally {await app.close();fs.rmSync(temp,{recursive:true,force:true});}
});
