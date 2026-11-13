package dev.ytmusictv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** A single platform WebView and a small, remote-accessible native toolbar. */
public final class MainActivity extends Activity {
    private static final String HOME = "https://music.youtube.com/";
    private static final int BACKGROUND = Color.rgb(16, 17, 22);
    private static final int SURFACE = Color.rgb(25, 26, 32);
    private static final int TEXT = Color.rgb(242, 238, 230);
    private static final int MUTED = Color.rgb(169, 166, 160);
    private static final int ACCENT = Color.rgb(203, 183, 146);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private FrameLayout webContainer;
    private CursorOverlayView cursorOverlay;
    private LinearLayout toolbar;
    private LinearLayout errorPanel;
    private TextView errorText;
    private ProgressBar progress;
    private Button homeButton;
    private Button controlsHandle;
    private SharedPreferences preferences;
    private AlertDialog activeDialog;
    private boolean chromeExpanded;
    private Button lyricsButton;
    private Button retryButton;
    private LyricsRepository lyricsRepository;
    private boolean foreground;
    private boolean destroyed;
    private boolean pageReady;
    private boolean mainFrameFailed;
    private boolean lyricsVisible;
    private boolean pollInFlight;
    private boolean cursorInitialized;
    private boolean cursorScrollPending;
    private boolean cursorHintShown;
    private boolean cursorTouchDown;
    private float cursorX;
    private float cursorY;
    private float pendingScrollX;
    private float pendingScrollY;
    private long cursorInteractionGeneration;
    private long cursorTouchDownTime;
    private long lastCursorScrollAt;
    private long pageGeneration;
    private long lyricsGeneration;
    private String currentLyricsKey = "";
    private String injectionScript;

    private final Runnable lyricsPoll = new Runnable() {
        @Override public void run() {
            if (!canPollLyrics()) return;
            if (!pollInFlight) pollLyrics();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        lyricsRepository = new LyricsRepository(handler,
                (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        preferences = getSharedPreferences("presentation", MODE_PRIVATE);
        chromeExpanded = preferences.getBoolean("chrome_expanded", false);
        buildLayout();
        try {
            injectionScript = buildInjectionScript();
            createWebView();
            webView.loadUrl(HOME);
        } catch (IOException | RuntimeException error) {
            showError("The music player could not start. Check that Android System WebView is installed and updated, then try again.");
        }
    }

    private void buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        setContentView(root);
        webContainer = new FrameLayout(this);
        root.addView(webContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cursorOverlay = new CursorOverlayView(this);
        webContainer.addView(cursorOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(2), dp(4), dp(2));
        toolbar.setBackground(surfaceBackground(SURFACE, dp(10)));
        TextView brand = new TextView(this);
        brand.setText("MUSIC / TV");
        brand.setTextColor(TEXT);
        brand.setTextSize(12);
        brand.setLetterSpacing(0.14f);
        brand.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        toolbar.addView(brand, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        homeButton = addToolbarButton("Home", v -> {
            setChromeExpanded(false, true);
            if (webView == null) { retry(); return; }
            if (pageReady) callScript("home()", null);
            else webView.loadUrl(HOME);
        });
        addToolbarButton("Search", v -> showSearch());
        lyricsButton = addToolbarButton("Lyrics", v -> {
            setChromeExpanded(false, true);
            toggleLyrics();
        });
        addToolbarButton("Account", v -> showAccount(null));
        addToolbarButton("More", v -> showMore());
        addToolbarButton("Hide ↑", v -> setChromeExpanded(false, true));
        FrameLayout.LayoutParams barLayout = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44), Gravity.TOP);
        webContainer.addView(toolbar, barLayout);

        controlsHandle = makeButton("Controls");
        controlsHandle.setTextSize(11);
        controlsHandle.setMinWidth(0);
        controlsHandle.setMinimumWidth(0);
        controlsHandle.setPadding(dp(10), 0, dp(10), 0);
        controlsHandle.setBackground(surfaceBackground(SURFACE, dp(14)));
        controlsHandle.setOnFocusChangeListener((view, focused) -> {
            controlsHandle.setBackground(surfaceBackground(focused ? ACCENT : SURFACE, dp(14)));
            controlsHandle.setTextColor(focused ? BACKGROUND : TEXT);
            if (focused) hideCursor();
        });
        controlsHandle.setContentDescription("Open controls. Press Menu on the remote.");
        controlsHandle.setOnClickListener(v -> setChromeExpanded(true, true));
        controlsHandle.setOnKeyListener((view, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) setChromeExpanded(false, true);
            return true;
        });
        webContainer.addView(controlsHandle, new FrameLayout.LayoutParams(dp(90), dp(28),
                Gravity.TOP | Gravity.END));
        toolbar.setVisibility(chromeExpanded ? View.VISIBLE : View.GONE);
        controlsHandle.setVisibility(chromeExpanded ? View.GONE : View.VISIBLE);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        webContainer.addView(progress, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2), Gravity.TOP));

