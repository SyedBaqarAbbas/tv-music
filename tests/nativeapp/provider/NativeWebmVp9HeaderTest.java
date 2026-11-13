package dev.ytmusictv.nativeapp.provider;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Synthetic EBML and metadata headers only: no encoded music-video/frame payload. */
public final class NativeWebmVp9HeaderTest {
    private static int checks;
    private static final int WIDTH=3840,HEIGHT=2160;
    public static void main(String[]args) {
        byte[] valid=doc(track(WIDTH,HEIGHT,colour(1),new byte[0]),block(frame(WIDTH,HEIGHT,0,2),1,128),false,false);
        check(verify(valid),"SDR profile0 header");
        for(int length=0;length<valid.length;length++) check(!WebmVp9Header.verify(valid,length,WIDTH,HEIGHT),"truncated required header");
        check(!WebmVp9Header.verify(valid,valid.length,WIDTH-1,HEIGHT),"wrong width");
        check(!WebmVp9Header.verify(valid,valid.length,WIDTH,HEIGHT-1),"wrong height");
        check(!WebmVp9Header.verify(new byte[262145],262145,WIDTH,HEIGHT),"prefix cap");
        check(!WebmVp9Header.verify(null,0,WIDTH,HEIGHT),"null prefix");
        for(int profile:new int[]{1,2,3}) reject(frame(WIDTH,HEIGHT,profile,2),128,"high profile");
        for(int space:new int[]{0,5,6,7}) reject(frame(WIDTH,HEIGHT,0,space),128,"unknown or unsupported colour");
        reject(frame(WIDTH,HEIGHT,0,2),0,"container non-keyframe");
        reject(frame(WIDTH,HEIGHT,0,2),130,"laced block");
        byte[] wrongSync=frame(WIDTH,HEIGHT,0,2);wrongSync[1]^=1;reject(wrongSync,128,"bad sync");
        for(int transfer:new int[]{16,18,99}) check(!verify(doc(track(WIDTH,HEIGHT,colour(transfer),new byte[0]),block(frame(WIDTH,HEIGHT,0,2),1,128),false,false)),"HDR/unknown transfer");
        byte[] duplicate=element(0x55B0,join(uint(0x55BA,16),uint(0x55BA,1)));
        check(!verify(doc(track(WIDTH,HEIGHT,duplicate,new byte[0]),block(frame(WIDTH,HEIGHT,0,2),1,128),false,false)),"duplicate PQ then SDR cannot erase HDR");
        byte[] deep=element(0x55B0,join(uint(0x55B2,10),uint(0x55BA,1)));
        check(!verify(doc(track(WIDTH,HEIGHT,deep,new byte[0]),block(frame(WIDTH,HEIGHT,0,2),1,128),false,false)),"container bitdepth conflict");
        byte[] hdr=element(0x55B0,join(uint(0x55BA,1),element(0x55D0,new byte[0])));
        check(!verify(doc(track(WIDTH,HEIGHT,hdr,new byte[0]),block(frame(WIDTH,HEIGHT,0,2),1,128),false,false)),"HDR mastering metadata");
        check(verify(doc(track(WIDTH,HEIGHT,new byte[0],new byte[0]),block(frame(WIDTH,HEIGHT,0,2),1,128),false,false)),"frame-proven SDR with absent container colour");
        check(verify(doc(track(WIDTH,HEIGHT,colour(2),new byte[0]),block(frame(WIDTH,HEIGHT,0,2),1,128),false,false)),"unspecified transfer with explicit SDR frame");
        for(long unsupported:new long[]{0x6D80,0x63A2}) check(!verify(doc(track(WIDTH,HEIGHT,colour(1),element(unsupported,new byte[]{0})),block(frame(WIDTH,HEIGHT,0,2),1,128),false,false)),"unverified encoding/private override");
        check(!verify(doc(track(WIDTH,HEIGHT,colour(1),new byte[0]),block(frame(WIDTH,HEIGHT,0,2),2,128),false,false)),"wrong track number");
        byte[] payload=join(new byte[]{(byte)0x81,0,(byte)0, (byte)128},frame(WIDTH,HEIGHT,0,2));
        byte[] partialBlock=declared(0xA3,payload,payload.length+1000);
        check(verify(doc(track(WIDTH,HEIGHT,colour(1),new byte[0]),partialBlock,true,true)),"range may end inside compressed payload after required header");
        check(!verify(doc(track(WIDTH,HEIGHT,colour(1),new byte[0]),partialBlock,false,false)),"complete cluster at prefix end cannot hide child overflow");
        byte[] oversizedEntry=declared(0xAE,new byte[]{(byte)0xD7,(byte)0x81,1},1000);
        check(!verify(doc(oversizedEntry,block(frame(WIDTH,HEIGHT,0,2),1,128),false,false)),"track child cannot escape parent");
        // Only these nine inspected uncompressed-header bytes are copied from public LEMONADE
        // 83C3TZ4Zm_o (3350x2160), 2026-11-14. No compressed frame payload is retained.
        byte[] publicHeader=hex("8249834240d15086f6");
        byte[] publicEnvelope=doc(track(3350,2160,colour(1),new byte[0]),block(publicHeader,1,128),false,false);
        check(WebmVp9Header.verify(publicEnvelope,publicEnvelope.length,3350,2160),"actual public profile/color/dimension bytes");
        System.out.println("Native WebM/VP9 header checks passed: "+checks);
    }
    private static void reject(byte[] frame,int flags,String label) {
        check(!verify(doc(track(WIDTH,HEIGHT,colour(1),new byte[0]),block(frame,1,flags),false,false)),label);
    }
    private static boolean verify(byte[] value) {return WebmVp9Header.verify(value,value.length,WIDTH,HEIGHT);}
    private static byte[] doc(byte[] entry,byte[] block,boolean unknownSegment,boolean unknownCluster) {
        byte[] header=element(0x1A45DFA3,element(0x4282,"webm".getBytes(StandardCharsets.US_ASCII)));
        byte[] cluster=unknownCluster?join(id(0x1F43B675),new byte[]{(byte)255},block):element(0x1F43B675,block);
        byte[] segment=join(element(0x1654AE6B,entry),cluster);
        return join(header,unknownSegment?join(id(0x18538067),new byte[]{(byte)255},segment):element(0x18538067,segment));
    }
    private static byte[] track(int width,int height,byte[] colour,byte[] extra) {
        return element(0xAE,join(uint(0xD7,1),uint(0x83,1),element(0x86,"V_VP9".getBytes(StandardCharsets.US_ASCII)),
                element(0xE0,join(uint(0xB0,width),uint(0xBA,height),colour)),extra));
    }
    private static byte[] colour(int transfer) {return element(0x55B0,join(uint(0x55B1,1),uint(0x55BA,transfer),uint(0x55BB,1)));}
    private static byte[] block(byte[] frame,int track,int flags) {return element(0xA3,join(new byte[]{(byte)(128|track),0,0,(byte)flags},frame));}
    private static byte[] frame(int width,int height,int profile,int colour) {
        BitWriter bits=new BitWriter();bits.write(2,2);bits.write(profile&1,1);bits.write(profile>>1,1);
        if(profile==3)bits.write(0,1);bits.write(0,1);bits.write(0,1);bits.write(1,1);bits.write(0,1);
        bits.write(0x498342,24);if(profile>=2)bits.write(0,1);bits.write(colour,3);bits.write(0,1);
        bits.write(width-1,16);bits.write(height-1,16);bits.write(0,1);return bits.bytes();
    }
    private static byte[] uint(long id,int value) {
        int length=value<=255?1:value<=65535?2:4;byte[] data=new byte[length];
        for(int i=length-1;i>=0;i--){data[i]=(byte)value;value>>>=8;}return element(id,data);
    }
    private static byte[] element(long id,byte[] data) {return declared(id,data,data.length);}
    private static byte[] declared(long id,byte[] data,int size) {return join(id(id),size(size),data);}
    private static byte[] id(long id) {
        int length=id<=255?1:id<=65535?2:id<=16777215?3:4;byte[] out=new byte[length];
        for(int i=length-1;i>=0;i--){out[i]=(byte)id;id>>>=8;}return out;
    }
    private static byte[] size(int value) {
        int length=1;while(value>=(1L<<(7*length))-1)length++;
        byte[] out=new byte[length];long encoded=(1L<<(7*length))|value;
        for(int i=length-1;i>=0;i--){out[i]=(byte)encoded;encoded>>>=8;}return out;
    }
    private static byte[] join(byte[]...arrays) {ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] data:arrays)out.writeBytes(data);return out.toByteArray();}
    private static byte[] hex(String value) {byte[]out=new byte[value.length()/2];for(int i=0;i<out.length;i++)out[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return out;}
    private static void check(boolean condition,String label) {checks++;if(!condition)throw new AssertionError("WebM header check "+checks+": "+label);}
    private static final class BitWriter {
        private final byte[] data=new byte[32];private int bit;
        void write(int value,int count){for(int i=count-1;i>=0;i--,bit++)data[bit/8]|=((value>>i)&1)<<(7-bit%8);}
        byte[] bytes(){return Arrays.copyOf(data,(bit+7)/8);}
    }
}
