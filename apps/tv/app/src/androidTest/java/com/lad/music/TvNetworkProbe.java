// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music;
import android.test.ActivityInstrumentationTestCase2;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.json.*;
/** Public unauthenticated endpoint probe. Never reads a session or a response body. */
@SuppressWarnings("deprecation")
public class TvNetworkProbe extends ActivityInstrumentationTestCase2<MainActivity> {
 public TvNetworkProbe(){super(MainActivity.class);}
 public void testPublicQuasarTransport() throws Exception {
  MainActivity a=getActivity();JSONArray rows=new JSONArray();
  for(String mode: new String[]{"default","http1","tls12","http1-tls12"}) {
   JSONObject row=new JSONObject().put("mode",mode);
   OkHttpClient.Builder b=new OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS).callTimeout(10,TimeUnit.SECONDS);
   if(mode.contains("http1")) b.protocols(Arrays.asList(Protocol.HTTP_1_1));
   if(mode.contains("tls12")) b.connectionSpecs(Arrays.asList(new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_2).build()));
   OkHttpClient client=b.build();long start=System.currentTimeMillis();
   try(Response r=client.newCall(new Request.Builder().url("https://quasar.yandex.net/glagol/device_list").header("User-Agent","LadAndroid/0.1").build()).execute()) {row.put("status",r.code());row.put("protocol",r.protocol().toString());if(r.handshake()!=null)row.put("tls",r.handshake().tlsVersion().javaName());}
   catch(Exception e){row.put("error",e.getClass().getSimpleName()+": "+e.getMessage());if(e.getCause()!=null)row.put("cause",e.getCause().getClass().getSimpleName()+": "+e.getCause().getMessage());}
   finally {client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}
   row.put("ms",System.currentTimeMillis()-start);rows.put(row);
  }
  File f=new File(a.getExternalFilesDir(null),"network-probe.json");try(FileOutputStream out=new FileOutputStream(f)){out.write(rows.toString(2).getBytes(StandardCharsets.UTF_8));}
 }
}
