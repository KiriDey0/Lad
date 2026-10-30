// SPDX-License-Identifier: AGPL-3.0-only
const { test }=require('node:test');
const assert=require('node:assert/strict');
const { WaveStreams }=require('../electron/wave-stream.cjs');
test('media proxy preserves range requests and CORS without exposing renderer URLs',async()=>{
 const streams=new WaveStreams();const url=streams.add('https://media.example.test/song.mp3');
 const response=await streams.handle(new Request(url,{headers:{Range:'bytes=7-'}}),async(source,init)=>{
  assert.equal(source,'https://media.example.test/song.mp3');assert.equal(init.headers.Range,'bytes=7-');
  return new Response('audio',{status:206,headers:{'Content-Range':'bytes 7-11/12','Content-Type':'audio/mpeg'}});
 });
 assert.equal(response.status,206);assert.equal(response.headers.get('content-range'),'bytes 7-11/12');assert.equal(response.headers.get('access-control-allow-origin'),'*');assert.equal(await response.text(),'audio');
 assert.equal((await streams.handle(new Request('lad-audio://stream/arbitrary'),()=>{throw Error('must not fetch')})).status,404);
 assert.throws(()=>streams.add('file:///etc/passwd'));
});
test('stale streams are bounded and network failures do not expose their addresses',async()=>{
 const streams=new WaveStreams();const old=streams.add('https://media.example.test/old');
 for(let i=0;i<8;i++)streams.add('https://media.example.test/'+i);
 assert.equal((await streams.handle(new Request(old),()=>{})).status,404);
 const url=streams.add('https://media.example.test/new');
 const response=await streams.handle(new Request(url),()=>{throw Error('private-url')});
 assert.equal(response.status,502);assert.equal(await response.text(),'');
});
test('shared web model keeps independent motion and proximity regression tests',async()=>{
 const path=require('node:path');
 const result=require('node:child_process').spawnSync(process.execPath,['--test',path.resolve(__dirname,'../../../shared/wave/tests/web-simulation.test.mjs'),path.resolve(__dirname,'../../../shared/wave/tests/audio-features.test.mjs')],{encoding:'utf8'});
 assert.equal(result.status,0,result.stdout+result.stderr);
});
