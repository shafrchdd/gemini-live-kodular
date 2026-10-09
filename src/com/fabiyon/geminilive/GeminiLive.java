package com.fabiyon.geminilive;

import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.*;
import org.json.JSONObject;
import org.json.JSONException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import android.util.Base64;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import com.google.appinventor.components.runtime.util.YailList;
import com.google.appinventor.components.runtime.PermissionResultHandler;
import java.net.URL;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileInputStream;
import java.net.Socket;
import java.net.InetSocketAddress;
import javax.net.ssl.SSLParameters;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

@DesignerComponent(version=5, description="Direct Gemini Live WebSocket client for Kodular. No proxy server required.", category=ComponentCategory.EXTENSION, nonVisible=true, iconName="")
@SimpleObject(external=true)
@UsesPermissions(permissionNames="android.permission.INTERNET, android.permission.RECORD_AUDIO")
public class GeminiLive extends AndroidNonvisibleComponent {
  private final Handler ui = new Handler(Looper.getMainLooper());
  private SSLSocket socket; private InputStream in; private OutputStream out;
  private volatile boolean connected=false, ready=false; private Thread reader;
  private String model="gemini-3.8-live";
  private String voice="Puck";
  private String systemInstruction="";
  private String apiKey="";
  private org.json.JSONArray functionDeclarations=new org.json.JSONArray();
  private final Object funcLock=new Object();
  private volatile boolean listening=false;
  private volatile int micGeneration=0;
  private final Object micLock=new Object();
  private AudioRecord audioRecord;
  private Thread micThread;
  private volatile boolean echoCancellation=true;
  private volatile boolean noiseSuppression=true;
  private volatile boolean muteMicWhileSpeaking=false;
  private volatile boolean assistantSpeaking=false;
  private volatile boolean audioEnabled=true;
  private volatile float volume=1.0f;
  private AudioTrack audioTrack;
  private final Object audioLock=new Object();
  private final ArrayBlockingQueue<byte[]> playQueue=new ArrayBlockingQueue<byte[]>(80);
  private Thread playThread;
  private volatile int playbackGeneration=0;
  private volatile int session=0;
  private final Object connectionLock=new Object();
  private volatile boolean debugRawMessages=false;

  public GeminiLive(ComponentContainer container){ super(container.$form()); }

  @SimpleProperty(category=PropertyCategory.BEHAVIOR, description="Emit raw server JSON to the UI; disabled by default to avoid huge audio events.")
  public boolean DebugRawMessages(){ return debugRawMessages; }
  @SimpleProperty(category=PropertyCategory.BEHAVIOR)
  public void DebugRawMessages(boolean value){ debugRawMessages=value; }

  @SimpleProperty(category=PropertyCategory.BEHAVIOR, description="Gemini Live model name.")
  public String Model(){ return model; }
  @DesignerProperty(editorType="string", defaultValue="gemini-3.8-live")
  @SimpleProperty(category=PropertyCategory.BEHAVIOR)
  public void Model(String value){ if(value!=null && !value.trim().isEmpty()) model=value.trim(); }

  @SimpleProperty(category=PropertyCategory.BEHAVIOR, description="Gemini prebuilt voice name used for the next connection.")
  public String Voice(){ return voice; }
  @DesignerProperty(editorType="string", defaultValue="Puck")
  @SimpleProperty(category=PropertyCategory.BEHAVIOR)
  public void Voice(String value){ if(value!=null && !value.trim().isEmpty()) voice=value.trim(); }

  @SimpleProperty(category=PropertyCategory.BEHAVIOR, description="System instruction sent when the next Live session connects.")
  public String SystemInstruction(){ return systemInstruction; }
  @DesignerProperty(editorType="textArea", defaultValue="")
  @SimpleProperty(category=PropertyCategory.BEHAVIOR)
  public void SystemInstruction(String value){ systemInstruction=value==null?"":value; }

