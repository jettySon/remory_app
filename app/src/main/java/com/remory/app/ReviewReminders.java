package com.remory.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;

final class ReviewReminders {
    static final String OPEN_REVIEW = "com.remory.app.OPEN_REVIEW";
    private static final String CHECK = "com.remory.app.CHECK_REVIEW";
    private static final String CHANNEL = "review-reminders";
    private static final int NOTIFICATION_ID = 1;
    private static final int ALARM_FLAGS = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT;
    private static final long NO_CARD = Long.MAX_VALUE;
    private static final String ENDPOINT = "/api/v1/auth/review-reminder";

    static JSONObject settings(Context context) throws JSONException {
        SharedPreferences prefs = prefs(context);
        LocalTime time = LocalTime.ofSecondOfDay(prefs.getInt("minute", 19 * 60) * 60L);
        return new JSONObject().put("enabled", prefs.getBoolean("enabled", false))
                .put("time", time.toString()).put("allowed", allowed(context));
    }

    static void save(Context context, boolean enabled, LocalTime time) {
        SharedPreferences prefs = prefs(context);
        int minute = time.toSecondOfDay() / 60;
        if (prefs.getBoolean("enabled", false) == enabled && prefs.getInt("minute", 19 * 60) == minute) {
            ensureScheduled(context);
            return;
        }
        prefs.edit().putBoolean("enabled", enabled).putInt("minute", minute)
                .putLong("revision", prefs.getLong("revision", 0) + 1).apply();
        if (!enabled) dismiss(context);
        reschedule(context);
    }

    static void sync(Context context, String source, long dueAt, String locale) {
        SharedPreferences prefs = prefs(context);
        boolean sourceChanged = !source.equals(prefs.getString("source", ""));
        boolean dueChanged = dueAt != prefs.getLong("dueAt", NO_CARD);
        if (sourceChanged || dueChanged || !locale.equals(prefs.getString("locale", ""))) {
            prefs.edit().putString("source", source).putLong("dueAt", dueAt).putString("locale", locale)
                    .putLong("revision", prefs.getLong("revision", 0) + 1).apply();
            if (sourceChanged || dueAt > System.currentTimeMillis()) dismiss(context);
            if (sourceChanged || (dueChanged && "guest".equals(source))) {
                reschedule(context);
                return;
            }
        }
        ensureScheduled(context);
    }

    static boolean allowed(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled()
                && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    static void ensureScheduled(Context context) {
        if (alarm(context, PendingIntent.FLAG_NO_CREATE, 0) == null) reschedule(context);
    }

    private static void reschedule(Context context) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        PendingIntent previous = alarm(context, PendingIntent.FLAG_NO_CREATE, 0);
        if (previous != null) {
            manager.cancel(previous);
            previous.cancel();
        }
        SharedPreferences prefs = prefs(context);
        if (!prefs.getBoolean("enabled", false) || !allowed(context)) return;
        String source = prefs.getString("source", "");
        long dueAt = prefs.getLong("dueAt", NO_CARD);
        boolean guest = "guest".equals(source);
        if (guest ? dueAt == NO_CARD : !"account".equals(source)) return;
        LocalTime time = LocalTime.ofSecondOfDay(prefs.getInt("minute", 19 * 60) * 60L);
        long trigger = ReviewReminderSchedule.next(Instant.now(), ZoneId.systemDefault(), time,
                prefs.getString("lastDay", ""), guest ? dueAt : null);
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, alarm(context, 0, trigger));
    }

    private static PendingIntent alarm(Context context, int flags, long trigger) {
        Intent intent = new Intent(context, Receiver.class).setAction(CHECK).putExtra("trigger", trigger);
        return PendingIntent.getBroadcast(context, 0, intent, ALARM_FLAGS | flags);
    }

    private static boolean canNotify(Context context, SharedPreferences prefs, long trigger) {
        return prefs.getBoolean("enabled", false) && allowed(context)
                && ReviewReminderSchedule.shouldDeliver(trigger, Instant.now(), ZoneId.systemDefault(),
                        prefs.getString("lastDay", ""));
    }

    private static void notifyDue(Context context, SharedPreferences prefs) {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag(prefs.getString("locale", "ko")));
        Context local = context.createConfigurationContext(config);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, local.getString(R.string.review_reminders),
                NotificationManager.IMPORTANCE_DEFAULT));
        Intent launch = new Intent(context, MainActivity.class).setAction(OPEN_REVIEW)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_review_reminder)
                .setContentTitle(local.getString(R.string.reminder_title))
                .setContentText(local.getString(R.string.reminder_body))
                .setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .build();
        manager.notify(NOTIFICATION_ID, notification);
        prefs.edit().putString("lastDay", Instant.now().atZone(ZoneId.systemDefault()).toLocalDate().toString()).apply();
    }

    static void dismiss(Context context) {
        context.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
    }

    private static boolean accountHasDueCards() {
        HttpURLConnection connection = null;
        try {
            String endpoint = SitePolicy.origin(BuildConfig.WEB_URL) + ENDPOINT;
            String cookies = CookieManager.getInstance().getCookie(endpoint);
            if (cookies == null) return false;
            // Reuse only the WebView session cookie for a read-only, same-origin request.
            String refresh = null;
            for (String cookie : cookies.split(";")) {
                if (cookie.trim().startsWith("remory_refresh=")) {
                    refresh = cookie.trim();
                    break;
                }
            }
            if (refresh == null) return false;
            connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(3000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Cookie", refresh);
            connection.setRequestProperty("X-Remory-Client", "web");
            if (connection.getResponseCode() != 200) return false;
            try (var reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                return new JSONObject(reader.readLine()).optBoolean("due");
            }
        } catch (IOException | JSONException | RuntimeException ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("review_reminders", Context.MODE_PRIVATE);
    }

    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (!CHECK.equals(intent.getAction())) {
                reschedule(context);
                return;
            }
            long trigger = intent.getLongExtra("trigger", Long.MAX_VALUE);
            SharedPreferences prefs = prefs(context);
            if (canNotify(context, prefs, trigger)) {
                String source = prefs.getString("source", "");
                if ("guest".equals(source) && prefs.getLong("dueAt", NO_CARD) <= System.currentTimeMillis()) {
                    notifyDue(context, prefs);
                } else if ("account".equals(source)) {
                    long revision = prefs.getLong("revision", 0);
                    PendingResult pending = goAsync();
                    new Thread(() -> {
                        boolean due = accountHasDueCards();
                        new Handler(Looper.getMainLooper()).post(() -> {
                            try {
                                if (due && revision == prefs.getLong("revision", 0) && canNotify(context, prefs, trigger)) {
                                    notifyDue(context, prefs);
                                }
                            } finally {
                                pending.finish();
                            }
                        });
                    }, "remory-due-cards").start();
                }
            }
            reschedule(context);
        }
    }
}
