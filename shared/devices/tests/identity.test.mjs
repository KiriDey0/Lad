// SPDX-License-Identifier: AGPL-3.0-only
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {deviceTitle,deviceLabel,deviceContext,deviceAmbiguous} from '../identity.mjs';
const kitchen={id:'a',name:'Яндекс Станция',model:'Яндекс Станция',room:'Кухня',household:'Дом',metadataStatus:'ready'};
test('room is prominent for model names; a custom name retains the room nearby',()=>{
 assert.equal(deviceTitle(kitchen),'Кухня');assert.equal(deviceLabel(kitchen),'Кухня · Дом');
 assert.equal(deviceContext(kitchen),'Яндекс Станция · Дом');
 const named={...kitchen,name:'Топаз'};
 assert.equal(deviceTitle(named),'Топаз');assert.equal(deviceLabel(named),'Топаз · Кухня · Дом');
 assert.equal(deviceContext(named),'Кухня · Яндекс Станция · Дом');
});
test('only actual display identity separates same-model devices',()=>{
 assert.equal(deviceAmbiguous(kitchen,[kitchen,{...kitchen,id:'b',room:'Спальня'}]),false);
 assert.equal(deviceAmbiguous(kitchen,[kitchen,{...kitchen,id:'b'}]),true);
 const missing={...kitchen,room:'',household:''};
 assert.equal(deviceAmbiguous(missing,[missing,{...missing,id:'b',name:'  ЯНДЕКС  СТАНЦИЯ  '}]),true);
 assert.equal(deviceAmbiguous(missing,[missing]),false);
 assert.equal(deviceAmbiguous(kitchen,[kitchen,{...kitchen,id:'b',household:'Другой дом'}]),false);
});
test('missing metadata is stated explicitly; never replaced by guessed rooms',()=>{
 assert.match(deviceContext({...kitchen,room:'',metadataStatus:'unavailable'}),/Комната недоступна/);
 assert.match(deviceContext({...kitchen,room:''}),/Комната не указана/);
 assert.equal(deviceLabel(undefined),'Станция');
});