  @SimpleFunction(description="Fetch models available to this API key. Use ModelsLoaded to fill a Spinner/ListPicker dynamically.")
  public void RefreshModels(final String key){ fetchCatalog(key,true); }

  @SimpleFunction(description="Fetch voices available to this API key. Use VoicesLoaded to fill a Spinner/ListPicker dynamically.")
  public void RefreshVoices(final String key){ fetchCatalog(key,false); }

  @SimpleEvent public void ModelsLoaded(YailList models){ EventDispatcher.dispatchEvent(this,"ModelsLoaded",models); }
  @SimpleEvent public void VoicesLoaded(YailList voices){ EventDispatcher.dispatchEvent(this,"VoicesLoaded",voices); }

  @SimpleFunction(description="Remove all function declarations that will be sent on the next connection.")
  public void ClearFunctions(){ synchronized(funcLock){ functionDeclarations=new org.json.JSONArray(); } }

  @SimpleFunction(description="Add a Gemini function. parametersJson must be a JSON Schema object, for example {\"type\":\"object\",\"properties\":{}}. Add functions before Connect.")
  public void AddFunction(String name,String description,String parametersJson){
    try{
      JSONObject d=new JSONObject().put("name",name).put("description",description==null?"":description);
      if(parametersJson!=null && !parametersJson.trim().isEmpty()) d.put("parameters",new JSONObject(parametersJson));
      synchronized(funcLock){ functionDeclarations.put(d); }
    }catch(Exception e){ Error("AddFunction: "+e.toString()); }
  }

  @SimpleEvent public void FunctionCall(String name,String argumentsJson,String callId){
    EventDispatcher.dispatchEvent(this,"FunctionCall",name,argumentsJson,callId);
  }

  @SimpleFunction(description="Return the result of a requested function to Gemini.")
  public void SendFunctionResult(final String callId,final String name,final String resultJson){
    if(!ready){ Error("Gemini is not ready."); return; }
    new Thread(new Runnable(){ public void run(){
      try{
        JSONObject response;
        try{ response=new JSONObject(resultJson); }catch(Exception x){ response=new JSONObject().put("result",resultJson); }
        JSONObject fr=new JSONObject().put("id",callId).put("name",name).put("response",response);
        org.json.JSONArray arr=new org.json.JSONArray().put(fr);
        sendFrame(new JSONObject().put("toolResponse",new JSONObject().put("functionResponses",arr)).toString());
      }catch(Exception e){ Error("SendFunctionResult: "+e.toString()); }
    }},"GeminiLive-toolResult").start();
  }

  @SimpleProperty(category=PropertyCategory.BEHAVIOR, description="Enable Android acoustic echo cancellation when supported by the device.")
  public boolean EchoCancellation(){ return echoCancellation; }
  @DesignerProperty(editorType="boolean", defaultValue="True")
  @SimpleProperty(category=PropertyCategory.BEHAVIOR)
  public void EchoCancellation(boolean value){ echoCancellation=value; }

  @SimpleProperty(category=PropertyCategory.BEHAVIOR, description="Enable Android microphone noise suppression when supported by the device.")
  public boolean NoiseSuppression(){ return noiseSuppression; }
  @DesignerProperty(editorType="boolean", defaultValue="True")
  @SimpleProperty(category=PropertyCategory.BEHAVIOR)
  public void NoiseSuppression(boolean value){ noiseSuppression=value; }

  @SimpleProperty(category=PropertyCategory.BEHAVIOR, description="Fallback: do not send microphone PCM while Gemini audio is playing. Prevents self-hearing but disables barge-in during playback.")
  public boolean MuteMicWhileSpeaking(){ return muteMicWhileSpeaking; }
  @DesignerProperty(editorType="boolean", defaultValue="False")
  @SimpleProperty(category=PropertyCategory.BEHAVIOR)
  public void MuteMicWhileSpeaking(boolean value){ muteMicWhileSpeaking=value; }

