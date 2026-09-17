package com.kessler.kroute;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

public class TripDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "kroute.db";
    private static final int DB_VERSION = 1;

    public TripDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trips (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "start_time INTEGER NOT NULL," +
                "end_time INTEGER," +
                "dest_lat REAL," +
                "dest_lon REAL," +
                "dest_label TEXT," +
                "planned_distance REAL DEFAULT 0," +
                "planned_duration REAL DEFAULT 0," +
                "route_label TEXT," +
                "status TEXT NOT NULL DEFAULT 'active'," +
                "total_distance REAL DEFAULT 0," +
                "max_speed REAL DEFAULT 0," +
                "avg_speed REAL DEFAULT 0," +
                "stopped_seconds INTEGER DEFAULT 0," +
                "point_count INTEGER DEFAULT 0" +
                ")");
        db.execSQL("CREATE TABLE trip_points (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "trip_id INTEGER NOT NULL," +
                "ts INTEGER NOT NULL," +
                "lat REAL NOT NULL," +
                "lon REAL NOT NULL," +
                "speed REAL DEFAULT 0," +
                "accuracy REAL DEFAULT 0," +
                "bearing REAL DEFAULT 0" +
                ")");
        db.execSQL("CREATE INDEX idx_trip_points_trip ON trip_points(trip_id, ts)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    public synchronized long startTrip(JSONObject data) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("start_time", System.currentTimeMillis());
        v.put("dest_lat", data.optDouble("destLat", 0));
        v.put("dest_lon", data.optDouble("destLon", 0));
        v.put("dest_label", data.optString("destLabel", "Destino"));
        v.put("planned_distance", data.optDouble("plannedDistance", 0));
        v.put("planned_duration", data.optDouble("plannedDuration", 0));
        v.put("route_label", data.optString("routeLabel", "Ruta"));
        v.put("status", "active");
        return db.insertOrThrow("trips", null, v);
    }

    public synchronized void addPoint(long tripId, Location loc, double totalDistance,
                                      double maxSpeedKmh, long stoppedMs, int pointCount) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues p = new ContentValues();
        p.put("trip_id", tripId);
        p.put("ts", System.currentTimeMillis());
        p.put("lat", loc.getLatitude());
        p.put("lon", loc.getLongitude());
        p.put("speed", loc.hasSpeed() ? loc.getSpeed() * 3.6 : 0);
        p.put("accuracy", loc.hasAccuracy() ? loc.getAccuracy() : 0);
        p.put("bearing", loc.hasBearing() ? loc.getBearing() : 0);
        db.insert("trip_points", null, p);

        ContentValues t = new ContentValues();
        t.put("total_distance", totalDistance);
        t.put("max_speed", maxSpeedKmh);
        t.put("stopped_seconds", stoppedMs / 1000L);
        t.put("point_count", pointCount);
        db.update("trips", t, "id=?", new String[]{String.valueOf(tripId)});
    }

    public synchronized void finishTrip(long tripId, double totalDistance, double maxSpeedKmh,
                                        long stoppedMs, int pointCount) {
        SQLiteDatabase db = getWritableDatabase();
        long end = System.currentTimeMillis();
        long start = end;
        try (Cursor c = db.rawQuery("SELECT start_time FROM trips WHERE id=?", new String[]{String.valueOf(tripId)})) {
            if (c.moveToFirst()) start = c.getLong(0);
        }
        double hours = Math.max(1, end - start) / 3600000.0;
        double avgSpeed = (totalDistance / 1000.0) / hours;
        ContentValues t = new ContentValues();
        t.put("end_time", end);
        t.put("status", "complete");
        t.put("total_distance", totalDistance);
        t.put("max_speed", maxSpeedKmh);
        t.put("avg_speed", avgSpeed);
        t.put("stopped_seconds", stoppedMs / 1000L);
        t.put("point_count", pointCount);
        db.update("trips", t, "id=?", new String[]{String.valueOf(tripId)});
    }

    public synchronized long getActiveTripId() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT id FROM trips WHERE status='active' ORDER BY id DESC LIMIT 1", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    public synchronized JSONObject getTripState(long tripId) {
        JSONObject o = new JSONObject();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT total_distance,max_speed,stopped_seconds,point_count,start_time FROM trips WHERE id=?",
                new String[]{String.valueOf(tripId)})) {
            if (c.moveToFirst()) {
                o.put("totalDistance", c.getDouble(0));
                o.put("maxSpeed", c.getDouble(1));
                o.put("stoppedSeconds", c.getLong(2));
                o.put("pointCount", c.getInt(3));
                o.put("startTime", c.getLong(4));
            }
        } catch (Exception ignored) {
        }
        try (Cursor c = db.rawQuery("SELECT lat,lon,ts FROM trip_points WHERE trip_id=? ORDER BY ts DESC LIMIT 1",
                new String[]{String.valueOf(tripId)})) {
            if (c.moveToFirst()) {
                o.put("lastLat", c.getDouble(0));
                o.put("lastLon", c.getDouble(1));
                o.put("lastTs", c.getLong(2));
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    public synchronized String getTripsJson() {
        JSONArray arr = new JSONArray();
        SQLiteDatabase db = getReadableDatabase();
        String sql = "SELECT id,start_time,end_time,dest_lat,dest_lon,dest_label,planned_distance,planned_duration,route_label,status,total_distance,max_speed,avg_speed,stopped_seconds,point_count FROM trips ORDER BY id DESC LIMIT 250";
        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("id", c.getLong(0));
                o.put("startTime", c.getLong(1));
                if (!c.isNull(2)) o.put("endTime", c.getLong(2));
                o.put("destLat", c.getDouble(3));
                o.put("destLon", c.getDouble(4));
                o.put("destLabel", c.getString(5));
                o.put("plannedDistance", c.getDouble(6));
                o.put("plannedDuration", c.getDouble(7));
                o.put("routeLabel", c.getString(8));
                o.put("status", c.getString(9));
                o.put("totalDistance", c.getDouble(10));
                o.put("maxSpeed", c.getDouble(11));
                o.put("avgSpeed", c.getDouble(12));
                o.put("stoppedSeconds", c.getLong(13));
                o.put("pointCount", c.getInt(14));
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        return arr.toString();
    }

    public synchronized String getTripPointsJson(long tripId) {
        JSONArray arr = new JSONArray();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT ts,lat,lon,speed,accuracy,bearing FROM trip_points WHERE trip_id=? ORDER BY ts",
                new String[]{String.valueOf(tripId)})) {
            while (c.moveToNext()) {
                JSONArray p = new JSONArray();
                p.put(c.getLong(0));
                p.put(c.getDouble(1));
                p.put(c.getDouble(2));
                p.put(c.getDouble(3));
                p.put(c.getDouble(4));
                p.put(c.getDouble(5));
                arr.put(p);
            }
        }
        return arr.toString();
    }
}
