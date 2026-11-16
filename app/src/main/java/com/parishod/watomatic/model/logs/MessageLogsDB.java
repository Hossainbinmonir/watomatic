package com.parishod.watomatic.model.logs;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.parishod.watomatic.model.utils.Constants;

@Database(entities = {MessageLog.class, AppPackage.class}, version = 3, exportSchema = false)
public abstract class MessageLogsDB extends RoomDatabase {
    private static final String DB_NAME = Constants.LOGS_DB_NAME;
    private static MessageLogsDB _instance;

    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE message_logs ADD COLUMN notif_incoming_msg TEXT");
            db.execSQL("ALTER TABLE message_logs ADD COLUMN matched_rule_name TEXT");
        }
    };

    public static synchronized MessageLogsDB getInstance(Context context) {
        if (_instance == null) {
            _instance = Room.databaseBuilder(context.getApplicationContext(), MessageLogsDB.class, DB_NAME)
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .allowMainThreadQueries()
                    .build();
        }
        return _instance;
    }

    public abstract MessageLogsDao logsDao();

    public abstract AppPackageDao appPackageDao();
}