        errorPanel = new LinearLayout(this);
        errorPanel.setOrientation(LinearLayout.VERTICAL);
        errorPanel.setGravity(Gravity.CENTER);
        errorPanel.setPadding(dp(32), dp(24), dp(32), dp(24));
        errorPanel.setBackgroundColor(BACKGROUND);
        TextView eyebrow = new TextView(this);
        eyebrow.setText("MUSIC / TV");
        eyebrow.setTextSize(12);
        eyebrow.setLetterSpacing(0.14f);
        eyebrow.setTextColor(ACCENT);
        eyebrow.setGravity(Gravity.CENTER);
        errorPanel.addView(eyebrow);
        errorText = new TextView(this);
        errorText.setTextColor(TEXT);
        errorText.setTextSize(19);
        errorText.setLineSpacing(dp(5), 1f);
        errorText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams messageLayout = new LinearLayout.LayoutParams(
                Math.min(dp(570), getResources().getDisplayMetrics().widthPixels - dp(120)),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        messageLayout.topMargin = dp(20);
        errorPanel.addView(errorText, messageLayout);
        retryButton = makeButton("Try again");
        retryButton.setOnClickListener(v -> retry());
        LinearLayout.LayoutParams retryLayout = new LinearLayout.LayoutParams(
                dp(128), dp(44));
        retryLayout.topMargin = dp(24);
        errorPanel.addView(retryButton, retryLayout);
        webContainer.addView(errorPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        errorPanel.setVisibility(View.GONE);
    }

    private Button addToolbarButton(String label, View.OnClickListener listener) {
        Button button = makeButton(label);
        button.setOnClickListener(listener);
        button.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) setChromeExpanded(false, true);
            return true;
        });
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        layout.setMarginStart(dp(3));
        toolbar.addView(button, layout);
        return button;
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setId(View.generateViewId());
        button.setText(label);
        button.setTextSize(12);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(dp(62));
        button.setMinimumWidth(dp(62));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setTextColor(TEXT);
        button.setBackground(buttonBackground(false));
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setStateListAnimator(null);
        button.setOnFocusChangeListener((view, hasFocus) -> {
            button.setBackground(buttonBackground(hasFocus));
            button.setTextColor(hasFocus ? BACKGROUND : TEXT);
            if (hasFocus) hideCursor();
        });
        return button;
    }

    private GradientDrawable surfaceBackground(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(radius);
        background.setStroke(dp(1), Color.argb(36, 242, 238, 230));
        return background;
    }

    private GradientDrawable buttonBackground(boolean focused) {
        return surfaceBackground(focused ? ACCENT : Color.TRANSPARENT, dp(7));
    }

    private void setChromeExpanded(boolean expanded, boolean transferFocus) {
        chromeExpanded = expanded;
        toolbar.setVisibility(expanded ? View.VISIBLE : View.GONE);
        controlsHandle.setVisibility(expanded ? View.GONE : View.VISIBLE);
        if (expanded) toolbar.bringToFront();
        else controlsHandle.bringToFront();
        if (preferences != null) preferences.edit().putBoolean("chrome_expanded", expanded).apply();
        callScript("setChromeExpanded(" + expanded + ")", null);
        if (!transferFocus) return;
        if (expanded) {
            hideCursor();
            homeButton.requestFocus();
        } else focusPlayer();
    }

    @SuppressWarnings("deprecation")
    private void createWebView() {
        WebView.setWebContentsDebuggingEnabled(
                (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        WebView player = new WebView(this);
        webView = player;
        player.setBackgroundColor(BACKGROUND);
        player.setFocusable(true);
        player.setFocusableInTouchMode(true);
        player.setDefaultFocusHighlightEnabled(false);
        player.setOnFocusChangeListener((view, hasFocus) -> updateCursorVisibility());
        player.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                cursorInitialized = false;
                centerCursor();
                updateCursorVisibility();
            }
        });
        WebSettings settings = player.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // Hardware media keys still call video.play() through JavaScript, while
        // pointer clicks are dispatched as real WebView touch events.
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(player, false);
        // Permit the OS to reclaim a background renderer instead of pinning TV memory.
        player.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true);
        player.setWebViewClient(new PlayerClient());
        player.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsBeforeUnload(WebView view, String url, String message, JsResult result) {
                if (view == webView && isMusicUrl(Uri.parse(url))) {
                    // Home, Search and Reload already express the user's navigation intent.
                    result.confirm();
                    return true;
                }
                return super.onJsBeforeUnload(view, url, message, result);
            }
            @Override public boolean onJsPrompt(WebView view, String url, String message,
                    String defaultValue, JsPromptResult result) {
                if (view == webView && pageReady && isMusicUrl(Uri.parse(url))
                        && "tv-music:lyrics-state".equals(message)
                        && ("on".equals(defaultValue) || "off".equals(defaultValue))) {
                    setLyricsVisible("on".equals(defaultValue));
                    result.confirm("");
                    return true;
                }
                return super.onJsPrompt(view, url, message, defaultValue, result);
            }
            @Override public void onProgressChanged(WebView view, int value) {
                if (view != webView) return;
                progress.setProgress(value);
                progress.setVisibility(value < 100 && !mainFrameFailed ? View.VISIBLE : View.INVISIBLE);
            }
        });
        webContainer.addView(player, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private final class PlayerClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false;
            return routeNavigation(request.getUrl());
        }

        @SuppressWarnings("deprecation")
        @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return routeNavigation(Uri.parse(url));
        }

        @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            if (view != webView) return;
            Uri uri = Uri.parse(url);
            if (!isMusicUrl(uri)) {
                view.stopLoading();
                routeNavigation(uri);
                view.loadUrl(HOME);
                return;
            }
            pageGeneration++;
            pageReady = false;
            mainFrameFailed = false;
            hideCursor();
            cursorInitialized = false;
            setLyricsVisible(false);
            errorPanel.setVisibility(View.GONE);
            progress.setVisibility(View.VISIBLE);
        }

        @Override public void onPageFinished(WebView view, String url) {
            if (view != webView || mainFrameFailed || !isMusicUrl(Uri.parse(url))
                    || !isMusicUrl(Uri.parse(safeCurrentUrl()))) return;
            final long expectedPage = pageGeneration;
            view.evaluateJavascript(injectionScript, value -> {
                if (view != webView || expectedPage != pageGeneration || destroyed) return;
                pageReady = "true".equals(value);
                progress.setVisibility(View.INVISIBLE);
                if (!pageReady) {
                    showError("The TV controls could not start. Reload the player and try again.");
                    return;
                }
                callScript("setChromeExpanded(" + chromeExpanded + ")", null);
                if (!foreground) callScript("suspend()", null);
                else {
                    if (chromeExpanded) homeButton.requestFocus();
                    else focusPlayer();
                    if (!cursorHintShown) {
                        cursorHintShown = true;
                        Toast.makeText(MainActivity.this,
                                "Arrows move cursor · OK click · Menu shows controls", Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (view == webView && request.isForMainFrame()) {
                mainFrameFailed = true;
                showError("YouTube Music could not load. Check the TV's internet connection, then try again.");
            }
        }

        @Override public void onReceivedHttpError(WebView view, WebResourceRequest request,
                WebResourceResponse response) {
            if (view == webView && request.isForMainFrame() && response.getStatusCode() >= 400) {
                mainFrameFailed = true;
                showError("YouTube Music returned an error (" + response.getStatusCode() + "). Try again shortly.");
            }
        }

        @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (view == webView) {
                pageGeneration++;
                pageReady = false;
                hideCursor();
                setLyricsVisible(false);
                webView = null;
                webContainer.removeView(view);
                view.destroy();
                showError("The TV released the music player to free memory. Select Try again to reopen it.");
            }
            return true;
        }
    }

    private boolean routeNavigation(Uri uri) {
        if (isMusicUrl(uri)) return false;
        if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null) {
            String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
            if (host.equals("accounts.google.com") || host.endsWith(".accounts.google.com")) {
                showAccount(uri);
            } else {
                showDialog(dialogBuilder("Open in browser")
                        .setMessage("This link opens in the TV browser.")
                        .setNegativeButton("Stay here", null)
                        .setPositiveButton("Open browser", (dialog, which) -> openExternal(uri)).create(), null);
            }
        } else {
            Toast.makeText(this, "Only secure web links are supported.", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException error) {
            showDialog(dialogBuilder("Browser unavailable")
                    .setMessage("No browser is installed on this TV. You can keep listening here.")
                    .setPositiveButton("Return to music", null).create(), null);
        }
    }

    private static boolean isMusicUrl(Uri uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && "music.youtube.com".equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null;
    }

    private String safeCurrentUrl() {
        String url = webView == null ? null : webView.getUrl();
        return url == null ? "" : url;
    }

    private String buildInjectionScript() throws IOException {
        String css = readAsset("lyrics/core.css") + "\n" + readAsset("tv.css");
        return "(function(){if(location.protocol!=='https:'||location.hostname!=='music.youtube.com'"
                + "||(location.port&&location.port!=='443'))return false;"
                + "if(window.__musicForTvInjected)return !!window.TvMusic;"
                + "try{var s=document.createElement('style');s.id='music-for-tv-style';s.textContent="
                + jsString(css) + ";(document.head||document.documentElement).appendChild(s);\n"
                + readAsset("lyrics/core.js") + "\nwindow.__musicForTvNative=true;\n" + readAsset("tv.js")
                + "\nwindow.__musicForTvInjected=true;return !!window.TvMusic;}catch(e){return false;}})();";
    }

    private String readAsset(String path) throws IOException {
        try (InputStream input = getAssets().open(path);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private boolean requirePlayer() {
        if (pageReady && webView != null && !mainFrameFailed) return true;
        Toast.makeText(this, "Wait for the player to load, or select Reload.", Toast.LENGTH_SHORT).show();
        return false;
    }

    private void callScript(String expression, ValueCallback<String> callback) {
        WebView player = webView;
        if (destroyed || player == null || !pageReady || !isMusicUrl(Uri.parse(safeCurrentUrl()))) {
            if (callback != null) callback.onReceiveValue("null");
            return;
        }
        player.evaluateJavascript("(function(){if(location.protocol!=='https:'||location.hostname!=='music.youtube.com'"
                + "||(location.port&&location.port!=='443')||!window.TvMusic)return null;"
                + "try{return window.TvMusic." + expression + ";}catch(e){return null;}})()", callback);
    }

    private void toggleLyrics() {
        if (!requirePlayer()) return;
        final long expectedPage = pageGeneration;
        callScript("toggleLyrics()", result -> {
            if (expectedPage != pageGeneration || destroyed) return;
            setLyricsVisible("true".equals(result));
            focusPlayer();
        });
    }

    private void setLyricsVisible(boolean visible) {
        if (lyricsVisible == visible) return;
        lyricsVisible = visible;
        lyricsButton.setText(visible ? "Lyrics on" : "Lyrics");
        lyricsGeneration++;
        currentLyricsKey = "";
        pollInFlight = false;
        handler.removeCallbacks(lyricsPoll);
        if (canPollLyrics()) handler.post(lyricsPoll);
    }

    private boolean canPollLyrics() {
        return !destroyed && foreground && lyricsVisible && pageReady && webView != null;
    }

    private void pollLyrics() {
        final WebView expectedView = webView;
        final long expectedPage = pageGeneration;
        final long expectedPoll = lyricsGeneration;
        pollInFlight = true;
        callScript("isLyricsVisible()?{visible:true,request:window.TvMusic.getLyricsRequest()}:{visible:false}", result -> {
            if (expectedView != webView || expectedPage != pageGeneration || expectedPoll != lyricsGeneration) return;
            pollInFlight = false;
            if (!canPollLyrics()) return;
            try {
                Object value = new JSONTokener(result == null ? "null" : result).nextValue();
                if (value instanceof String) value = new JSONTokener((String) value).nextValue();
                if (!(value instanceof JSONObject)) return;
                JSONObject snapshot = (JSONObject) value;
                if (!snapshot.optBoolean("visible", false)) {
                    setLyricsVisible(false);
                    return;
                }
                JSONObject metadata = snapshot.optJSONObject("request");
                if (metadata == null) {
                    if (!currentLyricsKey.isEmpty()) {
                        currentLyricsKey = "";
                        lyricsGeneration++;
                    }
                    return;
                }
                String key = lyricsKey(metadata);
                if (key.equals(currentLyricsKey)) return;
                currentLyricsKey = key;
                final long requestGeneration = ++lyricsGeneration;
                lyricsRepository.request(metadata, payload -> {
                    if (destroyed || !canPollLyrics() || webView != expectedView
                            || expectedPage != pageGeneration || requestGeneration != lyricsGeneration
                            || !key.equals(currentLyricsKey)) return;
                    // Payload is JSON data; never interpolate unquoted remote lyric text into JavaScript.
                    callScript("receiveLyrics(JSON.parse(" + jsString(payload.toString()) + "))", null);
                });
            } catch (JSONException ignored) {
                // A transient incomplete player snapshot will be retried on the next one-second tick.
            }
        });
    }

    private static String lyricsKey(JSONObject metadata) {
        return metadata.optString("trackId") + '\n' + metadata.optString("title") + '\n'
                + metadata.optString("artist") + '\n' + metadata.optString("album") + '\n'
                + metadata.optDouble("duration", 0) + '\n' + metadata.optString("retryNonce");
    }

    private static String jsString(String text) {
        // Older TV WebViews predate JavaScript's JSON-superset string-literal rules.
        return JSONObject.quote(text).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (isMediaKey(key) && pageReady) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                String action = key == KeyEvent.KEYCODE_MEDIA_NEXT ? "next"
                        : key == KeyEvent.KEYCODE_MEDIA_PREVIOUS ? "previous"
                        : key == KeyEvent.KEYCODE_MEDIA_PLAY ? "play"
                        : key == KeyEvent.KEYCODE_MEDIA_PAUSE ? "pause" : "playpause";
                callScript("media(" + JSONObject.quote(action) + ")", null);
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                setChromeExpanded(!chromeExpanded, true);
            }
            return true;
        }
        if (webView != null && webView.hasFocus() && pageReady && foreground) {
            if (isDirectionKey(key)) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) moveCursor(key, event.getRepeatCount());
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                    || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    clickCursor();
                }
                return true;
            }
            if (key == KeyEvent.KEYCODE_SEARCH && event.getAction() == KeyEvent.ACTION_DOWN) {
                showSearch();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isMediaKey(int key) {
        return key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PLAY
                || key == KeyEvent.KEYCODE_MEDIA_PAUSE || key == KeyEvent.KEYCODE_MEDIA_NEXT
                || key == KeyEvent.KEYCODE_MEDIA_PREVIOUS;
    }

    private static boolean isDirectionKey(int key) {
        return key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    private void centerCursor() {
        if (webView == null || webView.getWidth() < 1 || webView.getHeight() < 1) return;
        cursorX = webView.getWidth() * 0.5f;
        cursorY = webView.getHeight() * 0.5f;
        cursorInitialized = true;
        if (cursorOverlay != null) cursorOverlay.setPosition(cursorX, cursorY);
    }

    private void updateCursorVisibility() {
        if (cursorOverlay == null) return;
        boolean visible = !destroyed && foreground && pageReady && hasWindowFocus()
                && webView != null && webView.hasFocus() && errorPanel.getVisibility() != View.VISIBLE;
        if (visible) {
            if (!cursorInitialized) centerCursor();
            cursorOverlay.setVisibility(cursorInitialized ? View.VISIBLE : View.INVISIBLE);
        } else hideCursor();
    }

    private void hideCursor() {
        cancelCursorInteraction();
        if (cursorOverlay != null) cursorOverlay.setVisibility(View.INVISIBLE);
    }

    private void focusToolbar() {
        setChromeExpanded(true, true);
    }

    private void moveCursor(int key, int repeatCount) {
        WebView player = webView;
        if (player == null || player.getWidth() < 1 || player.getHeight() < 1) return;
        if (!cursorInitialized) centerCursor();
        float density = getResources().getDisplayMetrics().density;
        float step = density * Math.min(32f, 8f + repeatCount * 1.6f);
        float dx = key == KeyEvent.KEYCODE_DPAD_LEFT ? -step
                : key == KeyEvent.KEYCODE_DPAD_RIGHT ? step : 0f;
        float dy = key == KeyEvent.KEYCODE_DPAD_UP ? -step
                : key == KeyEvent.KEYCODE_DPAD_DOWN ? step : 0f;
        float inset = Math.min(dp(3), Math.min(player.getWidth(), player.getHeight()) * 0.1f);
        float maxX = Math.max(inset, player.getWidth() - inset - 1);
        float maxY = Math.max(inset, player.getHeight() - inset - 1);
        float nextX = cursorX + dx;
        float nextY = cursorY + dy;
        boolean edgeX = nextX < inset || nextX > maxX;
        boolean edgeY = nextY < inset || nextY > maxY;
        cursorX = Math.max(inset, Math.min(maxX, nextX));
        cursorY = Math.max(inset, Math.min(maxY, nextY));
        cursorOverlay.setPosition(cursorX, cursorY);
        updateCursorVisibility();
        float scrollX = edgeX ? Math.signum(dx) * 0.15f : 0f;
        float scrollY = edgeY ? Math.signum(dy) * 0.15f : 0f;
        if (cursorScrollPending && (scrollX != pendingScrollX || scrollY != pendingScrollY)) {
            // Moving away from an edge must invalidate its pending toolbar decision.
            cursorInteractionGeneration++;
            cursorScrollPending = false;
        }
        if (edgeX || edgeY) {
            scrollAtCursor(scrollX, scrollY);
        }
    }

    private void scrollAtCursor(float dxFraction, float dyFraction) {
        WebView player = webView;
        long now = SystemClock.uptimeMillis();
        if (player == null || cursorScrollPending || now - lastCursorScrollAt < 80L) return;
        cursorScrollPending = true;
        pendingScrollX = dxFraction;
        pendingScrollY = dyFraction;
        lastCursorScrollAt = now;
        final long expectedInteraction = cursorInteractionGeneration;
        final long expectedPage = pageGeneration;
        String expression = "scrollAt(" + (cursorX / player.getWidth()) + ","
                + (cursorY / player.getHeight()) + "," + dxFraction + "," + dyFraction + ")";
        callScript(expression, result -> {
            if (expectedInteraction != cursorInteractionGeneration || expectedPage != pageGeneration
                    || player != webView || destroyed) return;
            cursorScrollPending = false;
            if (dyFraction < 0 && !"true".equals(result) && player.hasFocus() && foreground) {
                focusToolbar();
            }
        });
    }

    private void clickCursor() {
        WebView player = webView;
        if (player == null || !foreground || !pageReady) return;
        if (!cursorInitialized) centerCursor();
        if (!cursorInitialized) return;
        if (!chromeExpanded && controlsHandle.getVisibility() == View.VISIBLE
                && cursorX >= controlsHandle.getLeft() && cursorX < controlsHandle.getRight()
                && cursorY >= controlsHandle.getTop() && cursorY < controlsHandle.getBottom()) {
            controlsHandle.performClick();
            return;
        }
        cursorTouchDownTime = SystemClock.uptimeMillis();
        cursorTouchDown = true;
        try {
            dispatchCursorTouch(player, MotionEvent.ACTION_DOWN, cursorTouchDownTime);
            if (cursorTouchDown && player == webView && foreground && pageReady) {
                cursorTouchDown = false;
                dispatchCursorTouch(player, MotionEvent.ACTION_UP, SystemClock.uptimeMillis());
            }
        } finally {
            if (cursorTouchDown) {
                cursorTouchDown = false;
                if (player == webView) {
                    dispatchCursorTouch(player, MotionEvent.ACTION_CANCEL, SystemClock.uptimeMillis());
                }
            }
        }
    }

    private void dispatchCursorTouch(WebView player, int action, long eventTime) {
        MotionEvent touch = MotionEvent.obtain(cursorTouchDownTime, eventTime,
                action, cursorX, cursorY, 0);
        touch.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            player.dispatchTouchEvent(touch);
        } finally {
            touch.recycle();
        }
    }

    private void cancelCursorInteraction() {
        cursorInteractionGeneration++;
        cursorScrollPending = false;
        lastCursorScrollAt = 0L;
        if (cursorTouchDown && webView != null) {
            cursorTouchDown = false;
            dispatchCursorTouch(webView, MotionEvent.ACTION_CANCEL, SystemClock.uptimeMillis());
        }
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (chromeExpanded && errorPanel.getVisibility() != View.VISIBLE) {
            setChromeExpanded(false, true);
            return;
        }
        if (webView == null || !pageReady) { super.onBackPressed(); return; }
        final WebView player = webView;
        final long expectedPage = pageGeneration;
        callScript("back()", result -> {
            if (destroyed || player != webView || expectedPage != pageGeneration) return;
            if ("true".equals(result)) {
                // JS can dismiss its lyrics overlay without a native button press.
                if (lyricsVisible) {
                    callScript("isLyricsVisible()", visible -> {
                        if ("false".equals(visible) && expectedPage == pageGeneration) setLyricsVisible(false);
                    });
                }
                return;
            }
            if (player.canGoBack()) player.goBack();
            else MainActivity.super.onBackPressed();
        });
    }

    private void focusPlayer() {
        if (errorPanel.getVisibility() == View.VISIBLE) {
            retryButton.requestFocus();
        } else if (webView != null && activeDialog == null) {
            webView.requestFocus();
            updateCursorVisibility();
        }
    }

    private void showError(String message) {
        pageReady = false;
        hideCursor();
        setLyricsVisible(false);
        errorText.setText(message);
        errorPanel.setVisibility(View.VISIBLE);
        progress.setVisibility(View.INVISIBLE);
        retryButton.requestFocus();
    }

    private void retry() {
        setLyricsVisible(false);
        pageReady = false;
        hideCursor();
        cursorInitialized = false;
        mainFrameFailed = false;
        errorPanel.setVisibility(View.GONE);
        try {
            if (injectionScript == null) injectionScript = buildInjectionScript();
            if (webView == null) createWebView();
            webView.loadUrl(HOME);
        } catch (IOException | RuntimeException error) {
            showError("The player could not restart. Update Android System WebView on this TV, then try again.");
        }
    }

    private AlertDialog.Builder dialogBuilder(String title) {
        return new AlertDialog.Builder(this, R.style.AppDialogTheme).setTitle(title);
    }

    /** One dialog at a time; every cancel path returns to a usable native focus target. */
    private void showDialog(AlertDialog dialog, Runnable onShown) {
        if (activeDialog != null) activeDialog.dismiss();
        activeDialog = dialog;
        hideCursor();
        dialog.setOnDismissListener(ignored -> {
            if (activeDialog != dialog) return;
            activeDialog = null;
            if (destroyed || !foreground) return;
            if (errorPanel.getVisibility() == View.VISIBLE) retryButton.requestFocus();
            else if (chromeExpanded) homeButton.requestFocus();
            else focusPlayer();
        });
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(surfaceBackground(SURFACE, dp(14)));
                dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams layout = dialog.getWindow().getAttributes();
                layout.dimAmount = 0.62f;
                dialog.getWindow().setAttributes(layout);
                dialog.getWindow().setLayout(Math.min(dp(540), getResources().getDisplayMetrics().widthPixels - dp(48)),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            for (int which : new int[] {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE,
                    AlertDialog.BUTTON_NEUTRAL}) {
                Button button = dialog.getButton(which);
                if (button == null) continue;
                button.setAllCaps(false);
                button.setTextSize(13);
                button.setTextColor(ACCENT);
                button.setBackground(buttonBackground(false));
                button.setOnFocusChangeListener((view, focused) -> {
                    button.setBackground(buttonBackground(focused));
                    button.setTextColor(focused ? BACKGROUND : TEXT);
                });
                // The positive button may already hold focus before onShow runs.
                button.setBackground(buttonBackground(button.hasFocus()));
                button.setTextColor(button.hasFocus() ? BACKGROUND : TEXT);
            }
            TextView message = dialog.findViewById(android.R.id.message);
            if (message != null) {
                message.setTextColor(MUTED);
                message.setTextSize(15);
                message.setLineSpacing(dp(4), 1f);
            }
            if (onShown != null) onShown.run();
        });
        dialog.show();
    }

    private void showMore() {
        setChromeExpanded(false, false);
        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        items.setPadding(dp(20), dp(8), dp(20), dp(12));
        AlertDialog menu = dialogBuilder("More").setView(items).setNegativeButton("Close", null).create();
        String[] labels = {"Reload player", "Remote guide", "Account"};
        for (int i = 0; i < labels.length; i++) {
            final int action = i;
            Button item = makeButton(labels[i]);
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            layout.topMargin = dp(6);
            items.addView(item, layout);
            item.setOnClickListener(view -> {
                menu.dismiss();
                if (action == 0) retry();
                else if (action == 1) showHelp();
                else showAccount(null);
            });
        }
        showDialog(menu, () -> items.getChildAt(0).requestFocus());
    }

    private void showAccount(Uri requestedUrl) {
        setChromeExpanded(false, false);
        Uri browserUrl = requestedUrl == null ? Uri.parse(HOME) : requestedUrl;
        showDialog(dialogBuilder("Your account")
                .setMessage("Google blocks sign-in inside embedded players. Listen here as a guest, or open YouTube and choose Music to use your Google account. Your lyrics view stays in this app.\n\nBrowser sign-in stays in the browser and does not sign this app in.")
                .setNegativeButton("Keep listening", null)
                .setNeutralButton("Open browser", (dialog, which) -> openExternal(browserUrl))
                .setPositiveButton("Open YouTube", (dialog, which) -> openYouTube()).create(), null);
    }

    private void openYouTube() {
        String packageName = "com.google.android.youtube.tv";
        Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(packageName);
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch != null) {
            try {
                startActivity(launch);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // A disabled or unavailable TV app should leave this player usable.
            }
        }
        showDialog(dialogBuilder("YouTube unavailable")
                .setMessage("The YouTube TV app could not be opened. Check that it is installed and enabled, then choose Music inside YouTube. You can keep listening here as a guest.")
                .setPositiveButton("Keep listening", null).create(), null);
    }

    private void showHelp() {
        setChromeExpanded(false, false);
        showDialog(dialogBuilder("Remote guide")
                .setMessage("Arrows move the cursor. Hold to move faster. OK clicks. At a page edge, keep moving to scroll.\n\nMenu opens or hides the controls. Down from the controls returns to music; Up at the top of the page reveals them.\n\nUse Lyrics for available synchronized lyrics. Play, pause and skip work with your remote’s media keys.")
                .setPositiveButton("Return to music", null).create(), null);
    }

    private void showSearch() {
        if (webView == null) {
            Toast.makeText(this, "Open More → Reload player first.", Toast.LENGTH_SHORT).show();
            return;
        }
        setChromeExpanded(false, false);
        EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setTextSize(18);
        query.setTextColor(TEXT);
        query.setHintTextColor(MUTED);
        query.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        query.setHint("Song, artist, or album");
        query.setInputType(InputType.TYPE_CLASS_TEXT);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        LinearLayout fieldContainer = new LinearLayout(this);
        fieldContainer.setPadding(dp(24), dp(8), dp(24), dp(8));
        fieldContainer.addView(query, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        AlertDialog dialog = dialogBuilder("Find your music")
                .setView(fieldContainer).setNegativeButton("Cancel", null)
                .setPositiveButton("Search", null).create();
        query.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
        });
        showDialog(dialog, () -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                String text = query.getText().toString().trim();
                if (text.isEmpty()) { query.setError("Enter a song or artist"); return; }
                if (webView != null) {
                    Uri url = Uri.parse(HOME).buildUpon().appendPath("search")
                            .appendQueryParameter("q", text).build();
                    if (pageReady) {
                        callScript("searchFor(" + jsString(text) + ")", navigated -> {
                            if (!"true".equals(navigated) && webView != null) webView.loadUrl(url.toString());
                        });
                    } else webView.loadUrl(url.toString());
                }
                dialog.dismiss();
            });
            query.requestFocus();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        if (webView != null) webView.onResume();
        updateCursorVisibility();
        callScript("resume()", null);
        handler.removeCallbacks(lyricsPoll);
        if (canPollLyrics()) handler.post(lyricsPoll);
    }

    @Override protected void onPause() {
        foreground = false;
        hideCursor();
        handler.removeCallbacks(lyricsPoll);
        lyricsGeneration++;
        currentLyricsKey = "";
        pollInFlight = false;
        callScript("suspend()", null);
        if (webView != null) webView.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (activeDialog != null) activeDialog.dismiss();
        hideCursor();
        pageGeneration++;
        handler.removeCallbacksAndMessages(null);
        if (lyricsRepository != null) lyricsRepository.close();
        if (webView != null) {
            WebView player = webView;
            webView = null;
            webContainer.removeView(player);
            player.stopLoading();
            player.setWebChromeClient(null);
            player.setWebViewClient(new WebViewClient());
            player.destroy();
        }
        injectionScript = null;
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        updateCursorVisibility();
    }
}
