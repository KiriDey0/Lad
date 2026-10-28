// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import java.net.URI
import java.util.Base64
import org.json.JSONObject
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

data class UpdateRelease(val version:String,val build:Long,val name:String,val url:String,val sha256:String,val size:Long,val notes:String,val newer:Boolean)
object UpdateProtocol {
    const val PUBLIC_KEY = "8fbccb948b83fa90afc655dd1b5d43f32a2c14a404590385b95964392c311dac"
    const val FEED = "https://github.com/KiriDey0/Lad/releases/latest/download/updates.json"
    fun compareVersion(a:String,b:String):Int {
        fun parse(s:String):List<Int> {
            require(Regex("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)").matches(s)) { "Некорректная версия обновления" }
            return s.split('.').map { it.toInt().also { n -> require(n <= 100000) } }
        }
        val aa=parse(a);val bb=parse(b)
        for(i in 0..2)if(aa[i]!=bb[i])return aa[i].compareTo(bb[i])
        return 0
    }
    fun verify(envelope:String,platform:String,current:String,currentBuild:Long,packageId:String,key:String=PUBLIC_KEY):UpdateRelease {
        require(envelope.toByteArray().size<=262144) { "Описание обновления слишком большое" }
        val e=JSONObject(envelope)
        require(e.getInt("schema")==1) { "Формат обновления не поддерживается" }
        val bytes=Base64.getDecoder().decode(e.getString("payload"));val signature=Base64.getDecoder().decode(e.getString("signature"))
        val publicKey=key.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
        val verifier=Ed25519Signer();verifier.init(false,Ed25519PublicKeyParameters(publicKey,0));verifier.update(bytes,0,bytes.size)
        require(signature.size==64&&verifier.verifySignature(signature)) { "Подпись обновления не прошла проверку" }
        val m=JSONObject(String(bytes,Charsets.UTF_8));require(m.getInt("schema")==1)
        val notes=m.getString("notes");require(notes.length<=12000)
        val p=m.getJSONObject("platforms").optJSONObject(platform) ?: error("Для этого устройства пока нет подходящего обновления")
        require(p.getString("packageId")==packageId) { "Обновление предназначено для другого приложения" }
        val version=p.getString("version");val build=p.getLong("build");require(build in 1..2100000000L && (p.get("build") as? Number)?.toDouble()==build.toDouble()){ "Некорректный номер сборки" }
        val newer=compareVersion(version,current)>0&&build>currentBuild
        val a=p.getJSONObject("artifact");val name=a.getString("name");val url=a.getString("url");val hash=a.getString("sha256");val size=a.getLong("size")
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}\\.apk").matches(name)&&Regex("[a-f0-9]{64}").matches(hash)&&size in 1..1610612736L && (a.get("size") as? Number)?.toDouble()==size.toDouble()) { "Некорректные параметры файла обновления" }
        val u=URI(url)
        require(u.scheme=="https"&&u.host=="github.com"&&u.port==-1&&u.userInfo==null&&u.query==null&&u.fragment==null&&u.rawPath=="/KiriDey0/Lad/releases/download/v$version/$name") { "Неизвестный источник обновления" }
        return UpdateRelease(version,build,name,url,hash,size,notes,newer)
    }
}
