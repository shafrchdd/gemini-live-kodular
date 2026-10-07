package com.fabiyon.geminilive;

import android.os.Handler;
import android.os.Looper;
import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.*;
import org.json.JSONObject;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import android.util.Base64;

@DesignerComponent(version=1, description="Direct Gemini Live WebSocket client for Kodular. No proxy server required.", category=ComponentCategory.EXTENSION, nonVisible=true, iconName="")
@SimpleObject(external=true)
@UsesPermissions(permissionNames="android.permission.INTERNET")
public class GeminiLive extends AndroidNonvisibleComponent {
  private final Handler ui = new Handler(Looper.getMainLooper());
  private SSLSocket socket; private InputStream in; private OutputStream out;
  private volatile boolean connected=false, ready=false; private Thread reader;
  private String model="gemini-3.8-live";

  public GeminiLive(ComponentContainer container){ super(container.$form()); }

  @SimpleProperty(category=PropertyCategory.BEHAVIOR, description="Gemini Live model name.")
  public String Model(){ return model; }
  @DesignerProperty(editorType="string", defaultValue="gemini-3.8-live")
  @SimpleProperty(category=PropertyCategory.BEHAVIOR)
  public void Model(String value){ if(value!=null && !value.trim().isEmpty()) model=value.trim(); }

  @SimpleFunction(description="Connect directly to Gemini Live with an API key.")
  public void Connect(final String apiKey){
    if(apiKey==null || apiKey.trim().isEmpty()){ Error("API key is empty"); return; }
    Disconnect();
    new Thread(new Runnable() { public void run() {
      try {
        URI uri=new URI("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key="+apiKey.trim());
        socket=(SSLSocket)SSLSocketFactory.getDefault().createSocket(uri.getHost(),443);
        socket.startHandshake(); in=socket.getInputStream(); out=socket.getOutputStream();
        String key=makeKey();
        String req="GET "+uri.getRawPath()+"?"+uri.getRawQuery()+" HTTP/1.1\r\n"+
          "Host: "+uri.getHost()+"\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"+
          "Sec-WebSocket-Key: "+key+"\r\nSec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.US_ASCII)); out.flush();
        String headers=readHttpHeaders();
        String firstLine=headers;
        int firstEnd=headers.indexOf("\r\n");
        if(firstEnd>=0) firstLine=headers.substring(0,firstEnd);
        if(!firstLine.contains(" 101 ")) throw new IOException("WebSocket handshake failed: "+firstLine);
        connected=true; fireConnected();
        reader=new Thread(new Runnable() { public void run() { readLoop(); }}); reader.start();
        JSONObject setup=new JSONObject(); JSONObject body=new JSONObject();
        body.put("model","models/"+model);
        org.json.JSONArray modalities=new org.json.JSONArray(); modalities.put("AUDIO");
        JSONObject prebuilt=new JSONObject(); prebuilt.put("voiceName","Puck");
        JSONObject voiceConfig=new JSONObject(); voiceConfig.put("prebuiltVoiceConfig",prebuilt);
        JSONObject speechConfig=new JSONObject(); speechConfig.put("voiceConfig",voiceConfig);
        JSONObject generationConfig=new JSONObject(); generationConfig.put("responseModalities",modalities); generationConfig.put("speechConfig",speechConfig);
        body.put("generationConfig",generationConfig);
        setup.put("setup",body);
        sendFrame(setup.toString()); SetupSent(setup.toString());
      } catch(Exception e){ Error("Connect/setup: "+e.getMessage()); closeQuietly(); }
    }},"GeminiLive-connect").start();
  }

  @SimpleFunction public void SendText(final String text){
    if(!ready){ Error("Gemini is not ready. Wait for SetupComplete."); return; }
    new Thread(new Runnable() { public void run() {
      try {
        JSONObject part=new JSONObject().put("text",text);
        org.json.JSONArray parts=new org.json.JSONArray().put(part);
        JSONObject content=new JSONObject().put("role","user").put("parts",parts);
        org.json.JSONArray turns=new org.json.JSONArray().put(content);
        JSONObject tc=new JSONObject().put("turns",turns).put("turnComplete",true);
        JSONObject root=new JSONObject().put("clientContent",tc);
        sendFrame(root.toString());
      } catch(Exception e){
        String detail=e.toString();
        if(e.getMessage()!=null) detail += " | " + e.getMessage();
        Error("SendText: "+detail);
      }
    }},"GeminiLive-sendText").start();
  }

  @SimpleFunction public boolean IsConnected(){ return connected; }
  @SimpleFunction public boolean IsReady(){ return ready; }
  @SimpleFunction public void Disconnect(){ closeQuietly(); }

  @SimpleEvent public void Connected(){ EventDispatcher.dispatchEvent(this,"Connected"); }
  @SimpleEvent public void SetupSent(String json){ EventDispatcher.dispatchEvent(this,"SetupSent",json); }
  @SimpleEvent public void SetupComplete(){ EventDispatcher.dispatchEvent(this,"SetupComplete"); }
  @SimpleEvent public void RawMessage(String message){ EventDispatcher.dispatchEvent(this,"RawMessage",message); }
  @SimpleEvent public void TextReceived(String text){ EventDispatcher.dispatchEvent(this,"TextReceived",text); }
  @SimpleEvent public void Error(String message){ ui.post(new Runnable() { public void run() { EventDispatcher.dispatchEvent(GeminiLive.this,"Error",message); }}); }
  @SimpleEvent public void Disconnected(String reason){ EventDispatcher.dispatchEvent(this,"Disconnected",reason); }

