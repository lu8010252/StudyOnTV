package com.studyontv.legacy;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Android 4.0+ 的离线练习页 + 整机管控 PoC 入口。 */
public class LegacyActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri> upload;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setDatabaseEnabled(true);
        web.getSettings().setAllowFileAccess(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !url.startsWith("file:///android_asset/");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            public void openFileChooser(ValueCallback<Uri> callback) { openFileChooser(callback, "application/json"); }
            public void openFileChooser(ValueCallback<Uri> callback, String accept) { openFileChooser(callback, accept, ""); }
            public void openFileChooser(ValueCallback<Uri> callback, String accept, String capture) {
                if (upload != null) upload.onReceiveValue(null);
                upload = callback;
                try { startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 7); }
                catch (RuntimeException error) { upload.onReceiveValue(null); upload = null; }
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/practice.html");
        LegacyServiceStarter.start(this);
    }

    @Override protected void onResume() {
        super.onResume();
        LegacyApp.enter();
    }

    @Override protected void onPause() {
        LegacyApp.leave();
        super.onPause();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            startActivity(new Intent(this, LegacySettingsActivity.class));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 7 && upload != null) { upload.onReceiveValue(result == RESULT_OK && data != null ? data.getData() : null); upload = null; }
    }
    @Override protected void onDestroy() {
        if (upload != null) upload.onReceiveValue(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
