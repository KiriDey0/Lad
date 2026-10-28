#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-only
// Signing key is read only from the supplied private file, never printed or copied.
const fs=require('node:fs');const path=require('node:path');const crypto=require('node:crypto');
const root=path.resolve(__dirname,'..'),config=require('../release-config.json');
const [directory,keyFile,notesFile]=process.argv.slice(2);
if(!directory||!keyFile||!notesFile)throw Error('Usage: node scripts/sign-release.cjs <assets-directory> <private-key-file> <notes-text-file>');
const key=crypto.createPrivateKey(fs.readFileSync(keyFile));
const pub=crypto.createPublicKey(key).export({format:'der',type:'spki'}).subarray(-32).toString('hex');
if(pub!==fs.readFileSync(path.join(root,'update-public-key.txt'),'utf8').trim())throw Error('Signing key does not match the embedded public key');
const version=config.version;
const items={
 'macos-arm64':{name:`Lad-macOS-arm64-${version}.zip`},
 'windows-x64-installer':{name:`Lad-Windows-Setup-${version}-x64.exe`},
 'windows-x64-portable':{name:`Lad-Windows-Portable-${version}-x64.zip`},
 'android':{name:`Lad-Android-${version}.apk`,build:config.androidVersionCode,packageId:'com.lad.music'},
 'android-tv-armv7':{name:`Lad-TV-${version}-armv7.apk`,build:config.tvVersionCode,packageId:'com.lad.music.tv'},
};
const platforms={};
for(const [platform,{name,...extra}]of Object.entries(items)){
 const file=path.join(directory,name),data=fs.readFileSync(file);
 platforms[platform]={version,...extra,artifact:{name,url:`https://github.com/KiriDey0/Lad/releases/download/v${version}/${name}`,size:data.length,sha256:crypto.createHash('sha256').update(data).digest('hex')}};
}
const payload=Buffer.from(JSON.stringify({schema:1,issuedAt:new Date().toISOString(),notes:fs.readFileSync(notesFile,'utf8'),platforms}));
const manifest=JSON.stringify({schema:1,payload:payload.toString('base64'),signature:crypto.sign(null,payload,key).toString('base64')},null,2)+'\n';
fs.writeFileSync(path.join(directory,'updates.json'),manifest);
const {verifyManifest}=require('../apps/macos/electron/update-protocol.cjs');
for(const platform of Object.keys(platforms))verifyManifest(manifest,platform,'0.0.0');
console.log(`Signed and verified ${Object.keys(platforms).length} update artifacts for ${version}`);
