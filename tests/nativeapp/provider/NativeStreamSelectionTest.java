package dev.ytmusictv.nativeapp.provider;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.services.youtube.ItagItem;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.VideoStream;

/** Offline representation choices using the pinned extractor's real stream objects. */
public final class NativeStreamSelectionTest {
    private static int checks;
    public static void main(String[] args) {
        VideoStream mux360 = stream(360,30,"avc1.42001E",MediaFormat.MPEG_4,false);
        VideoStream only480 = stream(480,30,"avc1.4d401f",MediaFormat.MPEG_4,true);
        VideoStream only720 = stream(720,30,"avc1.64001F",MediaFormat.MPEG_4,true);
        VideoStream only1080 = stream(1080,30,"avc1.640028",MediaFormat.MPEG_4,true);
        List<VideoStream> muxed = List.of(mux360);
        List<VideoStream> separate = List.of(only1080,only480,only720);
        equal(mux360,choose(muxed,separate,360,true));
        equal(only480,choose(muxed,separate,480,true));
        equal(only720,choose(muxed,separate,720,true));
        equal(only1080,choose(muxed,separate,1080,true));
        equal(only720,choose(muxed,separate,0,true));
        equal(only720,choose(muxed,separate,-1,true));
        equal(only1080,choose(muxed,separate,2160,true));
        equal(mux360,choose(muxed,separate,1080,false));
        equal(null,choose(List.of(),separate,1080,false));
        equal(null,choose(List.of(),List.of(only720),480,true));

        VideoStream mux720=stream(720,25,"avc1.64001F, mp4a.40.2",MediaFormat.MPEG_4,false);
        equal(mux720,choose(List.of(mux360,mux720),List.of(only720),720,true));
        equal(only1080,choose(List.of(mux720),List.of(only1080),1080,true));
        VideoStream only540=stream(540,30,"AVC1.4D401F",MediaFormat.MPEG_4,true);
        equal(only540,choose(muxed,List.of(only540),720,true));
        // A nominally higher representation must not bypass codec/container/fps safety.
        for (VideoStream unsafe : List.of(
                stream(1080,60,"avc1.64002A",MediaFormat.MPEG_4,true),
                stream(1080,0,"avc1.640028",MediaFormat.MPEG_4,true),
                stream(1080,30,"vp09.00.40.08",MediaFormat.MPEG_4,true),
                stream(1080,30,"av01.0.08M.08",MediaFormat.MPEG_4,true),
                stream(1080,30,"hev1.1.6.L120.B0",MediaFormat.MPEG_4,true),
                stream(1080,30,"",MediaFormat.MPEG_4,true),
                stream(1080,30,"avc1.640028",MediaFormat.WEBM,true))) {
            equal(mux360,choose(muxed,List.of(unsafe),1080,true));
        }
        equal(null,choose(null,null,720,true));
        equal(null,choose(Collections.emptyList(),Arrays.asList((VideoStream)null),720,true));
        equal(null,choose(List.of(),List.of(custom(720,30,"720p","http://example.com/video",true,DeliveryMethod.PROGRESSIVE_HTTP)),720,true));
        equal(null,choose(List.of(),List.of(custom(720,30,"720p","https://example.com/video",false,DeliveryMethod.PROGRESSIVE_HTTP)),720,true));
        equal(null,choose(List.of(),List.of(custom(720,30,"720p","https://example.com/video",true,DeliveryMethod.DASH)),720,true));
        equal(null,choose(List.of(),List.of(custom(720,30,"720p","https://example.com/video",true,DeliveryMethod.HLS)),720,true));
        VideoStream fallbackHeight=custom(0,30,"720p","https://example.com/video",true,DeliveryMethod.PROGRESSIVE_HTTP);
        equal(fallbackHeight,choose(List.of(),List.of(fallbackHeight),720,true));
        equal(null,choose(List.of(),List.of(custom(0,30,"unknown","https://example.com/video",true,DeliveryMethod.PROGRESSIVE_HTTP)),1080,true));
        equal(null,choose(List.of(),List.of(custom(0,30,"2160p","https://example.com/video",true,DeliveryMethod.PROGRESSIVE_HTTP)),1080,true));
        VideoStream vp9FourK=stream(2160,30,"vp09.00.51.08",MediaFormat.WEBM,true);
        VideoStream vp9Fourteen=stream(1440,30,"vp09.00.50.08",MediaFormat.WEBM,true);
        YouTubeStreamResolver.DecoderSupport supported=(w,h,f,l)->true;
        YouTubeStreamResolver.DecoderSupport unsupported=(w,h,f,l)->false;
        List<VideoStream> fourKChoices=List.of(only1080,vp9Fourteen,vp9FourK);
        equal(vp9FourK,YouTubeStreamResolver.chooseVideo(muxed,fourKChoices,2160,true,supported));
        equal(only1080,YouTubeStreamResolver.chooseVideo(muxed,fourKChoices,2160,true,unsupported));
        equal(only1080,YouTubeStreamResolver.chooseVideo(muxed,fourKChoices,2160,true,null));
        equal(vp9Fourteen,YouTubeStreamResolver.chooseVideo(muxed,fourKChoices,2160,true,(w,h,f,l)->h<=1440));
        equal(only1080,YouTubeStreamResolver.chooseVideo(muxed,fourKChoices,1080,true,(w,h,f,l)->{throw new AssertionError("1080 must not query optional VP9");}));
        equal(mux360,YouTubeStreamResolver.chooseVideo(muxed,fourKChoices,2160,false,supported));
        int[] dimensions=new int[4];
        equal(vp9FourK,YouTubeStreamResolver.chooseVideo(muxed,fourKChoices,2160,true,(w,h,f,l)->{
            dimensions[0]=w;dimensions[1]=h;dimensions[2]=f;dimensions[3]=l;return true;
        }));
        equal(3840,dimensions[0]);equal(2160,dimensions[1]);equal(30,dimensions[2]);equal(51,dimensions[3]);
        for(VideoStream unsafe:List.of(
                stream(2160,30,"vp9",MediaFormat.WEBM,true),
                stream(2160,30,"vp09.02.51.10",MediaFormat.WEBM,true),
                stream(2160,30,"vp09.00.51.10",MediaFormat.WEBM,true),
                stream(2160,30,"vp09.00.51.08.01.09.16.09.01",MediaFormat.WEBM,true),
                stream(2160,30,"vp09.00.51.08.01.09.18.09.01",MediaFormat.WEBM,true),
                stream(2160,30,"av01.0.12M.08",MediaFormat.MPEG_4,true),
                stream(2160,30,"avc1.640034",MediaFormat.MPEG_4,true),
                stream(2160,60,"vp09.00.52.08",MediaFormat.WEBM,true),
                stream(4320,30,"vp09.00.60.08",MediaFormat.WEBM,true))) {
            equal(only1080,YouTubeStreamResolver.chooseVideo(muxed,List.of(only1080,unsafe),2160,true,supported));
        }
        equal(51,VideoCodecPolicy.verifiedVp9SdrLevel("vp09.00.51.08"));
        equal(51,VideoCodecPolicy.verifiedVp9SdrLevel("vp09.00.51.08.01.01.01.01.00"));
        equal(51,VideoCodecPolicy.verifiedVp9SdrLevel("vp09.00.51.08.00.01.01.01.01"));
        for(String codec:Arrays.asList(null,"vp9","vp09.00.51","vp09.01.51.08","vp09.00.99.08",
                "vp09.00.51.08.01","vp09.00.51.08.03.01.01.01.00","vp09.00.51.08.01.01.01.01.02"))
            equal(0,VideoCodecPolicy.verifiedVp9SdrLevel(codec));
        equal(1648,HardwareVideoSupport.alignDimension(1634,16));
        equal(3840,HardwareVideoSupport.alignDimension(3840,64));
        equal(0,HardwareVideoSupport.alignDimension(1634,0));
        equal(0,HardwareVideoSupport.alignDimension(Integer.MAX_VALUE,16));
        equal(true,VideoCodecPolicy.legacyHardwareDecoderName("OMX.realtek.video.decoder.vp9"));
        equal(true,VideoCodecPolicy.legacyHardwareDecoderName("OMX.rtk.video.decoder.vp9"));
        equal(true,VideoCodecPolicy.legacyHardwareDecoderName("OMX.qcom.video.decoder.vp9"));
        for(String name:Arrays.asList(null,"OMX.google.vp9.decoder","c2.android.vp9.decoder","unknown.decoder",
                "OMX.qcom.video.decoder.vp9.sw","OMX.qcom.video.decoder.sw.vp9","OMX.rtk.software.vp9.decoder"))
            equal(false,VideoCodecPolicy.legacyHardwareDecoderName(name));
        VideoStream legacy2160=stream(2160,24,"vp9",MediaFormat.WEBM,true);
        VideoStream legacy1440=stream(1440,24,"vp9",MediaFormat.WEBM,true);
        VideoStream legacy1200=stream(1200,24,"vp9",MediaFormat.WEBM,true);
        List<VideoStream> legacy=List.of(legacy1200,legacy1440,legacy2160,only1080);
        int[] probes={0};
        equal(legacy2160,YouTubeStreamResolver.chooseVideo(muxed,legacy,2160,true,supported,candidate->{probes[0]++;return true;}));
        equal(1,probes[0]);
        probes[0]=0;
        equal(legacy1440,YouTubeStreamResolver.chooseVideo(muxed,legacy,2160,true,supported,candidate->{probes[0]++;return candidate==legacy1440;}));
        equal(2,probes[0]);
        probes[0]=0;
        equal(only1080,YouTubeStreamResolver.chooseVideo(muxed,legacy,2160,true,supported,candidate->{probes[0]++;return false;}));
        equal(2,probes[0]); // The third legacy representation cannot trigger another range request.
        equal(only1080,YouTubeStreamResolver.chooseVideo(muxed,legacy,2160,true,unsupported,candidate->{throw new AssertionError("unsupported hardware must not probe");}));
        equal(only1080,YouTubeStreamResolver.chooseVideo(muxed,legacy,1080,true,supported,candidate->{throw new AssertionError("1080 must not probe");}));
        equal(mux360,YouTubeStreamResolver.chooseVideo(muxed,legacy,0,true,supported,candidate->{throw new AssertionError("Auto must not probe");}));
        equal(mux360,YouTubeStreamResolver.chooseVideo(muxed,legacy,2160,false,supported,candidate->{throw new AssertionError("missing audio must not probe");}));
        System.out.println("Native stream selection checks passed: "+checks);
    }
    private static VideoStream choose(List<VideoStream> muxed,List<VideoStream> only,int height,boolean audio) {
        return YouTubeStreamResolver.chooseVideo(muxed,only,height,audio);
    }
    private static VideoStream stream(int height,int fps,String codec,MediaFormat format,boolean only) {
        return build(height,fps,height+"p",codec,format,only,"https://example.com/video",true,DeliveryMethod.PROGRESSIVE_HTTP);
    }
    private static VideoStream custom(int height,int fps,String resolution,String url,boolean isUrl,DeliveryMethod delivery) {
        return build(height,fps,resolution,"avc1.64001F",MediaFormat.MPEG_4,true,url,isUrl,delivery);
    }
    private static VideoStream build(int height,int fps,String resolution,String codec,MediaFormat format,boolean only,
            String url,boolean isUrl,DeliveryMethod delivery) {
        ItagItem item=new ItagItem(only?137:18,only?ItagItem.ItagType.VIDEO_ONLY:ItagItem.ItagType.VIDEO,format,resolution,fps);
        item.setHeight(height);item.setWidth(height*16/9);item.setFps(fps);item.setCodec(codec);
        return new VideoStream.Builder().setId("test-"+height).setContent(url,isUrl).setMediaFormat(format)
                .setDeliveryMethod(delivery).setResolution(resolution).setIsVideoOnly(only).setItagItem(item).build();
    }
    private static void equal(Object expected,Object actual) {
        checks++;if(!Objects.equals(expected,actual))throw new AssertionError("Stream selection check "+checks+" failed");
    }
}
