package com.sonelli.juicessh.performancemonitor.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * The app's Room database (metric history). Single process, so a plain
 * application-scoped singleton is enough. Schema export is off: this is a
 * sideloaded app with no migrations planned yet, and export writes a JSON file
 * that trips OneDrive file locking on this project path.
 */
@Database(entities = {MetricSampleEntity.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase instance;

    public abstract MetricSampleDao metricSampleDao();

    public static AppDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "perfmon.db")
                            .build();
                }
            }
        }
        return instance;
    }
}
