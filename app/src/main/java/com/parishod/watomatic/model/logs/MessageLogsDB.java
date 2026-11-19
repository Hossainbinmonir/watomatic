package com.parishod.watomatic.model.logs;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.parishod.watomatic.model.utils.Constants;

@Database(entities = {MessageLog.class, AppPackage.class, ReplyAttemptEntity.class, NotificationDispatchClaimEntity.class}, version = 5, exportSchema = false)
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

    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            // Preserve pre-02.3 records explicitly as LEGACY rather than falsely claiming DISPATCHED.
            db.execSQL("ALTER TABLE message_logs ADD COLUMN execution_status TEXT NOT NULL DEFAULT 'LEGACY'");
            db.execSQL("ALTER TABLE message_logs ADD COLUMN attempt_id TEXT");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_logs_attempt_id` ON `message_logs` (`attempt_id`)");
            db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reply_attempts` (" +
                            "`attempt_id` TEXT NOT NULL, " +
                            "`package_name` TEXT NOT NULL, " +
                            "`sender_name` TEXT, " +
                            "`incoming_message` TEXT, " +
                            "`notification_key` TEXT, " +
                            "`reply_text` TEXT, " +
                            "`source_type` TEXT NOT NULL, " +
                            "`source_label` TEXT, " +
                            "`status` TEXT NOT NULL, " +
                            "`dispatch_attempted` INTEGER NOT NULL, " +
                            "`dispatched_normally` INTEGER NOT NULL, " +
                            "`failure_category` TEXT, " +
                            "`created_at_ms` INTEGER NOT NULL, " +
                            "`updated_at_ms` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`attempt_id`)" +
                            ")"
            );
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_reply_attempts_updated_at_ms` ON `reply_attempts` (`updated_at_ms`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_reply_attempts_package_name_sender_name` ON `reply_attempts` (`package_name`, `sender_name`)");
        }
    };

    public static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE reply_attempts ADD COLUMN event_key TEXT");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_reply_attempts_event_key` ON `reply_attempts` (`event_key`)");
            db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notification_dispatch_claims` (" +
                            "`event_key` TEXT NOT NULL, " +
                            "`attempt_id` TEXT NOT NULL, " +
                            "`package_name` TEXT NOT NULL, " +
                            "`sender_key` TEXT NOT NULL, " +
                            "`notification_key` TEXT, " +
                            "`claim_status` TEXT NOT NULL, " +
                            "`failure_category` TEXT, " +
                            "`dispatch_attempted` INTEGER NOT NULL, " +
                            "`dispatched_normally` INTEGER NOT NULL, " +
                            "`created_at_ms` INTEGER NOT NULL, " +
                            "`updated_at_ms` INTEGER NOT NULL, " +
                            "`expires_at_ms` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`event_key`)" +
                            ")"
            );
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_dispatch_claims_attempt_id` ON `notification_dispatch_claims` (`attempt_id`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_dispatch_claims_claim_status` ON `notification_dispatch_claims` (`claim_status`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_dispatch_claims_updated_at_ms` ON `notification_dispatch_claims` (`updated_at_ms`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_dispatch_claims_expires_at_ms` ON `notification_dispatch_claims` (`expires_at_ms`)");
        }
    };

    public static synchronized MessageLogsDB getInstance(Context context) {
        if (_instance == null) {
            _instance = Room.databaseBuilder(context.getApplicationContext(), MessageLogsDB.class, DB_NAME)
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .allowMainThreadQueries()
                    .build();
        }
        return _instance;
    }

    @VisibleForTesting
    public static synchronized void resetInstance() {
        if (_instance != null) {
            _instance.close();
            _instance = null;
        }
    }

    public abstract MessageLogsDao logsDao();

    public abstract AppPackageDao appPackageDao();

    public abstract ReplyAttemptDao replyAttemptDao();

    public abstract NotificationDispatchClaimDao notificationDispatchClaimDao();
}