  @SimpleFunction public boolean IsEchoCancellationAvailable(){ return AcousticEchoCanceler.isAvailable(); }
  @SimpleFunction public boolean IsNoiseSuppressionAvailable(){ return NoiseSuppressor.isAvailable(); }

  @SimpleFunction(description="Start microphone only when explicitly requested.")
  public void StartListening(){
    if(!ready){ ModeError("VOICE","NOT_READY","Wait for SetupComplete"); return; }
    final int requestGeneration;
    synchronized(micLock){ if(listening) return; requestGeneration=++micGeneration; }
    form.askPermission("android.permission.RECORD_AUDIO",new PermissionResultHandler(){
      public void HandlePermissionResponse(String permission,boolean granted){
        if(!granted){ ModeError("VOICE","PERMISSION_DENIED","Microphone permission denied"); return; }
        if(requestGeneration!=micGeneration || !ready) return;
        startMicInternal();
      }
    });
  }

  @SimpleFunction(description="Stop the microphone without disconnecting Gemini. Safe to call repeatedly.")
  public void StopListening(){
    final AudioRecord recorder;
    final boolean wasListening;
    synchronized(micLock){
      wasListening=listening;
      listening=false;
      micGeneration++;
      recorder=audioRecord;
    }
    if(recorder!=null) try{ recorder.stop(); }catch(Exception ignored){}
    if(wasListening && ready && connected) new Thread(new Runnable(){ public void run(){
      try{ if(ready && connected) sendFrame(new JSONObject().put("realtimeInput",new JSONObject().put("audioStreamEnd",true)).toString()); }
      catch(IOException ignored){} catch(Exception e){ if(ready && connected) ModeError("VOICE","STREAM_END_FAILED",e.toString()); }
    }},"GeminiLive-audioEnd").start();
  }

  @SimpleEvent public void ModeError(String mode,String code,String message){
    final String m=mode,c=code,t=message;
    ui.post(new Runnable(){ public void run(){ EventDispatcher.dispatchEvent(GeminiLive.this,"ModeError",m,c,t); }});
  }

  @SimpleFunction public boolean IsListening(){ return listening; }
  @SimpleEvent public void ListeningStarted(){ EventDispatcher.dispatchEvent(this,"ListeningStarted"); }
  @SimpleEvent public void ListeningStopped(){ EventDispatcher.dispatchEvent(this,"ListeningStopped"); }
  @SimpleEvent public void InputTranscription(String text){ EventDispatcher.dispatchEvent(this,"InputTranscription",text); }

