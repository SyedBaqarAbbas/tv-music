package dev.ytmusictv.nativeapp.ui;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;
import dev.ytmusictv.nativeapp.model.NativeArtworkUrl;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One large-cover loader: 640 physical pixels, a 4 MiB cache, no hidden polling. */
final class NativeArtworkView extends ImageView implements AutoCloseable {
    private static final int COVER_PIXELS=640, MAX_BYTES=2*1024*1024;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,15,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1),new ThreadPoolExecutor.DiscardOldestPolicy());
    private final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(4*1024*1024){
        @Override protected int sizeOf(String key,Bitmap value){return value.getAllocationByteCount();}
    };
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean debug;
    private volatile HttpURLConnection active;
    private volatile boolean closed;
    private volatile long generation;
    private Future<?> pending;
    private String loadedKey="",inFlightKey="",failedKey="";
    private long retryAfter;

    NativeArtworkView(Context context){
        super(context);setScaleType(ScaleType.CENTER_CROP);paint.setColor(NativeUi.GOLD);
        setBackground(NativeUi.fill(context,NativeUi.CARD,false));worker.allowCoreThreadTimeOut(true);
        debug=(context.getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0;
    }
    void load(String url){
        if(closed)return;
        List<String> candidates=NativeArtworkUrl.candidates(url,COVER_PIXELS);
        String key=candidates.isEmpty()?"":candidates.get(0)+"#"+COVER_PIXELS;
        // An absent cover is a state change too: do not match the empty in-flight sentinel.
        if(key.isEmpty()){
            if(!loadedKey.isEmpty()||!inFlightKey.isEmpty()||getDrawable()!=null){
                stopLoading();loadedKey="";setImageDrawable(null);
            }
            return;
        }
        if(key.equals(loadedKey)||key.equals(inFlightKey))return;
        stopLoading();
        Bitmap cached=cache.get(key);
        if(cached!=null){apply(cached,key);return;}
        loadedKey="";setImageDrawable(null);
        if(key.equals(failedKey)&&SystemClock.elapsedRealtime()<retryAfter)return;
        inFlightKey=key;
        long token=generation;
        pending=worker.submit(()->{
            Bitmap bitmap=null;
            for(int index=0;index<candidates.size()&&!stale(token);index++){
                try{bitmap=fetch(candidates.get(index),token,index<candidates.size()-1);}
                catch(Exception ignored){ }
                if(bitmap!=null)break;
            }
            if(stale(token)){if(bitmap!=null)bitmap.recycle();return;}
            final Bitmap result=bitmap;
            main.post(()->{
                if(stale(token)){if(result!=null)result.recycle();return;}
                pending=null;inFlightKey="";
                if(result==null){failedKey=key;retryAfter=SystemClock.elapsedRealtime()+30_000;return;}
                failedKey="";cache.put(key,result);apply(result,key);
            });
        });
    }
    private boolean stale(long token){return closed||token!=generation||Thread.currentThread().isInterrupted();}
    private Bitmap fetch(String url,long token,boolean enhanced) throws Exception {
        URI uri=URI.create(url);String host=uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT);
        if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getUserInfo()!=null
                ||!(host.endsWith(".ytimg.com")||host.equals("ytimg.com")
                ||host.endsWith(".googleusercontent.com")||host.endsWith(".ggpht.com")))return null;
        HttpURLConnection request=(HttpURLConnection)uri.toURL().openConnection();
        active=request;
        try{
            if(stale(token))return null;
            request.setConnectTimeout(6000);request.setReadTimeout(6000);request.setInstanceFollowRedirects(false);
            if(request.getResponseCode()!=200||request.getContentLengthLong()>MAX_BYTES)return null;
            byte[] encoded;
            try(InputStream input=request.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int count;
                while((count=input.read(buffer))!=-1){
                    if(stale(token)||output.size()+count>MAX_BYTES)return null;
                    output.write(buffer,0,count);
                }
                encoded=output.toByteArray();
            }
            if(stale(token))return null;
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(encoded,0,encoded.length,bounds);
            int edge=Math.max(bounds.outWidth,bounds.outHeight);
            if(bounds.outWidth<1||bounds.outHeight<1||edge>10000)return null;
            // Missing high-resolution renditions can return a tiny placeholder with HTTP 200.
            if(enhanced&&edge<COVER_PIXELS)return null;
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;options.inScaled=false;
            options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            // Match CENTER_CROP before sampling. A 1280x720 thumbnail must retain its 720px
            // center square, rather than becoming a 640x360 image that is enlarged again.
            int square=Math.min(bounds.outWidth,bounds.outHeight);
            while(square/(options.inSampleSize*2)>=COVER_PIXELS)options.inSampleSize*=2;
            Bitmap decoded=null;
            if(bounds.outWidth!=bounds.outHeight){
                BitmapRegionDecoder region=null;
                try{
                    region=BitmapRegionDecoder.newInstance(encoded,0,encoded.length,false);
                    int left=(bounds.outWidth-square)/2,top=(bounds.outHeight-square)/2;
                    decoded=region.decodeRegion(new Rect(left,top,left+square,top+square),options);
                }catch(Exception unsupported){ /* Fall back to a bounded ordinary decode. */ }
                finally{if(region!=null)region.recycle();}
            }
            if(decoded==null){
                while(edge/(options.inSampleSize*2)>=COVER_PIXELS)options.inSampleSize*=2;
                decoded=BitmapFactory.decodeByteArray(encoded,0,encoded.length,options);
                if(decoded!=null&&decoded.getWidth()!=decoded.getHeight()){
                    int size=Math.min(decoded.getWidth(),decoded.getHeight());
                    Bitmap centered=Bitmap.createBitmap(decoded,(decoded.getWidth()-size)/2,
                            (decoded.getHeight()-size)/2,size,size);
                    if(centered!=decoded)decoded.recycle();decoded=centered;
                }
            }
            if(decoded==null)return null;
            if(stale(token)){decoded.recycle();return null;}
            int decodedEdge=Math.max(decoded.getWidth(),decoded.getHeight());
            if(decodedEdge>COVER_PIXELS){
                float scale=COVER_PIXELS/(float)decodedEdge;
                Bitmap bounded=Bitmap.createScaledBitmap(decoded,Math.max(1,Math.round(decoded.getWidth()*scale)),
                        Math.max(1,Math.round(decoded.getHeight()*scale)),true);
                if(bounded!=decoded)decoded.recycle();decoded=bounded;
            }
            if(debug)Log.d("NativeArtwork","source="+bounds.outWidth+"x"+bounds.outHeight
                    +" decoded="+decoded.getWidth()+"x"+decoded.getHeight()+" bytes="+encoded.length);
            return decoded;
        }finally{request.disconnect();if(active==request)active=null;}
    }
    private void apply(Bitmap bitmap,String key){
        loadedKey=key;setImageBitmap(bitmap);
        if(getDrawable() instanceof BitmapDrawable)((BitmapDrawable)getDrawable()).setFilterBitmap(true);
    }
    void stopLoading(){
        generation++;inFlightKey="";
        if(pending!=null){pending.cancel(true);pending=null;}worker.getQueue().clear();
        HttpURLConnection request=active;if(request!=null)request.disconnect();
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(getDrawable()==null){paint.setTextSize(Math.min(getWidth(),getHeight())*.45f);paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("♪",getWidth()/2f,getHeight()/2f-(paint.ascent()+paint.descent())/2,paint);}
    }
    @Override public void close(){
        closed=true;stopLoading();worker.shutdownNow();main.removeCallbacksAndMessages(null);
        setImageDrawable(null);cache.evictAll();
    }
}
