package dev.ytmusictv;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Google-owned sign-in pages in the app's normal WebView profile. */
public final class SignInActivity extends Activity {
    private static final String MUSIC = "https://music.youtube.com/";
    static final String EXTRA_START_URL = "start_url";
    static final String SIGN_IN = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F";
    private static final int BG = Color.rgb(16, 17, 22), INK = Color.rgb(242, 238, 230);
    private static final int ACCENT = Color.rgb(203, 183, 146);
    private WebView web;
    private FrameLayout viewport;
    private CursorOverlayView pointer;
    private Button back;
    private TextView origin;
    private TextView address;
    private TextView status;
    private ProgressBar loading;
    private boolean foreground;
    private float x, y;
    private final Rect visibleWindow = new Rect();

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setResult(RESULT_CANCELED);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(10), dp(14), dp(10));
        setContentView(root);
        LinearLayout commands = new LinearLayout(this);
        commands.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("YOUR ACCOUNT", 12);
        commands.addView(brand, new LinearLayout.LayoutParams(0, dp(42), 1));
        back = button(commands, "Back", this::goBack);
        button(commands, "Cancel", this::finish);
        button(commands, "Return to Music", () -> load(MUSIC));
        root.addView(commands, new LinearLayout.LayoutParams(-1, dp(42)));
        origin = text("", 13);
        address = text("", 10);
        address.setSingleLine(true);
        address.setHorizontallyScrolling(true);
        address.setTextColor(Color.rgb(176, 173, 168));
        root.addView(origin, new LinearLayout.LayoutParams(-1, dp(22)));
        root.addView(address, new LinearLayout.LayoutParams(-1, dp(20)));
        loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loading.setMax(100);
        root.addView(loading, new LinearLayout.LayoutParams(-1, dp(2)));
        viewport = new FrameLayout(this);
        root.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
        pointer = new CursorOverlayView(this);
        viewport.addView(pointer, new FrameLayout.LayoutParams(pointer.getCursorViewSize(),
                pointer.getCursorViewSize(), Gravity.TOP | Gravity.LEFT));
        status = text("", 11);
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(24)));
        String requested = getIntent().getStringExtra(EXTRA_START_URL);
        load(NavigationPolicy.allowed(requested) ? requested : SIGN_IN);
    }

    private TextView text(String value, int size) {
        TextView text = new TextView(this);
        text.setText(value); text.setTextSize(size); text.setTextColor(INK);
        text.setGravity(Gravity.CENTER_VERTICAL);
        return text;
    }

    private Button button(LinearLayout row, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label); button.setTextSize(12); button.setAllCaps(false);
        button.setMinHeight(0); button.setMinimumHeight(0);
        button.setMinWidth(dp(66)); button.setPadding(dp(12), 0, dp(12), 0);
        button.setTextColor(INK); button.setBackground(buttonFill(false));
        button.setFocusableInTouchMode(true); button.setStateListAnimator(null);
        button.setOnFocusChangeListener((view, focused) -> {
            button.setBackground(buttonFill(focused)); button.setTextColor(focused ? BG : INK);
            updatePointer();
        });
        button.setOnClickListener(view -> { action.run(); if (!isFinishing()) focusPage(); });
        button.setOnKeyListener((view, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) focusPage();
            return true;
        });
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, dp(38));
        layout.setMarginStart(dp(6)); row.addView(button, layout);
        return button;
    }

    private GradientDrawable buttonFill(boolean focused) {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(focused ? ACCENT : Color.rgb(34, 35, 42)); fill.setCornerRadius(dp(6));
        return fill;
    }

    @SuppressWarnings("deprecation")
    private void createWeb() {
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        web = new WebView(this); web.setBackgroundColor(Color.WHITE);
        web.setFocusable(true); web.setFocusableInTouchMode(true); web.setDefaultFocusHighlightEnabled(false);
        web.setOnFocusChangeListener((view, focused) -> updatePointer());
        web.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                x = (right - left) / 2f; y = (bottom - top) / 2f;
                placePointer();
            }
        });
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false); settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false); settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setUseWideViewPort(true); settings.setLoadWithOverviewMode(true);
        settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) {
                loading.setProgress(progress); loading.setVisibility(progress == 100 ? View.INVISIBLE : View.VISIBLE);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (allowNavigation(request.getUrl().toString())) return false;
                status.setText("Blocked: only HTTPS Google and YouTube pages are allowed.");
                return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (allowNavigation(url)) return false;
                status.setText("Blocked: only HTTPS Google and YouTube pages are allowed.");
                return true;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                if (!allowNavigation(url)) { view.stopLoading(); return; }
                showAddress(url);
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) { showAddress(url); }
            @Override public void onPageFinished(WebView view, String url) {
                Log.i("TVMusicSignIn", "finished " + NavigationPolicy.safeLocation(url));
                showAddress(url);
                // This reports navigation completion, not authentication success.
                // The music page in MainActivity refreshes its own account state.
                if (!isFinishing() && NavigationPolicy.isMusic(url)
                        && NavigationPolicy.isMusic(view.getUrl())) {
                    CookieManager.getInstance().flush();
                    setResult(RESULT_OK);
                    finish();
                }
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    Log.w("TVMusicSignIn", "main-frame error=" + error.getErrorCode()
                            + " " + NavigationPolicy.safeLocation(request.getUrl().toString()));
                    status.setText("Page could not load. Use Back or Return to Music.");
                }
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                viewport.removeView(view); view.destroy(); web = null; updatePointer();
                status.setText("Page stopped. Select Return to Music to reopen it."); back.requestFocus();
                return true;
            }
        });
        viewport.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
    }

    private boolean allowNavigation(String url) {
        boolean allowed = NavigationPolicy.allowed(url);
        Log.i("TVMusicSignIn", (allowed ? "allowed " : "blocked ") + NavigationPolicy.safeLocation(url));
        return allowed;
    }

    private void load(String url) {
        if (web == null) createWeb();
        status.setText("Arrows move cursor · OK clicks · Menu opens controls · Enter details only on Google's page");
        web.loadUrl(url); focusPage();
    }

    private void showAddress(String url) {
        String safe = NavigationPolicy.safeLocation(url);
        Uri uri = Uri.parse(safe);
        origin.setText(uri.getScheme() == null ? "[invalid URL]" : uri.getScheme() + "://" + uri.getHost());
        address.setText(safe); address.setContentDescription(safe);
    }

    private void focusPage() { if (web != null) { web.requestFocus(); updatePointer(); } }
    private void updatePointer() {
        if (pointer != null) pointer.setVisibility(foreground && hasWindowFocus()
                && web != null && web.hasFocus() ? View.VISIBLE : View.INVISIBLE);
    }
    private void placePointer() {
        pointer.setPosition(x, y);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) back.requestFocus();
            return true;
        }
        if (web != null && web.hasFocus() && !imeVisible()) {
            boolean horizontal = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
            boolean vertical = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
            if (horizontal || vertical) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int sign = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                    float step = dp(Math.min(28, 8 + event.getRepeatCount() * 2));
                    float next = (horizontal ? x : y) + sign * step;
                    float limit = (horizontal ? web.getWidth() : web.getHeight()) - dp(3);
                    boolean edge = next < dp(3) || next > limit;
                    if (horizontal) x = Math.max(dp(3), Math.min(limit, next));
                    else y = Math.max(dp(3), Math.min(limit, next));
                    placePointer();
                    if (edge) {
                        if (key == KeyEvent.KEYCODE_DPAD_UP && web.getScrollY() <= 0) back.requestFocus();
                        else web.scrollBy(horizontal ? sign * dp(60) : 0, vertical ? sign * dp(60) : 0);
                    }
                }
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    long now = SystemClock.uptimeMillis();
                    touch(MotionEvent.ACTION_DOWN, now); touch(MotionEvent.ACTION_UP, now);
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean imeVisible() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
            if (insets != null) return insets.isVisible(WindowInsets.Type.ime());
        }
        getWindow().getDecorView().getWindowVisibleDisplayFrame(visibleWindow);
        return getResources().getDisplayMetrics().heightPixels - visibleWindow.bottom > dp(100);
    }

    private void touch(int action, long now) {
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { if (web != null) web.dispatchTouchEvent(event); } finally { event.recycle(); }
    }
    private void goBack() {
        if (web != null && web.canGoBack()) web.goBack(); else finish();
    }
    @SuppressWarnings("deprecation") @Override public void onBackPressed() { goBack(); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); updatePointer(); }
    @Override protected void onResume() {
        super.onResume(); foreground = true; if (web != null) web.onResume(); updatePointer();
    }
    @Override protected void onPause() {
        foreground = false; updatePointer(); if (web != null) web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }
    @Override protected void onDestroy() {
        if (web != null) { viewport.removeView(web); web.stopLoading(); web.destroy(); web = null; }
        super.onDestroy();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    /** Strict navigation policy and a query-free representation safe for diagnostics. */
    static final class NavigationPolicy {
        private NavigationPolicy() { }

        static boolean allowed(String url) {
            try {
                URI uri = new URI(url);
                String host = uri.getHost();
                if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
                        || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
                host = host.toLowerCase(Locale.ROOT);
                return host.equals("google.com") || host.endsWith(".google.com")
                        || host.equals("youtube.com") || host.endsWith(".youtube.com")
                        || host.equals("accounts.google.com.pk");
            } catch (URISyntaxException | NullPointerException error) {
                return false;
            }
        }

        static String safeLocation(String url) {
            try {
                URI uri = new URI(url);
                String host = uri.getHost();
                if (host == null || uri.getScheme() == null) return "[invalid URL]";
                String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
                String path = uri.getRawPath();
                return scheme + "://" + host.toLowerCase(Locale.ROOT)
                        + (path == null || path.isEmpty() ? "/" : path);
            } catch (URISyntaxException | NullPointerException error) {
                return "[invalid URL]";
            }
        }
        static boolean isMusic(String url) {
            try {
                URI uri = new URI(url);
                return allowed(url) && "music.youtube.com".equalsIgnoreCase(uri.getHost());
            } catch (URISyntaxException | NullPointerException error) {
                return false;
            }
        }
    }
}