  @SimpleFunction(description="Connect directly to Gemini Live with an API key.")
  public void Connect(final String apiKey){
    if(apiKey==null || apiKey.trim().isEmpty()){ Error("API key is empty"); return; }
    Disconnect();
    this.apiKey=apiKey.trim();
    final int mySession=session;
    final String functionsSnapshot;
    synchronized(funcLock){ functionsSnapshot=functionDeclarations.toString(); }
    new Thread(new Runnable() { public void run() {
      try {
        URI uri=new URI("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key="+apiKey.trim());
        Socket plain=new Socket(); plain.connect(new InetSocketAddress(uri.getHost(),443),10000);
        SSLSocketFactory factory=(SSLSocketFactory)SSLSocketFactory.getDefault();
        SSLSocket fresh=(SSLSocket)factory.createSocket(plain,uri.getHost(),443,true);
        SSLParameters params=fresh.getSSLParameters(); params.setEndpointIdentificationAlgorithm("HTTPS"); fresh.setSSLParameters(params);
        fresh.setSoTimeout(20000); fresh.startHandshake();
        if(mySession!=session){ fresh.close(); return; }
        socket=fresh; in=fresh.getInputStream(); out=fresh.getOutputStream();
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
        if(mySession!=session) return;
        socket.setSoTimeout(0);
        connected=true; fireConnected();
        reader=new Thread(new Runnable() { public void run() { readLoop(mySession); }}); reader.start();
        JSONObject setup=new JSONObject(); JSONObject body=new JSONObject();
        body.put("model",model.startsWith("models/")?model:"models/"+model);
        org.json.JSONArray modalities=new org.json.JSONArray(); modalities.put("AUDIO");
        JSONObject prebuilt=new JSONObject(); prebuilt.put("voiceName",voice);
        JSONObject voiceConfig=new JSONObject(); voiceConfig.put("prebuiltVoiceConfig",prebuilt);
        JSONObject speechConfig=new JSONObject(); speechConfig.put("voiceConfig",voiceConfig);
        JSONObject generationConfig=new JSONObject(); generationConfig.put("responseModalities",modalities); generationConfig.put("speechConfig",speechConfig);
        body.put("generationConfig",generationConfig);
        if(!systemInstruction.trim().isEmpty()){
          JSONObject sip=new JSONObject().put("text",systemInstruction);
          body.put("systemInstruction",new JSONObject().put("parts",new org.json.JSONArray().put(sip)));
        }
        if(!"[]".equals(functionsSnapshot)){
          JSONObject tool=new JSONObject().put("functionDeclarations",new org.json.JSONArray(functionsSnapshot));
          body.put("tools",new org.json.JSONArray().put(tool));
        }
        body.put("inputAudioTranscription",new JSONObject());
        body.put("outputAudioTranscription",new JSONObject());
        setup.put("setup",body);
        sendFrame(setup.toString()); SetupSent(setup.toString());
      } catch(Exception e){ if(mySession==session){ Error("Connect/setup: "+e.getMessage()); closeQuietly(); } }
    }},"GeminiLive-connect").start();
  }

