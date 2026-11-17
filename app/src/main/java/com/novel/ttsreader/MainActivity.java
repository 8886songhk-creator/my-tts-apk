package com.novel.ttsreader;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {

    // 아래 큰따옴표 안의 주소만 본인의 깃허브 페이지 주소로 바꿔 주세요!
    private static final String MY_WEB_URL = "https://8886songhk-creator.github.io/studio-app/";

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private static final int FILE_REQ_CODE = 200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 100);
            }
        }

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(settings.getUserAgentString().replace("; wv", ""));

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_REQ_CODE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new TtsBridge(), "AndroidTTS");

        TtsService.setProgressCallback(new TtsService.ProgressCallback() {
            @Override
            public void onSentenceStart(int index) {
                webView.post(() -> webView.evaluateJavascript(
                        "if(window.onNativeTtsIndex) window.onNativeTtsIndex(" + index + ");", null));
            }

            @Override
            public void onFinished() {
                webView.post(() -> webView.evaluateJavascript(
                        "if(window.onNativeTtsDone) window.onNativeTtsDone();", null));
            }
        });

        webView.loadUrl(MY_WEB_URL);
    }

    public class TtsBridge {
        @JavascriptInterface
        public void speakList(String jsonSentences, int startIndex, float rate) {
            Intent intent = new Intent(MainActivity.this, TtsService.class);
            intent.putExtra("sentences", jsonSentences);
            intent.putExtra("startIndex", startIndex);
            intent.putExtra("rate", rate);
            startForegroundService(intent);
        }

        @JavascriptInterface
        public void speak(String text, float rate) {
            Intent intent = new Intent(MainActivity.this, TtsService.class);
            intent.putExtra("singleText", text);
            intent.putExtra("rate", rate);
            startForegroundService(intent);
        }

        @JavascriptInterface
        public void stop() {
            stopService(new Intent(MainActivity.this, TtsService.class));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_REQ_CODE && fileCallback != null) {
            Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileCallback.onReceiveValue(results);
            fileCallback = null;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        TtsService.setProgressCallback(null);
        super.onDestroy();
    }
}
