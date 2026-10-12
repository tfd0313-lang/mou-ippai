package app.mouippai;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ゲーム本体(Web)を WebView で開き、AdMob の全画面広告を出す窓口を JavaScript に渡す。
 * 広告をいつ出すか(仕様 §17 の条件)は Web 側の Ads が決める。ここは「出せる広告があるか」
 * 「出して」「閉じた」の受け渡しだけをする。
 */
public class MainActivity extends Activity {
    private static final String BG = "#120C07";

    private WebView web;
    private String appHost;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean adsStarted = new AtomicBoolean(false);
    private volatile InterstitialAd interstitial;
    private boolean loading;
    private int failCount;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor(BG));
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor(BG));
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // Android 15 以降は画面の端まで描く前提になる。ステータスバー・ナビゲーションバー・
        // カメラの切り欠きの分だけ内側に寄せ、ゲームの操作ボタンが隠れないようにする
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);              // 記録(localStorage)
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setTextZoom(100);                        // 端末の文字サイズで画面が崩れないように

        appHost = Uri.parse(BuildConfig.START_URL).getHost();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (appHost != null && appHost.equalsIgnoreCase(u.getHost())) return false;
                // ゲームの外(GitHub など)はブラウザで開く。広告の窓口を外のページに渡さない
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) showOffline();
            }
        });
        web.addJavascriptInterface(new Bridge(), "MouIppaiApp");

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(BuildConfig.START_URL);

        // 戻る: アプリ内で開いたページ(プライバシーポリシー等)からはゲームへ戻る。
        // Android 13 以降は onBackPressed が呼ばれないことがあるので、新しい仕組みでも受ける
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBackOrFinish);
        }

        startConsentThenAds();
    }

    private void goBackOrFinish() {
        if (web.canGoBack()) web.goBack();
        else finish();
    }

    /** 初回に一度も読み込めなかったとき(圏外など)の案内。2回目以降は Web 側の保存で圏外でも動く */
    private void showOffline() {
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                + "<body style='margin:0;background:" + BG + ";color:#f3e7d6;font:16px/1.8 sans-serif;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;text-align:center'>"
                + "<div>通信できませんでした。<br>電波のある所で一度ひらくと、<br>そのあとは圏外でも遊べます。<br><br>"
                + "<a href='" + BuildConfig.START_URL + "' style='color:#E8A13C'>もう一度読み込む</a></div></body></html>";
        web.loadDataWithBaseURL(BuildConfig.START_URL, html, "text/html", "utf-8", null);
    }

    // ---------- 広告 ----------

    /** EEA・英国などで必要な同意の確認(日本だけで配信する間は何も表示されない) */
    private void startConsentThenAds() {
        ConsentInformation ci = UserMessagingPlatform.getConsentInformation(this);
        ConsentRequestParameters params = new ConsentRequestParameters.Builder().build();
        ci.requestConsentInfoUpdate(this, params,
                () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(this, formError -> {
                    if (ci.canRequestAds()) startAds();
                }),
                requestError -> { if (ci.canRequestAds()) startAds(); });
        if (ci.canRequestAds()) startAds();
    }

    private void startAds() {
        if (!adsStarted.compareAndSet(false, true)) return;
        new Thread(() -> {
            MobileAds.initialize(this, status -> { });
            main.post(this::loadInterstitial);
        }).start();
    }

    private void loadInterstitial() {
        if (loading || interstitial != null || isFinishing()) return;
        loading = true;
        InterstitialAd.load(this, BuildConfig.AD_UNIT_INTERSTITIAL, new AdRequest.Builder().build(),
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull InterstitialAd ad) {
                        loading = false; failCount = 0; interstitial = ad;
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError e) {
                        loading = false; interstitial = null;
                        // 取れないときは間を空けて取り直す(1分→2分→…最大10分)
                        failCount = Math.min(failCount + 1, 10);
                        main.postDelayed(MainActivity.this::loadInterstitial, 60_000L * failCount);
                    }
                });
    }

    private void showInterstitial() {
        InterstitialAd ad = interstitial;
        if (ad == null) { notifyWeb(false); loadInterstitial(); return; }
        interstitial = null;
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() { notifyWeb(true); loadInterstitial(); }

            @Override
            public void onAdFailedToShowFullScreenContent(@NonNull AdError e) { notifyWeb(false); loadInterstitial(); }
        });
        ad.show(this);
    }

    private void notifyWeb(boolean shown) {
        main.post(() -> web.evaluateJavascript("window.__adDone&&window.__adDone(" + shown + ")", null));
    }

    /** Web 側から呼ぶ窓口。名前は window.MouIppaiApp */
    private class Bridge {
        @JavascriptInterface
        public boolean adReady() { return interstitial != null; }

        @JavascriptInterface
        public void showAd() { main.post(MainActivity.this::showInterstitial); }

        @JavascriptInterface
        public String platform() { return "android"; }
    }

    // ---------- 画面の出入り ----------

    @Override
    protected void onPause() { super.onPause(); web.onPause(); }

    @Override
    protected void onResume() { super.onResume(); web.onResume(); }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (web != null) { web.removeJavascriptInterface("MouIppaiApp"); web.destroy(); }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() { goBackOrFinish(); }
}