  @SimpleFunction public void SendText(final String text){
    if(!ready){ ModeError("TEXT","NOT_READY","Wait for SetupComplete"); return; }
    if(text==null || text.trim().isEmpty()){ ModeError("TEXT","EMPTY_TEXT","Text prompt is empty"); return; }
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
        ModeError("TEXT","SEND_FAILED",detail);
      }
    }},"GeminiLive-sendText").start();
  }

  @SimpleFunction(description="Send prompt plus image file path; microphone is not started.")
  public void SendTextWithImage(final String prompt,final String imagePath){
    if(!ready){ ModeError("IMAGE","NOT_READY","Wait for SetupComplete"); return; }
    if(imagePath==null || imagePath.trim().isEmpty()){ ModeError("IMAGE","INVALID_PATH","Image path is empty"); return; }
    new Thread(new Runnable(){ public void run(){
      try{
        String clean=imagePath.startsWith("file://")?imagePath.substring(7):imagePath;
        File file=new File(clean);
        if(!file.isFile()) throw new IOException("Image file not found: "+clean);
        BitmapFactory.Options options=new BitmapFactory.Options(); options.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(clean,options);
        options.inSampleSize=1;
        while(Math.max(options.outWidth/options.inSampleSize,options.outHeight/options.inSampleSize)>1536) options.inSampleSize*=2;
        options.inJustDecodeBounds=false;
        Bitmap bmp=BitmapFactory.decodeFile(clean,options);
        if(bmp==null) throw new IOException("Unsupported image or unreadable file");
        int max=Math.max(bmp.getWidth(),bmp.getHeight());
        if(max>1024){ float scale=1024f/max; Bitmap small=Bitmap.createScaledBitmap(bmp,Math.max(1,(int)(bmp.getWidth()*scale)),Math.max(1,(int)(bmp.getHeight()*scale)),true); bmp.recycle(); bmp=small; }
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        if(!bmp.compress(Bitmap.CompressFormat.JPEG,85,bytes)) throw new IOException("Image encoding failed");
        bmp.recycle();
        JSONObject image=new JSONObject().put("inlineData",new JSONObject().put("mimeType","image/jpeg").put("data",Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP)));
        org.json.JSONArray parts=new org.json.JSONArray();
        parts.put(new JSONObject().put("text",prompt==null?"":prompt)); parts.put(image);
        JSONObject content=new JSONObject().put("role","user").put("parts",parts);
        JSONObject request=new JSONObject().put("clientContent",new JSONObject().put("turns",new org.json.JSONArray().put(content)).put("turnComplete",true));
        sendFrame(request.toString());
      }catch(Exception e){ ModeError("IMAGE","SEND_FAILED",e.toString()); }
    }},"GeminiLive-image").start();
  }

  @SimpleProperty(category=PropertyCategory.BEHAVIOR, description="Play Gemini PCM audio responses through the device speaker.")
  public boolean AudioEnabled(){ return audioEnabled; }
  @DesignerProperty(editorType="boolean", defaultValue="True")
  @SimpleProperty(category=PropertyCategory.BEHAVIOR)
  public void AudioEnabled(boolean value){ audioEnabled=value; if(!value) StopAudio(); }

  @SimpleProperty(category=PropertyCategory.BEHAVIOR, description="Playback volume from 0 to 100.")
  public int Volume(){ return Math.round(volume*100f); }
  @DesignerProperty(editorType="integer", defaultValue="100")
  @SimpleProperty(category=PropertyCategory.BEHAVIOR)
  public void Volume(int value){
    if(value<0)value=0; if(value>100)value=100;
    volume=value/100f;
    synchronized(audioLock){ if(audioTrack!=null && audioTrack.getState()==AudioTrack.STATE_INITIALIZED) applyVolume(audioTrack); }
  }

  @SimpleFunction(description="Immediately stop and flush buffered Gemini audio.")
  public void StopAudio(){
    new Thread(new Runnable(){ public void run(){ flushAudio(); }},"GeminiLive-stopAudio").start();
  }

  @SimpleEvent public void OutputTranscription(String text){ EventDispatcher.dispatchEvent(this,"OutputTranscription",text); }
  @SimpleEvent public void TurnComplete(){ EventDispatcher.dispatchEvent(this,"TurnComplete"); }

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

  private void readLoop(final int mySession){
    ByteArrayOutputStream fragmented=new ByteArrayOutputStream();
    String closeReason="Socket closed";
    try {
      while(connected && mySession==session){
        int b1=in.read(); if(b1<0) break; int b2=in.read(); if(b2<0) break;
        boolean fin=(b1&0x80)!=0; int opcode=b1&0x0F; long len=b2&0x7F;
        if(len==126) len=((long)readByte()<<8)|readByte();
        else if(len==127){ len=0; for(int i=0;i<8;i++) len=(len<<8)|readByte(); }
        byte[] mask=null; if((b2&0x80)!=0){ mask=readN(4); }
        if(len>16*1024*1024) throw new IOException("Frame too large");
        byte[] data=readN((int)len); if(mask!=null) for(int i=0;i<data.length;i++) data[i]^=mask[i&3];
        if(opcode==8){ closeReason=data.length>=2?((((data[0]&255)<<8)|(data[1]&255))+": "+new String(data,2,data.length-2,StandardCharsets.UTF_8)):"Socket closed"; break; }
        if(opcode==9){ sendControl(10,data); continue; }
        // Gemini Live may return JSON in either TEXT (opcode 1) or BINARY
        // (opcode 2) WebSocket frames. The Python reference client receives
        // setupComplete/serverContent as bytes, so decode binary JSON as UTF-8.
        if(opcode==1 || opcode==2 || opcode==0){
          fragmented.write(data);
          if(fin){ handleMessage(new String(fragmented.toByteArray(),StandardCharsets.UTF_8)); fragmented.reset(); }
        }
      }
    } catch(Exception e){ if(connected && mySession==session) Error("Read: "+e.toString()); }
    finally { if(mySession==session){ final String reason=closeReason; boolean was=connected; closeQuietly(); if(was) ui.post(new Runnable() { public void run() { Disconnected(reason); }}); } }
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
    if(debugRawMessages) ui.post(new Runnable() { public void run() { RawMessage(msg); }});
    try {
      JSONObject j=new JSONObject(msg);
      if(j.has("setupComplete")){ ready=true; ui.post(new Runnable() { public void run() { SetupComplete(); }}); return; }
      if(j.has("toolCall")){
        JSONObject tc=j.getJSONObject("toolCall");
        org.json.JSONArray calls=tc.optJSONArray("functionCalls");
        if(calls!=null) for(int i=0;i<calls.length();i++){
          JSONObject fc=calls.getJSONObject(i);
          final String fn=fc.optString("name","");
          final String fid=fc.optString("id","");
          Object args=fc.opt("args");
          final String fa=args==null?"{}":args.toString();
          ui.post(new Runnable(){ public void run(){ FunctionCall(fn,fa,fid); }});
        }
      }
      if(j.has("serverContent")){
        JSONObject sc=j.getJSONObject("serverContent");
        if(sc.has("modelTurn")){
          org.json.JSONArray ps=sc.getJSONObject("modelTurn").optJSONArray("parts");
          if(ps!=null) {
            for(int i=0;i<ps.length();i++){
              JSONObject p=ps.getJSONObject(i);
              String t=p.optString("text","");
              if(!t.isEmpty()) {
                final String textPart=t;
                ui.post(new Runnable() { public void run() { TextReceived(textPart); }});
              }
              JSONObject inline=p.optJSONObject("inlineData");
              if(inline!=null && inline.optString("mimeType","").startsWith("audio/pcm")){
                String b64=inline.optString("data","");
                if(!b64.isEmpty() && audioEnabled){
                  try { playPcm(Base64.decode(b64,Base64.DEFAULT)); }
                  catch(Exception ae){ Error("Audio: "+ae.toString()); }
                }
              }
            }
          }
        }
        JSONObject itr=sc.optJSONObject("inputTranscription");
        if(itr!=null){
          final String itx=itr.optString("text","");
          if(!itx.isEmpty()) ui.post(new Runnable(){ public void run(){ InputTranscription(itx); }});
        }
        JSONObject tr=sc.optJSONObject("outputTranscription");
        if(tr==null) tr=sc.optJSONObject("outputAudioTranscription");
        if(tr!=null){
          final String tx=tr.optString("text","");
          if(!tx.isEmpty()) ui.post(new Runnable(){ public void run(){ OutputTranscription(tx); }});
        }
        if(sc.optBoolean("interrupted",false)){ assistantSpeaking=false; flushAudio(); }
        if(sc.optBoolean("turnComplete",false)){
          ui.post(new Runnable(){ public void run(){ TurnComplete(); }});
        }
      }
    } catch(JSONException e){
      if(debugRawMessages) Error("Invalid server JSON: "+e.toString());
    } catch(Exception e){ Error("Handle server message: "+e.toString()); }
  }

  private void startMicInternal(){
    if(!ready) return;
    final int generation;
    synchronized(micLock){ if(listening) return; listening=true; generation=++micGeneration; }
    try{
      int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
      if(min<0) throw new IOException("Unsupported microphone format: "+min);
      final int size=Math.max(min,3200);
      final AudioRecord rec=new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size*2);
      if(rec.getState()!=AudioRecord.STATE_INITIALIZED){ rec.release(); throw new IOException("Microphone initialization failed"); }
      synchronized(micLock){ if(generation!=micGeneration || !listening){ rec.release(); return; } audioRecord=rec; }
      final AcousticEchoCanceler echo=echoCancellation && AcousticEchoCanceler.isAvailable()?AcousticEchoCanceler.create(rec.getAudioSessionId()):null;
      final NoiseSuppressor noise=noiseSuppression && NoiseSuppressor.isAvailable()?NoiseSuppressor.create(rec.getAudioSessionId()):null;
      if(echo!=null) try{ echo.setEnabled(true); }catch(Exception ignored){}
      if(noise!=null) try{ noise.setEnabled(true); }catch(Exception ignored){}
      rec.startRecording();
      ui.post(new Runnable(){ public void run(){ if(listening && generation==micGeneration) ListeningStarted(); }});
      micThread=new Thread(new Runnable(){ public void run(){
        byte[] buf=new byte[size];
        try{
          while(listening && ready && generation==micGeneration){
            int n=rec.read(buf,0,buf.length);
            if(n<0){ if(listening) ModeError("VOICE","AUDIO_READ_FAILED","AudioRecord returned "+n); break; }
            if(n==0 || (muteMicWhileSpeaking && assistantSpeaking)) continue;
            byte[] chunk=new byte[n]; System.arraycopy(buf,0,chunk,0,n);
            JSONObject blob=new JSONObject().put("data",Base64.encodeToString(chunk,Base64.NO_WRAP)).put("mimeType","audio/pcm;rate=16000");
            if(listening && generation==micGeneration) sendFrame(new JSONObject().put("realtimeInput",new JSONObject().put("audio",blob)).toString());
          }
        }catch(Exception e){ if(listening && generation==micGeneration) ModeError("VOICE","MIC_FAILED",e.toString()); }
        finally{
          try{ rec.stop(); }catch(Exception ignored){}
          try{ rec.release(); }catch(Exception ignored){}
          if(echo!=null) try{ echo.release(); }catch(Exception ignored){}
          if(noise!=null) try{ noise.release(); }catch(Exception ignored){}
          synchronized(micLock){
            if(audioRecord==rec) audioRecord=null;
            if(generation==micGeneration) listening=false;
          }
          ui.post(new Runnable(){ public void run(){ if(!listening && generation==micGeneration) ListeningStopped(); }});
        }
      }},"GeminiLive-microphone");
      micThread.start();
    }catch(Exception e){ synchronized(micLock){ if(generation==micGeneration)listening=false; } ModeError("VOICE","START_FAILED",e.toString()); }
  }

  private void fetchCatalog(final String key,final boolean models){
    if(key==null || key.trim().isEmpty()){ Error("API key is empty"); return; }
    new Thread(new Runnable(){ public void run(){
      HttpURLConnection conn=null;
      try{
        String endpoint=models?"https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000&key=":
          "https://generativelanguage.googleapis.com/v1beta/voices?page_size=1000&key=";
        conn=(HttpURLConnection)new URL(endpoint+java.net.URLEncoder.encode(key.trim(),"UTF-8")).openConnection();
        conn.setConnectTimeout(10000); conn.setReadTimeout(15000); conn.setRequestMethod("GET");
        InputStream s=conn.getResponseCode()>=200&&conn.getResponseCode()<300?conn.getInputStream():conn.getErrorStream();
        BufferedReader r=new BufferedReader(new InputStreamReader(s,StandardCharsets.UTF_8));
        StringBuilder b=new StringBuilder(); String line; while((line=r.readLine())!=null)b.append(line);
        if(conn.getResponseCode()<200||conn.getResponseCode()>=300) throw new IOException("HTTP "+conn.getResponseCode()+": "+b.toString());
        JSONObject root=new JSONObject(b.toString()); final ArrayList<String> vals=new ArrayList<String>();
        org.json.JSONArray a=root.optJSONArray(models?"models":"voices");
        if(a!=null) for(int i=0;i<a.length();i++){
          JSONObject o=a.getJSONObject(i);
          if(models){
            String n=o.optString("name",""); if(n.startsWith("models/"))n=n.substring(7);
            String low=n.toLowerCase();
            if(low.contains("live")) vals.add(n);
          }else{
            String n=o.optString("display_name",o.optString("displayName",""));
            if(n.isEmpty()){ n=o.optString("name",""); if(n.startsWith("voices/"))n=n.substring(7); }
            if(!n.isEmpty()) vals.add(n);
          }
        }
        final YailList yl=YailList.makeList(vals);
        ui.post(new Runnable(){ public void run(){ if(models)ModelsLoaded(yl); else VoicesLoaded(yl); }});
      }catch(Exception e){ Error((models?"RefreshModels: ":"RefreshVoices: ")+e.toString()); }
      finally{ if(conn!=null)conn.disconnect(); }
    }},"GeminiLive-catalog").start();
  }

  private void applyVolume(AudioTrack track){
    if(Build.VERSION.SDK_INT>=21) track.setVolume(volume);
    else track.setStereoVolume(volume,volume);
  }

  private void ensureAudioTrack(){
    synchronized(audioLock){
      if(audioTrack==null){
        int min=AudioTrack.getMinBufferSize(24000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
        int buffer=Math.max(min,9600);
        audioTrack=new AudioTrack(AudioManager.STREAM_MUSIC,24000,AudioFormat.CHANNEL_OUT_MONO,
          AudioFormat.ENCODING_PCM_16BIT,buffer,AudioTrack.MODE_STREAM);
        if(audioTrack.getState()!=AudioTrack.STATE_INITIALIZED){
          audioTrack.release(); audioTrack=null;
          throw new IllegalStateException("AudioTrack failed to initialize");
        }
        applyVolume(audioTrack);
      }
      if(audioTrack.getState()!=AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("AudioTrack not initialized");
      if(audioTrack.getPlayState()!=AudioTrack.PLAYSTATE_PLAYING) audioTrack.play();
    }
  }

  private void playPcm(byte[] pcm){
    if(!audioEnabled || pcm==null || pcm.length==0) return;
    assistantSpeaking=true;
    synchronized(audioLock){
      if(playThread==null || !playThread.isAlive()){
        final int generation=++playbackGeneration;
        playThread=new Thread(new Runnable(){ public void run(){
          while(generation==playbackGeneration && !Thread.currentThread().isInterrupted()){
            try{
              byte[] chunk=playQueue.poll(250,TimeUnit.MILLISECONDS);
              if(chunk==null){ if(playQueue.isEmpty()) assistantSpeaking=false; continue; }
              if(!audioEnabled) continue;
              ensureAudioTrack();
              AudioTrack track;
              synchronized(audioLock){ track=audioTrack; }
              if(track!=null) track.write(chunk,0,chunk.length);
              if(playQueue.isEmpty()) assistantSpeaking=false;
            }catch(InterruptedException e){ break; }
            catch(Exception e){ Error("Playback: "+e.toString()); }
          }
        }},"GeminiLive-playback");
        playThread.start();
      }
    }
    if(!playQueue.offer(pcm)){
      playQueue.clear();
      Error("Audio playback queue overflow; dropped buffered audio");
      playQueue.offer(pcm);
    }
  }

  private void flushAudio(){
    playQueue.clear();
    assistantSpeaking=false;
    synchronized(audioLock){
      if(audioTrack!=null){
        try{ audioTrack.pause(); audioTrack.flush(); if(audioEnabled) audioTrack.play(); }catch(Exception ignored){}
      }
    }
  }

  private void releaseAudio(){
    playQueue.clear();
    assistantSpeaking=false;
    playbackGeneration++;
    Thread old=playThread; playThread=null;
    if(old!=null) old.interrupt();
    synchronized(audioLock){
      if(audioTrack!=null){
        try{ audioTrack.pause(); audioTrack.flush(); audioTrack.release(); }catch(Exception ignored){}
        audioTrack=null;
      }
    }
  }

  private void closeQuietly(){
    final SSLSocket oldSocket;
    synchronized(connectionLock){
      session++;
      ready=false; connected=false;
      oldSocket=socket; socket=null; in=null; out=null;
    }
    StopListening();
    releaseAudio();
    if(oldSocket!=null) new Thread(new Runnable(){ public void run(){
      try{ oldSocket.close(); }catch(Exception ignored){}
    }},"GeminiLive-close").start();
  }
}
