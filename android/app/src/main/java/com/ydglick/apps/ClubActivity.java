package com.ydglick.apps;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * An in-app browser tab for the user's own online club / course account.
 *
 * <p>This is a viewer, not a copy: nothing is mirrored or cached into the APK. The user enters the
 * club's address once, signs in with their own subscription, and the session cookie lives in this
 * WebView so the club opens straight from the game.
 */
public class ClubActivity extends AppCompatActivity {

    private static final String PREFS = "club";
    private static final String KEY_URL = "home_url";

    private WebView webView;
    private ProgressBar progress;
    private View setupPanel;
    private EditText urlInput;
    private FrameLayout fullscreenContainer;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_club);

        webView = findViewById(R.id.club_web_view);
        progress = findViewById(R.id.club_progress);
        setupPanel = findViewById(R.id.club_setup);
        urlInput = findViewById(R.id.club_url_input);
        fullscreenContainer = findViewById(R.id.club_fullscreen);

        // targetSdk 35 draws edge-to-edge by default, so the toolbar and the setup form
        // have to keep themselves clear of the status and navigation bars. The fullscreen
        // video container is deliberately left un-inset.
        applySystemBarInsets(findViewById(R.id.club_content));
        applySystemBarInsets(setupPanel);

        findViewById(R.id.club_save).setOnClickListener(v -> saveUrlFromInput());
        findViewById(R.id.club_home).setOnClickListener(v -> loadHome());
        findViewById(R.id.club_reload).setOnClickListener(v -> webView.reload());
        findViewById(R.id.club_external).setOnClickListener(v -> openExternally());
        findViewById(R.id.club_settings).setOnClickListener(v -> showSetup());

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        // Logins on most sites hand out a third-party cookie somewhere in the redirect chain.
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    // Sign-in flows bounce through other hosts, so keep web navigation inside
                    // this tab and let the user reach for the browser button deliberately.
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                } catch (ActivityNotFoundException ignored) {
                    toast(getString(R.string.club_no_app));
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                fullscreenContainer.setVisibility(View.VISIBLE);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });

        // Course material is usually a download link rather than an inline viewer.
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException ignored) {
                toast(getString(R.string.club_no_app));
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (customView != null) {
                    exitFullscreen();
                } else if (setupPanel.getVisibility() == View.VISIBLE && hasHomeUrl()) {
                    setupPanel.setVisibility(View.GONE);
                } else if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else if (hasHomeUrl()) {
            loadHome();
        } else {
            showSetup();
        }
    }

    private static void applySystemBarInsets(View view) {
        final int left = view.getPaddingLeft();
        final int top = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int bottom = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(left + bars.left, top + bars.top, right + bars.right,
                    bottom + bars.bottom);
            return windowInsets;
        });
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private boolean hasHomeUrl() {
        return !TextUtils.isEmpty(prefs().getString(KEY_URL, null));
    }

    private void showSetup() {
        urlInput.setText(prefs().getString(KEY_URL, ""));
        setupPanel.setVisibility(View.VISIBLE);
    }

    private void saveUrlFromInput() {
        String url = urlInput.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            toast(getString(R.string.club_url_empty));
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        if (!URLUtil.isValidUrl(url)) {
            toast(getString(R.string.club_url_invalid));
            return;
        }
        prefs().edit().putString(KEY_URL, url).apply();
        setupPanel.setVisibility(View.GONE);
        loadHome();
    }

    private void loadHome() {
        String url = prefs().getString(KEY_URL, null);
        if (TextUtils.isEmpty(url)) {
            showSetup();
            return;
        }
        webView.loadUrl(url);
    }

    private void openExternally() {
        String url = webView.getUrl();
        if (TextUtils.isEmpty(url)) {
            url = prefs().getString(KEY_URL, null);
        }
        if (TextUtils.isEmpty(url)) {
            showSetup();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException ignored) {
            toast(getString(R.string.club_no_app));
        }
    }

    private void exitFullscreen() {
        if (customView == null) {
            return;
        }
        fullscreenContainer.removeView(customView);
        fullscreenContainer.setVisibility(View.GONE);
        customView = null;
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }
}
