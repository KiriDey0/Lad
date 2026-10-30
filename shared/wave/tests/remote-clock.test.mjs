import {test} from 'node:test';import assert from 'node:assert/strict';
import {remoteTarget,remoteCorrection} from '../remote-clock.mjs';
test('remote clock extrapolates only fresh running playback',()=>{
 const p={position:30,positionAt:10000,playing:true,transitioning:false,duration:200};
 assert.equal(remoteTarget(p,11250),31.25);
 assert.equal(remoteTarget({...p,playing:false},11250),null);
 assert.equal(remoteTarget({...p,transitioning:true},11250),null);
 assert.equal(remoteTarget(p,15001),null);
 assert.equal(remoteTarget({...p,position:199},12000),200);
});
test('seeks correct remote jumps while small errors use bounded speed changes',()=>{
 assert.deepEqual(remoteCorrection(30,20),{seek:30,rate:1});
 assert.deepEqual(remoteCorrection(10,30),{seek:10,rate:1});
 assert.deepEqual(remoteCorrection(30,30),{seek:null,rate:1});
 assert.ok(remoteCorrection(30.4,30).rate>1);
 assert.ok(remoteCorrection(29.6,30).rate<1);
 for(let i=-650;i<=650;i++){const c=remoteCorrection(30+i/1000,30);assert.ok(c.rate>=.96&&c.rate<=1.04);}
});
