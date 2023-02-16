package com.example.detection;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

public class WebVIewActivity extends AppCompatActivity {
    private WebView webView;

    @SuppressLint("StaticFieldLeak")
    public static Activity webViewActivity;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web_view);
        webView = findViewById(R.id.webView);

        webViewActivity = WebVIewActivity.this;

        //자동꺼짐 해제
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Intent intent = getIntent();
        String url = intent.getStringExtra("url");

        WebSettings webSettings = webView.getSettings();
        //웹뷰에서 재생가능한 콘텐츠를 자동으로 재생할 수 있도록 설정
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        //자바 스크립트를 쓸수 있게
        webSettings.setJavaScriptEnabled(true);
        // HTML 5 사양의 일부
        webSettings.setDomStorageEnabled(true);

        webView.setWebViewClient(new WebViewClient(){
            @SuppressLint("WebViewClientOnReceivedSslError")
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // 인증서 오류를 해결하지 않고 웹뷰로 접속을 진행
                handler.proceed();
            }
        });

        //웹뷰로 띄운 웹 페이지를 컨트롤하는 함수, 크롬에 맞춰  쾌적한 환경조성을 위한 세팅으로 보면 된다.
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // 권한 요청 승인 처리 코드
                request.grant(request.getResources());
            }
        });

        webView.loadUrl(url);
    }

    // 안드로이드 내에서 특정 키를 누를 때 동작하는 메소드
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_BACK) && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        //해당 액티비티가 실행중이 아니라면 스트리밍도 일시정지
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        //웹뷰가 가진 리소스를 모두 해제
        webView.stopLoading();
        webView.loadUrl("about:blank");
        webView.setWebViewClient(null);
        webView.setWebChromeClient(null);
        webView.destroy();
        webView = null;
        super.onDestroy();
    }
}