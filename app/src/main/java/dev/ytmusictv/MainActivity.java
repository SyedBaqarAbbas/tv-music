package dev.ytmusictv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
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
    private static final int BACKGROUND = Color.rgb(16, 19, 16);
    private static final int ACCENT = Color.rgb(183, 243, 107);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private FrameLayout webContainer;
    private CursorOverlayView cursorOverlay;
    private LinearLayout toolbar;
    private LinearLayout errorPanel;
    private TextView errorText;
    private ProgressBar progress;
    private Button homeButton;
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(22), dp(12), dp(22), dp(14));
        setContentView(root);

        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(0, 0, 0, dp(4));
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        TextView brand = new TextView(this);
        brand.setText(R.string.app_name);
        brand.setTextColor(ACCENT);
        brand.setTextSize(20);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        toolbar.addView(brand, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        homeButton = addToolbarButton("Home", v -> {
            if (webView == null) { retry(); return; }
            if (pageReady) callScript("home()", null);
            else webView.loadUrl(HOME);
            focusPlayer();
        });
        addToolbarButton("Search", v -> showSearch());
        lyricsButton = addToolbarButton("Lyrics", v -> toggleLyrics());
        addToolbarButton("Reload", v -> retry());
        addToolbarButton("Help", v -> showHelp());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));
        webContainer = new FrameLayout(this);
        root.addView(webContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        cursorOverlay = new CursorOverlayView(this);
        webContainer.addView(cursorOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        errorPanel = new LinearLayout(this);
        errorPanel.setOrientation(LinearLayout.VERTICAL);
        errorPanel.setGravity(Gravity.CENTER);
        errorPanel.setPadding(dp(48), dp(24), dp(48), dp(24));
        errorPanel.setBackgroundColor(BACKGROUND);
        errorText = new TextView(this);
        errorText.setTextColor(Color.WHITE);
        errorText.setTextSize(20);
        errorText.setGravity(Gravity.CENTER);
        errorPanel.addView(errorText, new LinearLayout.LayoutParams(
                dp(570), ViewGroup.LayoutParams.WRAP_CONTENT));
        retryButton = makeButton("Try again");
        retryButton.setOnClickListener(v -> retry());
        LinearLayout.LayoutParams retryLayout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
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
            if (event.getAction() == KeyEvent.ACTION_DOWN) focusPlayer();
            return true;
        });
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        layout.setMarginStart(dp(8));
        toolbar.addView(button, layout);
        return button;
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setId(View.generateViewId());
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinWidth(dp(76));
        button.setMinimumWidth(dp(76));
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setTextColor(Color.WHITE);
        button.setBackground(buttonBackground(false));
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setOnFocusChangeListener((view, hasFocus) -> {
            button.setBackground(buttonBackground(hasFocus));
            button.setTextColor(hasFocus ? BACKGROUND : Color.WHITE);
            if (hasFocus) hideCursor();
        });
        return button;
    }

    private GradientDrawable buttonBackground(boolean focused) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(focused ? ACCENT : Color.rgb(38, 43, 37));
        background.setCornerRadius(dp(8));
        return background;
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
                if (!foreground) callScript("suspend()", null);
                else {
                    focusPlayer();
                    if (!cursorHintShown) {
                        cursorHintShown = true;
                        Toast.makeText(MainActivity.this,
                                "Arrows move cursor · OK click · Menu toolbar", Toast.LENGTH_LONG).show();
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
            boolean googleSignIn = host.equals("accounts.google.com") || host.endsWith(".accounts.google.com");
            String message = googleSignIn
                    ? "Google may block sign-in inside embedded players. Opening this page in the TV browser will not sign this player in. You can still try listening without signing in."
                    : "Open this link in the TV's browser?";
            new AlertDialog.Builder(this).setTitle(googleSignIn ? "Google sign-in" : "Open browser")
                    .setMessage(message).setNegativeButton("Stay here", null)
                    .setPositiveButton("Open browser", (dialog, which) -> openExternal(uri)).show();
        } else {
            Toast.makeText(this, "Only secure web links are supported.", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException error) {
            new AlertDialog.Builder(this).setTitle("No browser available")
                    .setMessage("This TV has no browser that can open this link.")
                    .setPositiveButton("OK", null).show();
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
                + readAsset("lyrics/core.js") + "\n" + readAsset("tv.js")
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
                if (metadata == null) return;
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
                + metadata.optDouble("duration", 0);
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
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) focusToolbar();
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
        hideCursor();
        homeButton.requestFocus();
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
        if (webView != null && errorPanel.getVisibility() != View.VISIBLE) {
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

    private void showHelp() {
        new AlertDialog.Builder(this).setTitle("Remote controls")
                .setMessage("Arrows move the visible cursor. Hold an arrow to move faster. Press OK to click at the cursor. Keep moving at a page edge to scroll.\n\nPress Menu to reach the toolbar, or move Up past the top after scrolling all the way up. Press Down from the toolbar to return to the cursor.\n\nLyrics shows available synchronized lyrics for the current song. Play/pause and skip buttons control playback.\n\nThis independent player uses the official YouTube Music website. Google may restrict embedded sign-in; an external browser login does not sign this player in.")
                .setPositiveButton("Got it", null).show();
    }

    private void showSearch() {
        if (webView == null) {
            Toast.makeText(this, "Select Reload to open the player first.", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setTextSize(20);
        query.setHint("Song, artist, or album");
        query.setInputType(InputType.TYPE_CLASS_TEXT);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        LinearLayout fieldContainer = new LinearLayout(this);
        fieldContainer.setPadding(dp(24), dp(12), dp(24), 0);
        fieldContainer.addView(query, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Search YouTube Music")
                .setView(fieldContainer).setNegativeButton("Cancel", null)
                .setPositiveButton("Search", null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                String text = query.getText().toString().trim();
                if (text.isEmpty()) { query.setError("Enter a song or artist"); return; }
                if (webView != null) {
                    Uri url = Uri.parse(HOME).buildUpon().appendPath("search")
                            .appendQueryParameter("q", text).build();
                    webView.loadUrl(url.toString());
                }
                dialog.dismiss();
                focusPlayer();
            });
            query.requestFocus();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
        });
        query.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
        });
        dialog.show();
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
