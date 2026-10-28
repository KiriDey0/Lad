// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

data class UpdateState(val phase:String="idle",val version:String="",val progress:Int=0,val message:String="",val notes:String="",val size:Long=0)
class AppUpdates(private val context:Context) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutable=MutableStateFlow(UpdateState())
    val state=mutable.asStateFlow()
    private val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(20,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(20,TimeUnit.MINUTES).build()
    private val dir=File(context.filesDir,"updates")
    private var task:Job?=null
    @Volatile private var call:Call?=null
    @Volatile private var release:UpdateRelease?=null
    private var envelope=""
    private var file:File?=null
    private var awaitingPermission=false
    private var installerLaunched=false
    private val platform=if(BuildConfig.APPLICATION_ID.contains(".tv")) "android-tv-armv7" else "android"
    init {
        task=scope.launch { runCatching {
            val text=File(dir,"ready.json").readText();val r=UpdateProtocol.verify(text,platform,BuildConfig.VERSION_NAME,BuildConfig.VERSION_CODE.toLong(),BuildConfig.APPLICATION_ID)
            if(r.newer) { val f=File(dir,r.name);if(f.length()==r.size){envelope=text;release=r;file=f;mutable.value=UpdateState("ready",r.version,100,notes=r.notes,size=r.size)} }
            else { File(dir,r.name).delete();File(dir,"ready.json").delete() }
        } }
    }
    private fun fetch(url:String,manifest:Boolean=false):Response {
        var next=url
        repeat(8) {
            val uri=Uri.parse(next)
            require(uri.scheme=="https"&&uri.host in listOf("github.com","release-assets.githubusercontent.com","objects.githubusercontent.com")) { "Небезопасный адрес загрузки" }
            val c=client.newCall(Request.Builder().url(next).header("User-Agent","Lad-Updater").build());call=c
            if(manifest)c.timeout().timeout(20,TimeUnit.SECONDS)
            val response=c.execute()
            if(response.code in listOf(301,302,303,307,308)) {
                val target=response.header("Location") ?: run{response.close();error("Не удалось открыть загрузку")}
                next=java.net.URI(next).resolve(target).toString();response.close()
            } else {
                if(!response.isSuccessful){val code=response.code;response.close();error(if(code==404)"Пока нет опубликованного обновления с поддержкой установки" else "GitHub недоступен ($code). Попробуй позже")}
                return response
            }
        }
        error("Слишком много перенаправлений")
    }
    fun check() {
        if(task?.isActive==true)return
        task=scope.launch {
            mutable.value=mutable.value.copy(phase="checking",message="",progress=0)
            try {
                val text=fetch(UpdateProtocol.FEED,true).use { r -> val out=java.io.ByteArrayOutputStream();r.body!!.byteStream().use { input -> val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=262144){"Описание обновления слишком большое"};out.write(buffer,0,n)} };String(out.toByteArray(),Charsets.UTF_8) }
                val r=UpdateProtocol.verify(text,platform,BuildConfig.VERSION_NAME,BuildConfig.VERSION_CODE.toLong(),BuildConfig.APPLICATION_ID)
                ensureActive();release=if(r.newer)r else null;envelope=text;file=null
                File(dir,"ready.json").delete()
                mutable.value=UpdateState(if(r.newer)"available" else "current",r.version,notes=r.notes,size=r.size)
            } catch(e:CancellationException){throw e} catch(e:Exception){if(currentCoroutineContext().isActive)mutable.value=mutable.value.copy(phase="error",message=e.message?:"Не удалось проверить обновления")}
        }
    }
    fun download() {
        if(task?.isActive==true)return
        val r=release ?: return
        task=scope.launch {
            dir.mkdirs();val part=File(dir,r.name+".part")
            mutable.value=mutable.value.copy(phase="downloading",progress=0,message="")
            try {
                val digest=MessageDigest.getInstance("SHA-256");var total=0L
                fetch(r.url).use { response -> response.body!!.byteStream().use { input -> part.outputStream().use { output ->
                    val buffer=ByteArray(65536)
                    while(true){ensureActive();val n=input.read(buffer);if(n<0)break;total+=n;require(total<=r.size){"Размер загрузки не совпадает с релизом"};digest.update(buffer,0,n);output.write(buffer,0,n);mutable.value=mutable.value.copy(progress=(total*100/r.size).toInt().coerceAtMost(99))}
                    output.fd.sync()
                } } }
                require(total==r.size&&digest.digest().joinToString(""){"%02x".format(it)}==r.sha256){"Файл повреждён: контрольная сумма не совпадает"}
                verifyApk(part,r);ensureActive()
                val final=File(dir,r.name);require(part.renameTo(final)){"Не удалось сохранить обновление"}
                File(dir,"ready.json").writeText(envelope);file=final
                mutable.value=mutable.value.copy(phase="ready",progress=100)
            } catch(e:CancellationException){throw e} catch(e:Exception){if(currentCoroutineContext().isActive)mutable.value=mutable.value.copy(phase="error",message=e.message?:"Не удалось скачать обновление")}
            finally{part.delete()}
        }
    }
    private fun hash(f:File):String {val digest=MessageDigest.getInstance("SHA-256");f.inputStream().use { input -> val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}};return digest.digest().joinToString(""){"%02x".format(it)}}
    @Suppress("DEPRECATION")
    private fun verifyApk(f:File,r:UpdateRelease) {
        val pm=context.packageManager
        val flags=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive=pm.getPackageArchiveInfo(f.absolutePath,flags) ?: error("Не удалось проверить APK")
        val installed=pm.getPackageInfo(context.packageName,flags)
        require(archive.packageName==context.packageName&&PackageInfoCompat.getLongVersionCode(archive)==r.build&&archive.versionName==r.version){"APK не соответствует версии приложения"}
        fun signatures(info:android.content.pm.PackageInfo)=if(Build.VERSION.SDK_INT>=28)info.signingInfo?.apkContentsSigners?.toList() else info.signatures?.toList()
        val old=signatures(installed);val fresh=signatures(archive)
        require(!old.isNullOrEmpty()&&old.toSet()==fresh?.toSet()){ "Подпись APK отличается от установленного Лада" }
        if(Build.VERSION.SDK_INT>=24)require(archive.applicationInfo?.minSdkVersion?.let{it<=Build.VERSION.SDK_INT}!=false){"Нужна более новая версия Android"}
    }
    fun cancel() {
        if(state.value.phase=="installing")return
        call?.cancel();task?.cancel();task=null
        mutable.value=mutable.value.copy(phase=if(release!=null)"available" else "idle",progress=0,message="")
    }
    fun install(activity:Activity) {
        val f=file ?: return;val r=release ?: return
        if(task?.isActive==true)return
        task=scope.launch {
            try {
                require(f.length()==r.size&&hash(f)==r.sha256){"Скачанный файл изменился. Загрузи его заново"};verifyApk(f,r)
                withContext(Dispatchers.Main) {
                    if(!context.packageManager.canRequestPackageInstalls()){
                        awaitingPermission=true
                        mutable.value=mutable.value.copy(message="Разреши Ладу установку обновлений и вернись в приложение")
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")))
                    } else launchInstaller(activity,f)
                }
            } catch(e:CancellationException){throw e} catch(e:Exception){if(currentCoroutineContext().isActive)mutable.value=mutable.value.copy(phase="error",message=e.message?:"Не удалось открыть установку")}
        }
    }
    private fun launchInstaller(activity:Activity,f:File) {
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.updates",f)
        val intent=Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        installerLaunched=true;mutable.value=mutable.value.copy(phase="installing",message="Подтверди установку в системном окне")
        activity.startActivity(intent)
    }
    fun onResume(activity:Activity) {
        if(awaitingPermission){awaitingPermission=false;if(context.packageManager.canRequestPackageInstalls())file?.let { runCatching { launchInstaller(activity,it) }.onFailure {mutable.value=mutable.value.copy(phase="ready",message="Нажми «Установить обновление» ещё раз")} }}
        else if(installerLaunched){installerLaunched=false;mutable.value=mutable.value.copy(phase="ready",message="Можно повторить установку")}
    }
}
