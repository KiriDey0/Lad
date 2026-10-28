// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music
import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class UpdateProtocolTest {
    private val key=Ed25519PrivateKeyParameters(SecureRandom())
    private val publicKey=key.generatePublicKey().encoded.joinToString(""){"%02x".format(it)}
    private fun payload()=JSONObject("""{"schema":1,"notes":"Release","platforms":{"android":{"version":"0.3.0","build":30000,"packageId":"com.lad.music","artifact":{"name":"Lad.apk","url":"https://github.com/KiriDey0/Lad/releases/download/v0.3.0/Lad.apk","sha256":"${"a".repeat(64)}","size":1234}}}}""")
    private fun sign(p:JSONObject):String {val b=p.toString().toByteArray();val signer=Ed25519Signer();signer.init(true,key);signer.update(b,0,b.size);return JSONObject().put("schema",1).put("payload",Base64.getEncoder().encodeToString(b)).put("signature",Base64.getEncoder().encodeToString(signer.generateSignature())).toString()}
    private fun verify(e:String,version:String="0.2.99",code:Long=29999,platform:String="android",pkg:String="com.lad.music")=UpdateProtocol.verify(e,platform,version,code,pkg,publicKey)
    private fun rejects(block:()->Unit){try{block();fail("Invalid update accepted")}catch(e:IllegalArgumentException){}catch(e:org.json.JSONException){}catch(e:IllegalStateException){}}
    @Test fun signedUpdateIsSelected(){val r=verify(sign(payload()));assertTrue(r.newer);assertEquals("0.3.0",r.version);assertEquals(30000,r.build)}
    @Test fun installedOrOlderVersionIsNotAnUpdate(){assertFalse(verify(sign(payload()),"0.3.0",30000).newer);assertFalse(verify(sign(payload()),"0.4.0",40000).newer);assertFalse(verify(sign(payload()),"0.2.99",50000).newer)}
    @Test fun modifiedPayloadIsRejected(){val e=JSONObject(sign(payload()));val p=payload().put("notes","tampered");e.put("payload",Base64.getEncoder().encodeToString(p.toString().toByteArray()));rejects{verify(e.toString())}}
    @Test fun forgedSignatureIsRejected(){val e=JSONObject(sign(payload())).put("signature",Base64.getEncoder().encodeToString(ByteArray(64)));rejects{verify(e.toString())}}
    @Test fun wrongAppAndPlatformAreRejected(){rejects{verify(sign(payload()),pkg="com.other")};rejects{verify(sign(payload()),platform="android-tv-armv7")}}
    @Test fun otherRepositoryAndHttpAreRejected(){for(url in listOf("http://github.com/KiriDey0/Lad/releases/download/v0.3.0/Lad.apk","https://github.com/evil/Lad/releases/download/v0.3.0/Lad.apk","https://github.com/KiriDey0/Lad/releases/download/v0.3.0/Lad.apk?x=1")){val p=payload();p.getJSONObject("platforms").getJSONObject("android").getJSONObject("artifact").put("url",url);rejects{verify(sign(p))}}}
    @Test fun traversalAndCorruptHashAreRejected(){for(pair in listOf("name" to "../Lad.apk","sha256" to "invalid","name" to "Lad.exe")){val p=payload();p.getJSONObject("platforms").getJSONObject("android").getJSONObject("artifact").put(pair.first,pair.second);rejects{verify(sign(p))}}}
    @Test fun oversizedAndEmptyArtifactsAreRejected(){for(size in listOf(0L,-1L,2000000000L)){val p=payload();p.getJSONObject("platforms").getJSONObject("android").getJSONObject("artifact").put("size",size);rejects{verify(sign(p))}}}
    @Test fun numericVersionsAndInvalidInput(){assertTrue(UpdateProtocol.compareVersion("0.10.0","0.9.9")>0);for(v in listOf("v1.0.0","01.0.0","1.2","1.2.3-beta"))rejects{UpdateProtocol.compareVersion(v,"1.0.0")}}
}
