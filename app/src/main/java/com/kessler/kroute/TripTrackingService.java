package com.kessler.kroute;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

import androidx.annotation.Nullable;

import org.json.JSONObject;

public class TripTrackingService extends Service implements LocationListener {
    public static final String ACTION_START = "com.kessler.kroute.START_TRACKING";
    public static final String ACTION_STOP = "com.kessler.kroute.STOP_TRACKING";
    public static final String EXTRA_TRIP_ID = "trip_id";
    private static final int NOTIFICATION_ID = 2402;
    private static final String CHANNEL_ID = "kroute_trip_tracking";

    private LocationManager locationManager;
    private TripDatabase db;
    private long tripId;
    private Location lastLocation;
    private long lastTs;
    private double totalDistance;
    private double maxSpeedKmh;
    private long stoppedMs;
    private int pointCount;

    @Override
    public void onCreate() {
        super.onCreate();
        db = new TripDatabase(this);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            finishAndStop();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            tripId = intent.getLongExtra(EXTRA_TRIP_ID, 0);
            if (tripId <= 0) {
                stopSelf();
                return START_NOT_STICKY;
            }
            restoreState();
            startForeground(NOTIFICATION_ID, buildNotification());
            requestUpdates();
        }
        return START_NOT_STICKY;
    }

    private void restoreState() {
        try {
            JSONObject s = db.getTripState(tripId);
            totalDistance = s.optDouble("totalDistance", 0);
            maxSpeedKmh = s.optDouble("maxSpeed", 0);
            stoppedMs = s.optLong("stoppedSeconds", 0) * 1000L;
            pointCount = s.optInt("pointCount", 0);
            if (s.has("lastLat")) {
                Location l = new Location("db");
                l.setLatitude(s.optDouble("lastLat"));
                l.setLongitude(s.optDouble("lastLon"));
                lastLocation = l;
                lastTs = s.optLong("lastTs", 0);
            }
        } catch (Exception ignored) {
        }
    }

    private void requestUpdates() {
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 5f, this);
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 10f, this);
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        long now = System.currentTimeMillis();
        if (location == null) return;
        if (lastLocation != null) {
            float segment = lastLocation.distanceTo(location);
            if (segment >= 0 && segment < 1000) totalDistance += segment;
            long dt = lastTs > 0 ? now - lastTs : 0;
            double speedKmh = location.hasSpeed() ? location.getSpeed() * 3.6 : (dt > 0 ? segment / (dt / 1000.0) * 3.6 : 0);
            if (speedKmh > maxSpeedKmh && speedKmh < 250) maxSpeedKmh = speedKmh;
            if (dt > 0 && dt < 15000 && speedKmh < 3.0) stoppedMs += dt;
        } else if (location.hasSpeed()) {
            maxSpeedKmh = Math.max(maxSpeedKmh, location.getSpeed() * 3.6);
        }
        pointCount++;
        db.addPoint(tripId, location, totalDistance, maxSpeedKmh, stoppedMs, pointCount);
        lastLocation = new Location(location);
        lastTs = now;

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        Intent appIntent = new Intent(this, MainActivity.class);
        appIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 1, appIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, TripTrackingService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 2, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text = pointCount > 0
                ? String.format(java.util.Locale.US, "%.1f km registrados", totalDistance / 1000.0)
                : "Esperando GPS…";

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("K Route · viaje activo")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Detener y guardar", stop).build())
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Registro de viajes",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Mantiene el GPS activo mientras navega con Waze.");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }

    private void finishAndStop() {
        try {
            if (locationManager != null) locationManager.removeUpdates(this);
        } catch (Exception ignored) {
        }
        if (tripId <= 0) tripId = db.getActiveTripId();
        if (tripId > 0) db.finishTrip(tripId, totalDistance, maxSpeedKmh, stoppedMs, pointCount);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        try {
            if (locationManager != null) locationManager.removeUpdates(this);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
