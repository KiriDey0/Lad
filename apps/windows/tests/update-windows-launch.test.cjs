// SPDX-License-Identifier: AGPL-3.0-only
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const os=require('node:os');
const path=require('node:path');
const {spawn}=require('node:child_process');
const {helperExecutable}=require('../electron/updates.cjs');
test('Windows helper uses the OS PowerShell executable',()=>{
  const expected=path.join(process.env.SystemRoot||'C:/Windows','System32','WindowsPowerShell','v1.0','powershell.exe');
  assert.equal(helperExecutable('windows-x64-installer'),expected);
});
test('PowerShell confirms startup and survives the launching process', {skip:process.platform!=='win32',timeout:30000}, async t=>{
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'lad-helper-'));t.after(()=>fs.rm(dir,{force:true,recursive:true}));
  const started=path.join(dir,'started'),done=path.join(dir,'done'),script=path.join(dir,'helper.ps1');
  const ps=s=>s.replaceAll("'","''");
  await fs.writeFile(script,"\uFEFFparam([int]$ParentId)\n'ready' | Set-Content -LiteralPath '"+ps(started)+"'\nfor($i=0;$i -lt 100;$i++){if(!(Get-Process -Id $ParentId -ErrorAction SilentlyContinue)){break};Start-Sleep -Milliseconds 100}\n'done' | Set-Content -LiteralPath '"+ps(done)+"'\n");
  const modulePath=path.resolve(__dirname,'../electron/updates.cjs');
  const source=`const {helperExecutable,startHelper}=require(${JSON.stringify(modulePath)});startHelper(helperExecutable('windows-x64-installer'),['-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',${JSON.stringify(script)},'-ParentId',String(process.pid)],${JSON.stringify(started)},true).then(()=>process.exit(0)).catch(e=>{console.error(e);process.exit(1)});`;
  const launcher=spawn(process.execPath,['-e',source],{stdio:['ignore','pipe','pipe']});let errors='';launcher.stderr.on('data',b=>errors+=b);
  const code=await new Promise((resolve,reject)=>{launcher.on('error',reject);launcher.on('exit',resolve)});assert.equal(code,0,errors);
  for(let i=0;i<150;i++){try{assert.match(await fs.readFile(done,'utf8'),/done/);return}catch{}await new Promise(r=>setTimeout(r,100));}
  assert.fail('Helper did not survive launcher shutdown');
});
