package dev.ytmusictv.nativeapp.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** At most one 320px thumbnail decode; bounded memory, bytes and request queue. */
final class NativeArtworkView extends ImageView implements AutoCloseable {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),new ThreadPoolExecutor.DiscardOldestPolicy());
    private final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(2*1024*1024){
        @Override protected int sizeOf(String key,Bitmap value){return value.getAllocationByteCount();}
    };
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private volatile HttpURLConnection active;
    private volatile boolean closed;
    private long generation;
    private String current="";
    NativeArtworkView(Context context){super(context);setScaleType(ScaleType.CENTER_CROP);paint.setColor(NativeUi.GOLD);setBackground(NativeUi.fill(context,NativeUi.CARD,false));}
    void load(String url){
        if(closed||current.equals(url))return;current=url;long token=++generation;setImageDrawable(null);
        if(url==null||url.isEmpty())return;Bitmap cached=cache.get(url);if(cached!=null){setImageBitmap(cached);return;}
        worker.getQueue().clear();
        worker.execute(()->{
            HttpURLConnection request=null;
            try{
                URI uri=URI.create(url);String host=uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT);
                if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null||!(host.endsWith(".ytimg.com")||host.equals("ytimg.com")||host.endsWith(".googleusercontent.com")||host.endsWith(".ggpht.com")))return;
                request=(HttpURLConnection)uri.toURL().openConnection();active=request;
                request.setConnectTimeout(6000);request.setReadTimeout(6000);request.setInstanceFollowRedirects(false);
                if(request.getResponseCode()!=200)return;
                byte[] encoded;
                try(InputStream input=request.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[4096];int count;
                    while((count=input.read(buffer))!=-1){if(closed||output.size()+count>1024*1024)return;output.write(buffer,0,count);}
                    encoded=output.toByteArray();
                }
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                BitmapFactory.decodeByteArray(encoded,0,encoded.length,bounds);
                if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>10000||bounds.outHeight>10000)return;
                BitmapFactory.Options options=new BitmapFactory.Options();
                while(bounds.outWidth/(options.inSampleSize==0?1:options.inSampleSize)>320||bounds.outHeight/(options.inSampleSize==0?1:options.inSampleSize)>320)
                    options.inSampleSize=options.inSampleSize==0?2:options.inSampleSize*2;
                Bitmap bitmap=BitmapFactory.decodeByteArray(encoded,0,encoded.length,options);
                if(bitmap!=null){cache.put(url,bitmap);main.post(()->{if(!closed&&token==generation)setImageBitmap(bitmap);});}
            }catch(Exception ignored){}finally{if(request!=null)request.disconnect();active=null;}
        });
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);if(getDrawable()==null){paint.setTextSize(Math.min(getWidth(),getHeight())*.45f);paint.setTextAlign(Paint.Align.CENTER);canvas.drawText("♪",getWidth()/2f,getHeight()/2f-(paint.ascent()+paint.descent())/2,paint);}
    }
    @Override public void close(){closed=true;generation++;worker.shutdownNow();HttpURLConnection connection=active;if(connection!=null)connection.disconnect();main.removeCallbacksAndMessages(null);cache.evictAll();}
}
