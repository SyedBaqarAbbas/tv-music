package dev.ytmusictv.nativeapp.model;

import java.util.List;

public final class NativeArtworkUrlTest {
    private static int checks;
    public static void main(String[] args) {
        String original="https://yt3.googleusercontent.com/Identity_ABC123=w120-h120-l90-rj";
        List<String> urls=NativeArtworkUrl.candidates(original,640);
        equal(2,urls.size());equal("https://yt3.googleusercontent.com/Identity_ABC123=w640-h640-l90-rj",urls.get(0));
        equal(original,urls.get(1));
        equal("https://lh3.googleusercontent.com/id=w640-h480-p-k-no-nu",NativeArtworkUrl.candidates("https://lh3.googleusercontent.com/id=w120-h90-p-k-no-nu",640).get(0));
        equal("https://lh3.googleusercontent.com/id=s640-c-rj",NativeArtworkUrl.candidates("https://lh3.googleusercontent.com/id=s120-c-rj",640).get(0));
        equal("https://yt3.ggpht.com/id=w640-c",NativeArtworkUrl.candidates("https://yt3.ggpht.com/id=w120-c",640).get(0));
        equal("https://lh3.ggpht.com/id=h640-c",NativeArtworkUrl.candidates("https://lh3.ggpht.com/id=h120-c",640).get(0));
        equal(1,NativeArtworkUrl.candidates(original,120).size());
        equal(1,NativeArtworkUrl.candidates(original,0).size());
        equal(1,NativeArtworkUrl.candidates(original,-1).size());
        equal(0,NativeArtworkUrl.candidates(null,640).size());
        equal(0,NativeArtworkUrl.candidates("",640).size());
        equal("https://yt3.googleusercontent.com/Identity_ABC123=w1024-h1024-l90-rj",NativeArtworkUrl.candidates(original,8192).get(0));
        unchanged("https://example.com/id=w120-h120-c");
        unchanged("https://lh3.googleusercontent.com.attacker.example/id=w120-h120-c");
        unchanged("https://attacker@lh3.googleusercontent.com/id=w120-h120-c");
        unchanged("http://lh3.googleusercontent.com/id=w120-h120-c");
        unchanged("https://lh3.googleusercontent.com:444/id=w120-h120-c");
        unchanged(original+"?signature=unchanged");
        unchanged(original+"#unchanged");
        unchanged("https://lh3.googleusercontent.com/id=w0-h120-c");
        unchanged("https://lh3.googleusercontent.com/id=w120-w240-h120-c");
        unchanged("https://lh3.googleusercontent.com/id=w120-h120-s120-c");
        unchanged("https://lh3.googleusercontent.com/id=wBAD-h120-c");
        unchanged("https://lh3.googleusercontent.com/id=w120-h120--c");
        unchanged("https://lh3.googleusercontent.com/id-without-size");
        unchanged("https://i.ytimg.com/vi/Video_Id/hqdefault.jpg"); // No unverified maxres guess.
        unchanged("not a valid URL");
        try { urls.add("bad");throw new AssertionError("Candidates must be immutable"); }
        catch(UnsupportedOperationException expected){checks++;}
        System.out.println("Native artwork URL checks passed: "+checks);
    }
    private static void unchanged(String original){List<String> urls=NativeArtworkUrl.candidates(original,640);equal(1,urls.size());equal(original,urls.get(0));}
    private static void equal(Object expected,Object actual){checks++;if(!expected.equals(actual))throw new AssertionError("Expected "+expected+"; got "+actual);}
}
