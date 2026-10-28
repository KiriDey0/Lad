// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music;

import android.test.ActivityInstrumentationTestCase2;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Runs on the actual Hartens. No account or other packages are accessed. */
@SuppressWarnings("deprecation")
public class TvRemoteTest extends ActivityInstrumentationTestCase2<MainActivity> {
 public TvRemoteTest(){super(MainActivity.class);}
 private MainActivity a;
 @Override public void setUp() throws Exception {super.setUp();setActivityInitialTouchMode(false);a=getActivity();getInstrumentation().waitForIdleSync();Thread.sleep(800);}
 private View root(){return a.findViewById(android.R.id.content);}
 private View find(String tag){return root().findViewWithTag(tag);}
 private void key(int code) throws Exception {getInstrumentation().sendKeyDownUpSync(code);getInstrumentation().waitForIdleSync();Thread.sleep(200);}
 private void click(String tag) throws Exception {View v=find(tag);assertNotNull("Missing control "+tag,v);getInstrumentation().runOnMainSync(()->v.requestFocus());key(KeyEvent.KEYCODE_DPAD_CENTER);Thread.sleep(300);}
 private String focused(){View v=root().findFocus();return v==null?"":String.valueOf(v.getTag());}
 private boolean text(View v,String part){if(v instanceof TextView && ((TextView)v).getText().toString().contains(part))return true;if(v instanceof ViewGroup){for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(text(((ViewGroup)v).getChildAt(i),part))return true;}return false;}
 private void demo() throws Exception {if(find("demo")!=null)click("demo");Thread.sleep(600);}
 public void testKeyboardLayoutsAndPhysicalDpad() throws Exception {
  demo();click("nav-search");assertNotNull(find("key-0-11"));click("key-0-3");assertTrue(text(root(),"к |"));key(KeyEvent.KEYCODE_DPAD_RIGHT);assertEquals("key-0-4",focused());key(KeyEvent.KEYCODE_DPAD_CENTER);assertTrue(text(root(),"ке |"));click("key-lang");assertNotNull(find("key-0-9"));assertNull(find("key-0-11"));assertTrue(text(root(),"Q"));assertEquals("key-lang",focused());click("key-digits");assertTrue(text(root(),"1"));click("key-delete");assertTrue(text(root(),"к |"));
 }
 public void testPlayerTransportAndSeekCancel() throws Exception {
  demo();click("nav-player");assertNotNull(find("pause"));click("pause");getInstrumentation().runOnMainSync(()->find("pause").requestFocus());key(KeyEvent.KEYCODE_DPAD_UP);assertEquals("seek",focused());key(KeyEvent.KEYCODE_DPAD_CENTER);key(KeyEvent.KEYCODE_DPAD_RIGHT);assertTrue(text(root(),"ПЕРЕМОТКА"));key(KeyEvent.KEYCODE_BACK);assertFalse(text(root(),"ПЕРЕМОТКА"));assertEquals("seek",focused());click("next");assertNotNull(find("pause"));click("queue");assertTrue(text(root(),"Очередь"));click("queue-5");assertNotNull(find("queue-5"));key(KeyEvent.KEYCODE_BACK);assertNotNull(find("pause"));
 }
 public void testModalFocusTrapAndBackRestoration() throws Exception {
  demo();click("nav-player");click("more");assertNotNull(find("more-output"));key(KeyEvent.KEYCODE_DPAD_LEFT);assertTrue(focused().startsWith("more-"));key(KeyEvent.KEYCODE_BACK);assertEquals("more",focused());click("queue");click("queue-2-more");click("action-move");key(KeyEvent.KEYCODE_DPAD_DOWN);assertTrue(text(root(),"Перемещаем"));key(KeyEvent.KEYCODE_BACK);assertFalse(text(root(),"Перемещаем"));
 }
 public void testCollectionBackPreservesCardFocus() throws Exception {
  demo();click("nav-library");Thread.sleep(800);click("lib-0-1");assertNotNull(find("collection-play"));key(KeyEvent.KEYCODE_BACK);assertEquals("lib-0-1",focused());
 }
 public void testNativeAudioDecoderAndTransport() throws Exception {
  demo();
  byte[] wav=new byte[44+32000];
  java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN);
  b.put("RIFF".getBytes("US-ASCII"));b.putInt(wav.length-8);b.put("WAVEfmt ".getBytes("US-ASCII"));b.putInt(16);b.putShort((short)1);b.putShort((short)1);b.putInt(8000);b.putInt(16000);b.putShort((short)2);b.putShort((short)16);b.put("data".getBytes("US-ASCII"));b.putInt(32000);
  for(int i=0;i<16000;i++)b.putShort((short)(Math.sin(i*2*Math.PI*440/8000)*500));
  String uri="data:audio/wav;base64,"+android.util.Base64.encodeToString(wav,android.util.Base64.NO_WRAP);
  MusicEngine e=((LadApplication)a.getApplication()).getEngine();
  java.util.concurrent.atomic.AtomicLong pos=new java.util.concurrent.atomic.AtomicLong();
  try {
   getInstrumentation().runOnMainSync(()->{e.getPlayer().setVolume(0);e.getPlayer().setMediaItem(androidx.media3.common.MediaItem.fromUri(uri));e.getPlayer().prepare();e.getPlayer().play();});
   for(int i=0;i<30;i++){Thread.sleep(100);getInstrumentation().runOnMainSync(()->pos.set(e.getPlayer().getCurrentPosition()));if(pos.get()>200)break;}
   assertTrue("Native decoder must advance",pos.get()>200);
   getInstrumentation().runOnMainSync(()->{e.getPlayer().pause();e.getPlayer().seekTo(800);});Thread.sleep(200);
   getInstrumentation().runOnMainSync(()->pos.set(e.getPlayer().getCurrentPosition()));assertTrue(pos.get()>=750&&pos.get()<950);
  } finally {getInstrumentation().runOnMainSync(()->e.getPlayer().stop());}
 }

 private void focus(String tag) throws Exception {View v=find(tag);assertNotNull(tag,v);getInstrumentation().runOnMainSync(()->v.requestFocus());getInstrumentation().waitForIdleSync();}
 private void bounds(String tag,int x,int y,int w,int h) {View v=find(tag);assertNotNull(tag,v);int[] loc=new int[2];v.getLocationOnScreen(loc);assertEquals(tag+" x",x,loc[0]);assertEquals(tag+" y",y,loc[1]);assertEquals(tag+" width",w,v.getWidth());assertEquals(tag+" height",h,v.getHeight());}
 private void shot(String name) throws Exception {Thread.sleep(800);android.graphics.Bitmap b=getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(b);java.io.File dir=new java.io.File(a.getExternalFilesDir(null),"parity");dir.mkdirs();try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,name+".png"))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
 public void testSettingsDesignAndSubmenus() throws Exception {
  assertEquals("Tests must use isolated QA profile","com.lad.music.tv.qa",a.getPackageName());
  demo();click("nav-settings");focus("setting-filter");
  String[] rows={"setting-filter","setting-appearance","setting-glass","setting-scale","setting-ambient","setting-account"};
  for(int i=0;i<rows.length;i++) {bounds(rows[i],248,244+i*88,1576,72);ViewGroup r=(ViewGroup)find(rows[i]);assertEquals(3,r.getChildCount());TextView title=(TextView)r.getChildAt(0), value=(TextView)r.getChildAt(1);assertEquals(24f,title.getTextSize(),.01f);assertEquals(20f,value.getTextSize(),.01f);assertTrue((value.getGravity()&android.view.Gravity.END)==android.view.Gravity.END);assertEquals(android.graphics.Color.rgb(170,175,185),value.getCurrentTextColor());}
  bounds("nav-settings",96,480,64,64);assertNull(find("setting-oled"));shot("settings");
  click("setting-appearance");shot("appearance");assertNotNull(find("appearance-oled"));if(text(root(),"OLED · выключен"))click("appearance-oled");assertTrue("OLED must enable",text(root(),"OLED · включён"));click("appearance-oled");assertTrue("OLED must disable",text(root(),"OLED · выключен"));key(KeyEvent.KEYCODE_BACK);assertEquals("setting-appearance",focused());
  click("setting-ambient");shot("ambient-settings");click("ambient-60");assertTrue(text(root(),"Через 60 секунд"));click("setting-ambient");click("ambient-30");
  click("setting-account");shot("account");assertNotNull(find("account-logout"));key(KeyEvent.KEYCODE_BACK);
 }
 public void testVisualParityMainScreens() throws Exception {
  demo();click("nav-home");focus("wave");shot("home");
  bounds("wave",276,321,374,64);bounds("mini-open",248,904,1576,88);assertNull(find("mini-toggle"));assertNull(find("mini-queue"));
  click("nav-library");focus("liked-play");shot("collection");bounds("liked-play",1445,244,168,64);bounds("liked-open",1637,244,167,64);bounds("tab-0",248,368,158,56);
  click("lib-0-1");focus("track-0");shot("playlist");bounds("track-0",248,462,1576,92);bounds("collection-play",536,354,239,64);
  key(KeyEvent.KEYCODE_BACK);click("nav-player");focus("pause");shot("player");bounds("pause",444,894,197,64);bounds("previous",112,894,312,64);bounds("next",661,894,290,64);bounds("text",1172,894,190,64);bounds("queue",1382,894,238,64);bounds("more",1640,894,168,64);assertNull(find("lyrics-open"));
  click("text");shot("lyrics");key(KeyEvent.KEYCODE_BACK);click("queue");focus("queue-0");shot("queue");click("queue-2-more");shot("track-actions");key(KeyEvent.KEYCODE_BACK);key(KeyEvent.KEYCODE_BACK);click("more");click("more-output");shot("devices");key(KeyEvent.KEYCODE_BACK);
 }

 public void testCompactCollectionAndStableTabs() throws Exception {
  demo();click("nav-library");View firstTab=find("tab-0"), mini=find("mini-open");
  bounds("lib-0-0",248,456,200,264);
  int[] dockPos=new int[2];mini.getLocationOnScreen(dockPos);View card=find("lib-0-0");int[] cp=new int[2];card.getLocationOnScreen(cp);assertTrue("Entire card must fit above dock",cp[1]+card.getHeight()+32<=dockPos[1]);
  focus("tab-1");getInstrumentation().runOnMainSync(()->find("tab-1").performClick());
  for(int i=0;i<8;i++){Thread.sleep(30);assertSame("Tabs must retain their Views",firstTab,find("tab-0"));assertSame("Dock must not be recreated",mini,find("mini-open"));bounds("tab-0",248,368,158,56);assertEquals("tab-1",focused());}
  click("tab-2");click("tab-0");assertSame(firstTab,find("tab-0"));bounds("lib-0-0",248,456,200,264);shot("compact-tabs");
 }

 public void testOutputPickerDiscoveryAndRoundTrip() throws Exception {
  demo();click("nav-home");focus("wave");key(KeyEvent.KEYCODE_DPAD_UP);assertEquals("output-picker",focused());key(KeyEvent.KEYCODE_DPAD_DOWN);assertEquals("wave",focused());
  click("output-picker");
  for(int i=0;i<30&&find("device-demo-midi")==null;i++)Thread.sleep(100);
  assertNotNull("Discovered Station must appear without reopening picker",find("device-demo-midi"));shot("station-picker");key(KeyEvent.KEYCODE_BACK);assertEquals("output-picker",focused());
  click("nav-player");click("seek");key(KeyEvent.KEYCODE_DPAD_RIGHT);key(KeyEvent.KEYCODE_DPAD_RIGHT);key(KeyEvent.KEYCODE_DPAD_RIGHT);key(KeyEvent.KEYCODE_DPAD_CENTER);
  MusicEngine e=((LadApplication)a.getApplication()).getEngine();long position=e.getState().getValue().getPositionMs();assertTrue(position>=15000);
  click("output-picker");click("device-demo-midi");assertEquals("station",e.getState().getValue().getOutput());assertEquals(position,e.getState().getValue().getPositionMs());assertTrue(text(root(),"Звук: Станция Миди"));assertNotNull(find("pause"));shot("station-player");
  click("output-picker");click("device-tv");assertEquals("phone",e.getState().getValue().getOutput());assertEquals(position,e.getState().getValue().getPositionMs());assertTrue(text(root(),"Звук: этот телевизор"));
 }

}
