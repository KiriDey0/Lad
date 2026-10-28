// SPDX-License-Identifier: AGPL-3.0-only
const fs = require('node:fs');
const fsp = fs.promises;
const path = require('node:path');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const { FEED, verifyManifest } = require('./update-protocol.cjs');
const safeHost = h => ['github.com','release-assets.githubusercontent.com','objects.githubusercontent.com'].includes(h);
async function request(url, signal) {
  for (let i=0;i<8;i++) {
    const u = new URL(url);
    if (u.protocol !== 'https:' || !safeHost(u.hostname) || u.username || u.password) throw Error('Небезопасный адрес загрузки');
    const r = await fetch(u, { signal, redirect:'manual', headers:{'User-Agent':'Lad-Updater','Accept':'application/octet-stream'} });
    if ([301,302,303,307,308].includes(r.status)) { const next=r.headers.get('location'); await r.body?.cancel(); if(!next)throw Error('Не удалось открыть загрузку'); url=new URL(next,u).href;continue; }
    if (!r.ok) { await r.body?.cancel(); throw Error(r.status===404?'Пока нет опубликованного обновления с поддержкой установки':`GitHub недоступен (${r.status}). Попробуй позже`); }
    return r;
  }
  throw Error('Слишком много перенаправлений');
}
async function hashFile(file) { const h=crypto.createHash('sha256'); for await(const chunk of fs.createReadStream(file))h.update(chunk);return h.digest('hex'); }
function helperExecutable(platform) {
  return platform.startsWith('macos') ? '/bin/sh' : path.join(process.env.SystemRoot || 'C:/Windows','System32','WindowsPowerShell','v1.0','powershell.exe');
}
async function startHelper(command,args,started,windows) {
  // Windows PowerShell needs its own console session to survive the app closing.
  // A short launcher creates a hidden process; the actual helper confirms readiness.
  let launchArgs=args;
  if(windows) {
    const ps=s=>"'"+s.replaceAll("'","''")+"'";
    const commandLine=args.map(s=>'"'+s.replaceAll('"','\\"')+'"').join(' ');
    const source=`$ErrorActionPreference='Stop'; Start-Process -FilePath ${ps(command)} -ArgumentList ${ps(commandLine)} -WindowStyle Hidden`;
    launchArgs=['-NoProfile','-NonInteractive','-EncodedCommand',Buffer.from(source,'utf16le').toString('base64')];
  }
  const child=spawn(command,launchArgs,{detached:!windows,stdio:'ignore',windowsHide:true});
  await new Promise((resolve,reject)=>{child.once('spawn',resolve);child.once('error',reject);});
  let exited=false;
  const onExit=()=>{exited=true;};child.once('exit',onExit);
  try {
    if(started) {
      let ready=false;
      for(let i=0;i<150;i++) {
        if(fs.existsSync(started)){ready=true;break;}
        if(exited&&!windows)throw Error('Не удалось запустить установщик. Попробуй ещё раз');
        await new Promise(resolve=>setTimeout(resolve,100));
      }
      if(!ready){child.kill();throw Error('Установщик не ответил. Попробуй ещё раз');}
    }
    child.unref();return child;
  } finally {child.removeListener('exit',onExit);}
}
class UpdateService {
  constructor({version,platform,cache,emit,execPath,appPath,quit,verify=verifyManifest,fetcher=request}) {
    this.verify=verify;this.fetcher=fetcher;this.version=version;this.platform=platform;this.cache=cache;this.emit=emit;this.execPath=execPath;this.appPath=appPath;this.quit=quit;this.generation=0;this.state={phase:'idle',currentVersion:version,progress:0};
    this.restoring=this.restore();
  }
  set(data) { this.state={...this.state,...data,currentVersion:this.version};this.emit(this.state);return this.state; }
  async restore() {
    try {
      const saved=JSON.parse(await fsp.readFile(path.join(this.cache,'ready.json'),'utf8'));
      const c=this.verify(saved.envelope,this.platform,this.version);
      if(!/^[a-f0-9-]{36}$/.test(saved.directory))return;
      if(!c.newer) {
        const timer=setTimeout(async()=>{
          try {
            await fsp.rm(path.join(this.cache,saved.directory),{recursive:true,force:true});
            const latest=JSON.parse(await fsp.readFile(path.join(this.cache,'ready.json'),'utf8'));
            if(latest.directory===saved.directory)await fsp.rm(path.join(this.cache,'ready.json'),{force:true});
          }catch{}
        },180000);timer.unref();return;
      }
      const file=path.join(this.cache,saved.directory,c.artifact.name);
      if((await fsp.stat(file)).size!==c.artifact.size)return;
      this.candidate=c;this.envelope=saved.envelope;this.file=file;this.set({phase:'ready',version:c.version,notes:c.notes,size:c.artifact.size,progress:100});
    }catch{}
  }
  async action(action) {
    await this.restoring;
    if(action==='state')return this.state;
    if(action==='cancel') { if(this.state.phase==='installing')return this.state; this.generation++;this.abort?.abort();return this.set({phase:this.candidate?'available':'idle',progress:0,message:''}); }
    if(['checking','downloading','installing'].includes(this.state.phase))return this.state;
    if(action==='check')return this.check();
    if(action==='download')return this.download();
    if(action==='install')return this.install();
    throw Error('Неизвестное действие обновления');
  }
  async check() {
    const generation=++this.generation;this.abort=new AbortController();
    this.set({phase:'checking',message:'',progress:0});
    try {
      const r=await this.fetcher(FEED,AbortSignal.any([this.abort.signal,AbortSignal.timeout(20000)]));
      let bytes=0,parts=[];for await(const c of r.body){bytes+=c.length;if(bytes>262144)throw Error('Описание обновления слишком большое');parts.push(Buffer.from(c));}
      const envelope=Buffer.concat(parts).toString('utf8'); const c=this.verify(envelope,this.platform,this.version);
      if(generation!==this.generation)return this.state;
      this.candidate=c.newer?c:null;this.envelope=envelope;this.file=null;
      await fsp.rm(path.join(this.cache,'ready.json'),{force:true});
      return this.set({phase:c.newer?'available':'current',version:c.version,notes:c.notes,size:c.artifact.size,message:c.newer?'':'Установлена последняя версия'});
    }catch(e){if(generation===this.generation)this.set({phase:'error',message:e.name==='TimeoutError'?'GitHub не ответил. Попробуй ещё раз':e.message});return this.state;}
  }
  async download() {
    if(!this.candidate?.newer)throw Error('Сначала проверь наличие обновлений');
    const c=this.candidate,generation=++this.generation;this.abort=new AbortController();
    const directory=crypto.randomUUID(),dir=path.join(this.cache,directory),file=path.join(dir,c.artifact.name),part=file+'.part';
    await fsp.mkdir(dir,{recursive:true,mode:0o700});this.set({phase:'downloading',progress:0,message:''});
    try {
      const r=await this.fetcher(c.artifact.url,AbortSignal.any([this.abort.signal,AbortSignal.timeout(1200000)]));
      const handle=await fsp.open(part,'wx',0o600);let total=0,last=-1;const hash=crypto.createHash('sha256');
      try { for await(const chunk of r.body){ total+=chunk.length;if(total>c.artifact.size)throw Error('Размер загрузки не совпадает с релизом');hash.update(chunk);await handle.writeFile(chunk);const n=Math.min(99,Math.floor(total*100/c.artifact.size));if(n!==last&&generation===this.generation){last=n;this.set({progress:n});} } await handle.sync(); }finally{await handle.close();}
      if(total!==c.artifact.size||hash.digest('hex')!==c.artifact.sha256)throw Error('Файл повреждён: контрольная сумма не совпадает');
      if(generation!==this.generation)return this.state;
      await fsp.rename(part,file);await fsp.writeFile(path.join(this.cache,'ready.json'),JSON.stringify({envelope:this.envelope,directory}),{mode:0o600});if(generation!==this.generation)return this.state;this.file=file;
      return this.set({phase:'ready',progress:100,message:''});
    }catch(e){if(generation===this.generation)this.set({phase:'error',message:e.name==='TimeoutError'?'Загрузка прервалась. Попробуй ещё раз':e.message});return this.state;}
    finally{await fsp.rm(part,{force:true}).catch(()=>{});}
  }
  async install() {
    const c=this.candidate;
    if(!c?.newer||!this.file)throw Error('Сначала скачай обновление');
    this.set({phase:'installing',message:'Проверка файла перед установкой'});
    try {
      if((await fsp.stat(this.file)).size!==c.artifact.size||await hashFile(this.file)!==c.artifact.sha256)throw Error('Скачанный файл изменился. Загрузи его заново');
      const target=this.platform.startsWith('macos')?path.resolve(this.execPath,'../../..'):path.dirname(this.execPath);
      if(this.appPath.startsWith(target+path.sep)===false)throw Error('Не удалось определить установленное приложение');
      await fsp.access(this.platform.startsWith('macos')?path.dirname(target):target,fs.constants.W_OK);
      const dir=await fsp.mkdtemp(path.join(path.dirname(this.file),'install-')),ack=path.join(dir,'launch-confirmed'),journal=path.join(this.cache,'last-install.json');
      const started=this.platform.startsWith('windows')?path.join(dir,'helper-ready'):null;
      const job={started,parentPid:process.pid,target,executable:this.execPath,file:this.file,version:c.version,ack,journal,installer:this.platform.endsWith('installer')};
      const source=path.join(__dirname,this.platform.startsWith('macos')?'update-macos.sh':'update-windows.ps1'),script=path.join(dir,path.basename(source));
      await fsp.copyFile(source,script);await fsp.chmod(script,0o700);
      const args=this.platform.startsWith('macos')?[script,String(job.parentPid),target,this.file,c.version,ack,journal]:['-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',script,'-JobFile',path.join(dir,'job.json')];
      await fsp.writeFile(path.join(dir,'job.json'),JSON.stringify(job),{mode:0o600});
      await startHelper(helperExecutable(this.platform),args,started,this.platform.startsWith('windows'));
      this.quit();return this.state;
    }catch(e){return this.set({phase:'error',message:e.code==='EACCES'?'Нет прав на обновление этой папки. Перемести Лад в доступную папку':e.message});}
  }
}
module.exports={UpdateService,request,hashFile,helperExecutable,startHelper};
