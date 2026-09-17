package com.kessler.kroute;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.WindowManager;

import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int LOCATION_REQUEST = 1001;
    private static final int NOTIFICATION_REQUEST = 1002;
    private WebView webView;
    private TripDatabase tripDb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        tripDb = new TripDatabase(this);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(settings.getUserAgentString() + " KRouteAndroid/2.0");

        webView.addJavascriptInterface(new NativeBridge(), "KRouteNative");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                notifyResume();
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                boolean granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                callback.invoke(origin, granted, false);
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, LOCATION_REQUEST);
        } else {
            requestNotificationPermissionIfNeeded();
        }

        webView.loadUrl("file:///android_asset/index.html");
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        }
    }

    private void notifyResume() {
        if (webView == null) return;
        webView.postDelayed(() -> webView.evaluateJavascript(
                "if(window.onKRouteResume){window.onKRouteResume();}", null), 250);
    }

    private void startTracking(long tripId) {
        Intent service = new Intent(this, TripTrackingService.class);
        service.setAction(TripTrackingService.ACTION_START);
        service.putExtra(TripTrackingService.EXTRA_TRIP_ID, tripId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
        else startService(service);
    }

    private void stopTracking() {
        Intent service = new Intent(this, TripTrackingService.class);
        service.setAction(TripTrackingService.ACTION_STOP);
        startService(service);
    }

    private class NativeBridge {
        @JavascriptInterface
        public String getTrips() {
            return tripDb.getTripsJson();
        }

        @JavascriptInterface
        public String getTripPoints(String id) {
            try {
                return tripDb.getTripPointsJson(Long.parseLong(id));
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public String getActiveTripId() {
            return String.valueOf(tripDb.getActiveTripId());
        }

        @JavascriptInterface
        public String startTrip(String json) {
            try {
                long active = tripDb.getActiveTripId();
                if (active > 0) return String.valueOf(active);
                JSONObject data = new JSONObject(json);
                long id = tripDb.startTrip(data);
                startTracking(id);
                return String.valueOf(id);
            } catch (Exception e) {
                return "0";
            }
        }

        @JavascriptInterface
        public void stopTrip() {
            stopTracking();
        }

        @JavascriptInterface
        public boolean isWazeInstalled() {
            return getPackageManager().getLaunchIntentForPackage("com.waze") != null;
        }

        @JavascriptInterface
        public void launchWaze(String json) {
            try {
                JSONObject d = new JSONObject(json);
                double lat = d.getDouble("lat");
                double lon = d.getDouble("lon");
                String url = "https://waze.com/ul?ll=" + lat + "%2C" + lon + "&navigate=yes&utm_source=kroute_v2";
                Uri uri = Uri.parse(url);
                Intent i = new Intent(Intent.ACTION_VIEW, uri);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (getPackageManager().getLaunchIntentForPackage("com.waze") != null) {
                    i.setPackage("com.waze");
                }
                try {
                    startActivity(i);
                } catch (Exception first) {
                    Intent fallback = new Intent(Intent.ACTION_VIEW, uri);
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(fallback);
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        notifyResume();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_REQUEST) {
            if (webView != null) webView.reload();
            requestNotificationPermissionIfNeeded();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