  private void fireConnected(){ ui.post(new Runnable() { public void run() { Connected(); }}); }
  private String makeKey(){ byte[] b=new byte[16]; new SecureRandom().nextBytes(b); return Base64.encodeToString(b,Base64.NO_WRAP); }

  private synchronized void sendFrame(String s) throws IOException {
    if(!connected || out==null) throw new IOException("Not connected");
    byte[] p=s.getBytes(StandardCharsets.UTF_8); ByteArrayOutputStream f=new ByteArrayOutputStream();
    f.write(0x81); int n=p.length;
    if(n<=125) f.write(0x80|n); else if(n<=65535){ f.write(0x80|126); f.write((n>>>8)&255); f.write(n&255); }
    else { f.write(0x80|127); for(int i=7;i>=0;i--) f.write((int)(((long)n >>> (8*i))&255)); }
    byte[] mask=new byte[4]; new SecureRandom().nextBytes(mask); f.write(mask);
    for(int i=0;i<n;i++) f.write(p[i]^mask[i&3]);
    out.write(f.toByteArray()); out.flush();
  }

  private void readLoop(){
    StringBuilder fragmented=new StringBuilder();
    try {
      while(connected){
        int b1=in.read(); if(b1<0) break; int b2=in.read(); if(b2<0) break;
        boolean fin=(b1&0x80)!=0; int opcode=b1&0x0F; long len=b2&0x7F;
        if(len==126) len=((long)readByte()<<8)|readByte();
        else if(len==127){ len=0; for(int i=0;i<8;i++) len=(len<<8)|readByte(); }
        byte[] mask=null; if((b2&0x80)!=0){ mask=readN(4); }
        if(len>16*1024*1024) throw new IOException("Frame too large");
        byte[] data=readN((int)len); if(mask!=null) for(int i=0;i<data.length;i++) data[i]^=mask[i&3];
        if(opcode==8){ break; }
        if(opcode==9){ sendControl(10,data); continue; }
        // Gemini Live may return JSON in either TEXT (opcode 1) or BINARY
        // (opcode 2) WebSocket frames. The Python reference client receives
        // setupComplete/serverContent as bytes, so decode binary JSON as UTF-8.
        if(opcode==1 || opcode==2 || opcode==0){
          String chunk=new String(data,StandardCharsets.UTF_8);
          if((opcode==1 || opcode==2) && fin) handleMessage(chunk);
          else { fragmented.append(chunk); if(fin){ handleMessage(fragmented.toString()); fragmented.setLength(0); } }
        }
      }
    } catch(Exception e){ if(connected) Error("Read: "+e.toString()); }
    finally { boolean was=connected; closeQuietly(); if(was) ui.post(new Runnable() { public void run() { Disconnected("Socket closed"); }}); }
  }

  private String readHttpHeaders() throws IOException {
    ByteArrayOutputStream b=new ByteArrayOutputStream();
    int state=0;
    while(b.size()<32768){
      int x=in.read(); if(x<0) throw new EOFException("EOF during WebSocket handshake");
      b.write(x);
      if(state==0) state=(x=='\r')?1:0;
      else if(state==1) state=(x=='\n')?2:0;
      else if(state==2) state=(x=='\r')?3:0;
      else if(state==3 && x=='\n') break;
      else state=0;
    }
    if(state!=3) throw new IOException("WebSocket response headers too large");
    return new String(b.toByteArray(),StandardCharsets.US_ASCII);
  }

  private int readByte() throws IOException { int x=in.read(); if(x<0) throw new EOFException(); return x; }
  private byte[] readN(int n) throws IOException { byte[] b=new byte[n]; int o=0,r; while(o<n && (r=in.read(b,o,n-o))>0)o+=r; if(o<n)throw new EOFException(); return b; }
  private synchronized void sendControl(int opcode, byte[] p) throws IOException {
    ByteArrayOutputStream f=new ByteArrayOutputStream(); f.write(0x80|opcode); f.write(0x80|p.length);
    byte[] m=new byte[4]; new SecureRandom().nextBytes(m); f.write(m); for(int i=0;i<p.length;i++)f.write(p[i]^m[i&3]);
    out.write(f.toByteArray()); out.flush();
  }

  private void handleMessage(final String msg){
    ui.post(new Runnable() { public void run() { RawMessage(msg); }});
    try {
      JSONObject j=new JSONObject(msg);
      if(j.has("setupComplete")){ ready=true; ui.post(new Runnable() { public void run() { SetupComplete(); }}); return; }
      if(j.has("serverContent")){
        JSONObject sc=j.getJSONObject("serverContent");
        if(sc.has("modelTurn")){
          org.json.JSONArray ps=sc.getJSONObject("modelTurn").optJSONArray("parts");
          if(ps!=null) { for(int i=0;i<ps.length();i++){ String t=ps.getJSONObject(i).optString("text",""); if(!t.isEmpty()) { final String textPart=t; ui.post(new Runnable() { public void run() { TextReceived(textPart); }}); } } }
        }
      }
    } catch(Exception ignored){}
  }

  private synchronized void closeQuietly(){
    ready=false; connected=false;
    try{ if(socket!=null) socket.close(); }catch(Exception ignored){}
    socket=null; in=null; out=null;
  }
}