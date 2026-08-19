package com.khaled.backupapp;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Telephony;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {

    // ── colors (dark + amber, matches the rest of khaled's apps) ──
    static final int BG     = 0xFF0A0A0F;
    static final int CARD   = 0xFF13131A;
    static final int ACCENT = 0xFFF59E0B; // amber
    static final int TEXT   = 0xFFFFFFFF;
    static final int SUB    = 0xFF8B8B96;
    static final int OK     = 0xFF34D399;
    static final int ERR    = 0xFFEF4444;
    static final int DIVIDER= 0xFF1F1F2E;

    // Point this at wherever backupapp-serve is running.
    static final String SERVER_URL = "http://72.60.89.132:4425";

    static final int REQ_PERMS = 100;
    static final int REQ_PICK_FOLDER = 101;
    static final int REQ_ALL_FILES_ACCESS = 102;
    static final int REQ_INSTALL_UNKNOWN_SOURCES = 103;

    static final String LOCAL_BACKUP_DIRNAME = "BackupApp";

    // Top-level folders under external storage that are either protected
    // (Android/data, Android/obb — unreadable even with All-Files-Access
    // on API 30+), already covered by the MediaStore photos/videos backup,
    // or (BackupApp) this app's own local-mode output — walking into that
    // one while writing into it would recurse forever, copying its own
    // output back into itself.
    static final java.util.Set<String> SKIP_TOP_LEVEL = new java.util.HashSet<>(java.util.Arrays.asList(
            "Android", "DCIM", "Pictures", "Movies", LOCAL_BACKUP_DIRNAME));

    TextView logView;
    ScrollView logScroll;
    TextView statusView;
    TextView destLabel;
    Button destServerBtn, destUsbBtn;
    final List<Button> actionButtons = new ArrayList<>();
    SharedPreferences prefs;
    ExecutorService uploader = Executors.newSingleThreadExecutor();
    String deviceId;
    // Where a backup goes: WiFi upload to SERVER_URL, or written straight onto
    // this device's shared storage so it shows up in a plain file browser the
    // moment the phone is plugged into a PC over USB (MTP) - no server, no
    // Wi-Fi, no account. Toggle persists across launches.
    boolean localMode;
    // Counts backup jobs queued-or-running on `uploader`. Every button funnels
    // through runBackupTask() so tapping any of them while another is in
    // flight is a no-op (buttons disabled) instead of silently queuing an
    // overlapping duplicate job - single-thread executor makes them safe to
    // run back-to-back, but not useful to run at the same time by accident.
    final AtomicInteger inFlight = new AtomicInteger(0);
    File pendingApk;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("backupapp", MODE_PRIVATE);
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        if (deviceId == null) deviceId = "unknown-device";
        localMode = prefs.getBoolean("local_mode", false);

        buildUi();
        requestPerms();
        if (BuildConfig.BACKUP_KEY.isEmpty()) {
            log("[WARN] No BACKUP_KEY built into this APK - set BACKUP_KEY in local.properties " +
                    "and rebuild, or an authenticated server will reject every upload with 401.");
        }
        Executors.newSingleThreadExecutor().execute(this::checkForUpdate);
    }

    // ── UI ──────────────────────────────────────────────────────
    android.graphics.drawable.GradientDrawable pill(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    android.graphics.drawable.GradientDrawable outlinePill(int strokeColor, int radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(0x00000000);
        d.setStroke(dp(1), strokeColor);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    void buildUi() {
        ScrollView pageScroll = new ScrollView(this);
        pageScroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(40), pad, pad);
        pageScroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView titleIcon = new TextView(this);
        titleIcon.setText("💾");
        titleIcon.setTextSize(28);
        titleIcon.setPadding(0, 0, dp(8), 0);
        titleRow.addView(titleIcon);
        TextView title = new TextView(this);
        title.setText("Backup App");
        title.setTextColor(TEXT);
        title.setTextSize(26);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        titleRow.addView(title);
        root.addView(titleRow);

        TextView subtitle = new TextView(this);
        subtitle.setText("Full device backup — نسخ احتياطي كامل للجهاز");
        subtitle.setTextColor(SUB);
        subtitle.setTextSize(13);
        subtitle.setPadding(0, dp(4), 0, dp(20));
        root.addView(subtitle);

        // ── destination toggle: WiFi server vs. this device (USB → PC) ──
        TextView destTitle = new TextView(this);
        destTitle.setText("SAVE TO  ·  احفظ في");
        destTitle.setTextColor(SUB);
        destTitle.setTextSize(11);
        destTitle.setTypeface(destTitle.getTypeface(), android.graphics.Typeface.BOLD);
        destTitle.setPadding(dp(2), 0, 0, dp(8));
        root.addView(destTitle);

        LinearLayout destCard = new LinearLayout(this);
        destCard.setOrientation(LinearLayout.VERTICAL);
        destCard.setBackground(pill(CARD, 14));
        destCard.setPadding(dp(10), dp(10), dp(10), dp(10));

        LinearLayout destRow = new LinearLayout(this);
        destRow.setOrientation(LinearLayout.HORIZONTAL);
        destServerBtn = new Button(this);
        destServerBtn.setText("☁ Server (WiFi)");
        destServerBtn.setAllCaps(false);
        destServerBtn.setTextSize(13);
        destServerBtn.setPadding(dp(8), dp(10), dp(8), dp(10));
        destServerBtn.setOnClickListener(v -> setLocalMode(false));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp1.rightMargin = dp(6);
        destServerBtn.setLayoutParams(lp1);
        destRow.addView(destServerBtn);

        destUsbBtn = new Button(this);
        destUsbBtn.setText("🔌 This Device (USB → PC)");
        destUsbBtn.setAllCaps(false);
        destUsbBtn.setTextSize(13);
        destUsbBtn.setPadding(dp(8), dp(10), dp(8), dp(10));
        destUsbBtn.setOnClickListener(v -> setLocalMode(true));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        destUsbBtn.setLayoutParams(lp2);
        destRow.addView(destUsbBtn);
        destCard.addView(destRow);

        destLabel = new TextView(this);
        destLabel.setTextColor(SUB);
        destLabel.setTextSize(11);
        destLabel.setPadding(dp(2), dp(8), dp(2), 0);
        destCard.addView(destLabel);
        root.addView(destCard);
        updateDestUi();

        statusView = new TextView(this);
        statusView.setTextColor(SUB);
        statusView.setTextSize(12);
        statusView.setBackground(pill(CARD, 14));
        statusView.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(12);
        statusLp.bottomMargin = dp(20);
        statusView.setLayoutParams(statusLp);
        refreshStatus();
        root.addView(statusView);

        // Primary call-to-action, full width, unmissable.
        addPrimaryButton(root, "⚡ Backup Everything  ·  انسخ كل شي", v -> backupEverything());

        // Everything else in a compact 2-column grid — icons carry most of
        // the scanning, EN+AR only where it fits.
        addButtonGrid(root, new Object[][]{
                {"🖼️ Photos && Videos", (View.OnClickListener) v -> runBackupTask(this::backupMedia)},
                {"👤 Contacts", (View.OnClickListener) v -> runBackupTask(this::backupContacts)},
                {"💬 SMS", (View.OnClickListener) v -> runBackupTask(this::backupSms)},
                {"📱 Installed Apps", (View.OnClickListener) v -> runBackupTask(this::backupApps)},
                {"📁 All Device Folders", (View.OnClickListener) v -> backupAllFiles()},
        });

        addGhostButton(root, "Pick one folder (fallback) / اختر مجلد", v -> pickFolder());

        TextView logLabel = new TextView(this);
        logLabel.setText("LOG  ·  السجل");
        logLabel.setTextColor(SUB);
        logLabel.setTextSize(11);
        logLabel.setTypeface(logLabel.getTypeface(), android.graphics.Typeface.BOLD);
        logLabel.setPadding(dp(2), dp(20), 0, dp(8));
        root.addView(logLabel);

        logView = new TextView(this);
        logView.setTextColor(TEXT);
        logView.setTextSize(12);
        logView.setTextIsSelectable(true);
        logView.setText("", TextView.BufferType.SPANNABLE);

        logScroll = new ScrollView(this);
        logScroll.setBackground(pill(CARD, 14));
        logScroll.setPadding(dp(12), dp(12), dp(12), dp(12));
        logScroll.addView(logView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220));
        root.addView(logScroll, lp);

        setContentView(pageScroll);
    }

    void addPrimaryButton(LinearLayout root, String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(BG);
        b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        b.setTextSize(15);
        b.setBackground(pill(ACCENT, 14));
        b.setPadding(dp(16), dp(16), dp(16), dp(16));
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        b.setLayoutParams(lp);
        actionButtons.add(b);
        root.addView(b);
    }

    // Two-per-row grid of secondary actions — compact, icon-led.
    void addButtonGrid(LinearLayout root, Object[][] items) {
        for (int i = 0; i < items.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = dp(8);
            row.setLayoutParams(rowLp);
            row.addView(gridButton((String) items[i][0], (View.OnClickListener) items[i][1], i + 1 < items.length));
            if (i + 1 < items.length) {
                row.addView(gridButton((String) items[i + 1][0], (View.OnClickListener) items[i + 1][1], false));
            }
            root.addView(row);
        }
    }

    Button gridButton(String label, View.OnClickListener onClick, boolean addRightMargin) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(13);
        b.setBackground(pill(CARD, 12));
        b.setPadding(dp(10), dp(14), dp(10), dp(14));
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        if (addRightMargin) lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        actionButtons.add(b);
        return b;
    }

    // Low-emphasis outline button for the "fallback" action — present, not competing.
    void addGhostButton(LinearLayout root, String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(SUB);
        b.setTextSize(12);
        b.setBackground(outlinePill(DIVIDER, 12));
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        actionButtons.add(b);
        root.addView(b);
    }

    void setButtonsEnabled(boolean enabled) {
        for (Button b : actionButtons) b.setEnabled(enabled);
    }

    void setLocalMode(boolean local) {
        localMode = local;
        prefs.edit().putBoolean("local_mode", local).apply();
        updateDestUi();
        toast(local
                ? "Backups now save to this phone's storage — plug in USB to copy them to your PC. / بتنحفظ بجهازك، وصّل USB وانسخها للكمبيوتر"
                : "Backups now upload over WiFi to the server. / بترفع عبر الواي فاي للسيرفر");
    }

    void updateDestUi() {
        if (destServerBtn == null) return; // called before buildUi finishes
        destServerBtn.setBackgroundColor(localMode ? CARD : ACCENT);
        destServerBtn.setTextColor(localMode ? SUB : BG);
        destUsbBtn.setBackgroundColor(localMode ? ACCENT : CARD);
        destUsbBtn.setTextColor(localMode ? BG : SUB);
        destLabel.setText(localMode
                ? "Files land in: " + localBackupRoot().getPath() + "\nPlug in a USB cable and open that folder on your PC (no WiFi needed). / وصّل كيبل USB وافتح هذا المجلد من الكمبيوتر"
                : "Uploads to " + SERVER_URL + " over WiFi. / يرفع عبر الواي فاي للسيرفر");
    }

    // Runs `task` on the single-thread uploader executor, disabling every
    // action button until every currently queued-or-running task (this one
    // and any already ahead of it) has finished.
    void runBackupTask(Runnable task) {
        if (inFlight.getAndIncrement() == 0) {
            runOnUiThread(() -> setButtonsEnabled(false));
        }
        uploader.execute(() -> {
            try {
                task.run();
            } finally {
                if (inFlight.decrementAndGet() == 0) {
                    runOnUiThread(() -> setButtonsEnabled(true));
                }
            }
        });
    }

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    void refreshStatus() {
        long photos = prefs.getLong("last_media", 0);
        long contacts = prefs.getLong("last_contacts", 0);
        long sms = prefs.getLong("last_sms", 0);
        long files = prefs.getLong("last_files", 0);
        long apps = prefs.getLong("last_apps", 0);
        statusView.setText(
                "Last backup — media: " + fmt(photos) +
                "  |  contacts: " + fmt(contacts) +
                "  |  sms: " + fmt(sms) +
                "  |  files: " + fmt(files) +
                "  |  apps: " + fmt(apps));
    }

    String fmt(long t) {
        if (t == 0) return "never";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(t));
    }

    void log(String s) {
        int color = logColor(s);
        android.text.SpannableString line = new android.text.SpannableString(s + "\n");
        if (color != TEXT) {
            line.setSpan(new android.text.style.ForegroundColorSpan(color), 0, s.length(), 0);
        }
        runOnUiThread(() -> {
            logView.append(line);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    int logColor(String s) {
        String low = s.toLowerCase(Locale.US);
        if (low.contains("failed") || low.contains("[warn]") || low.contains("not granted")) return ERR;
        if (low.contains(" ok") || low.contains("uploaded") || low.contains("=ok")
                || low.contains("up to date") || low.contains("granted")) return OK;
        return TEXT;
    }

    void toast(String s) {
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    void backupEverything() {
        if (hasAllFilesAccess()) {
            runBackupTask(() -> {
                backupContacts();
                backupSms();
                backupMedia();
                backupApps();
                backupAllFilesNow();
            });
        } else {
            // No all-files permission yet: run everything else now, and kick
            // off the permission request - the user taps "All Device Folders"
            // (or "Backup Everything" again) once it's granted.
            runBackupTask(() -> {
                backupContacts();
                backupSms();
                backupMedia();
                backupApps();
            });
            backupAllFiles();
        }
    }

    // ── auto-update ────────────────────────────────────────────
    void checkForUpdate() {
        try {
            long current = getPackageManager().getPackageInfo(getPackageName(), 0)
                    .getLongVersionCode();
            URL url = new URL(SERVER_URL + "/api/version");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            StringBuilder body = new StringBuilder();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) body.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            } finally {
                conn.disconnect();
            }
            JSONObject v = new JSONObject(body.toString());
            long latest = v.getLong("versionCode");
            String apkUrl = v.getString("apkUrl");
            String versionName = v.optString("versionName", "");
            if (latest > current) {
                log("Update: v" + versionName + " available, downloading...");
                downloadAndInstallUpdate(apkUrl, versionName);
            } else {
                log("Update: up to date (v" + current + ")");
            }
        } catch (Exception e) {
            Log.w("BackupApp", "update check failed", e);
        }
    }

    void downloadAndInstallUpdate(String apkUrl, String versionName) {
        HttpURLConnection conn = null;
        try {
            File dir = new File(getExternalFilesDir(null), "updates");
            dir.mkdirs();
            File apk = new File(dir, "update.apk");

            URL url = new URL(SERVER_URL + apkUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(apk)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }

            log("Update: v" + versionName + " downloaded, opening installer...");
            pendingApk = apk;
            runOnUiThread(() -> installApk(apk));
        } catch (Exception e) {
            Log.w("BackupApp", "update download failed", e);
            log("Update: download failed");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    void installApk(File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            toast("Allow \"Install unknown apps\" for Backup App to auto-update.");
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(settings, REQ_INSTALL_UNKNOWN_SOURCES);
            return;
        }
        Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    // ── permissions ─────────────────────────────────────────────
    void requestPerms() {
        List<String> need = new ArrayList<>();
        need.add(Manifest.permission.READ_CONTACTS);
        need.add(Manifest.permission.READ_SMS);
        if (Build.VERSION.SDK_INT >= 33) {
            need.add(Manifest.permission.READ_MEDIA_IMAGES);
            need.add(Manifest.permission.READ_MEDIA_VIDEO);
        } else {
            need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        List<String> missing = new ArrayList<>();
        for (String p : need) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_PERMS) {
            log("Permissions: " + describePerms());
        }
    }

    String describePerms() {
        StringBuilder sb = new StringBuilder();
        String[] all = Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.READ_CONTACTS, Manifest.permission.READ_SMS,
                               Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO}
                : new String[]{Manifest.permission.READ_CONTACTS, Manifest.permission.READ_SMS,
                               Manifest.permission.READ_EXTERNAL_STORAGE};
        for (String p : all) {
            boolean granted = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
            sb.append(p.substring(p.lastIndexOf('.') + 1)).append(granted ? "=ok " : "=denied ");
        }
        return sb.toString();
    }

    // ── contacts ────────────────────────────────────────────────
    void backupContacts() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            log("Contacts: permission not granted, skipped.");
            return;
        }
        log("Contacts: reading...");
        StringBuilder json = new StringBuilder("[\n");
        Cursor c = getContentResolver().query(ContactsContract.Contacts.CONTENT_URI,
                null, null, null, null);
        int count = 0;
        if (c != null) {
            int idIdx = c.getColumnIndex(ContactsContract.Contacts._ID);
            int nameIdx = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME);
            while (c.moveToNext()) {
                String id = c.getString(idIdx);
                String name = c.getString(nameIdx);
                List<String> phones = new ArrayList<>();
                Cursor pc = getContentResolver().query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null,
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?",
                        new String[]{id}, null);
                if (pc != null) {
                    int numIdx = pc.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                    while (pc.moveToNext()) phones.add(pc.getString(numIdx));
                    pc.close();
                }
                if (count > 0) json.append(",\n");
                json.append("  {\"name\":").append(jsonStr(name))
                        .append(",\"phones\":").append(jsonArr(phones)).append("}");
                count++;
            }
            c.close();
        }
        json.append("\n]\n");
        boolean ok = storeBytes("contacts", "contacts.json",
                json.toString().getBytes(StandardCharsets.UTF_8), "application/json");
        if (ok) {
            prefs.edit().putLong("last_contacts", System.currentTimeMillis()).apply();
            runOnUiThread(this::refreshStatus);
        }
        log("Contacts: " + count + " uploaded " + (ok ? "ok" : "FAILED"));
    }

    // ── sms ─────────────────────────────────────────────────────
    void backupSms() {
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            log("SMS: permission not granted, skipped.");
            return;
        }
        log("SMS: reading...");
        StringBuilder json = new StringBuilder("[\n");
        int count = 0;
        Cursor c = getContentResolver().query(Telephony.Sms.CONTENT_URI,
                new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.BODY},
                null, null, Telephony.Sms.DATE + " DESC");
        if (c != null) {
            while (c.moveToNext()) {
                String addr = c.getString(0);
                long date = c.getLong(1);
                int type = c.getInt(2);
                String body = c.getString(3);
                if (count > 0) json.append(",\n");
                json.append("  {\"address\":").append(jsonStr(addr))
                        .append(",\"date\":").append(date)
                        .append(",\"type\":").append(type)
                        .append(",\"body\":").append(jsonStr(body)).append("}");
                count++;
            }
            c.close();
        }
        json.append("\n]\n");
        boolean ok = storeBytes("sms", "sms.json",
                json.toString().getBytes(StandardCharsets.UTF_8), "application/json");
        if (ok) {
            prefs.edit().putLong("last_sms", System.currentTimeMillis()).apply();
            runOnUiThread(this::refreshStatus);
        }
        log("SMS: " + count + " uploaded " + (ok ? "ok" : "FAILED"));
    }

    // ── photos & videos ─────────────────────────────────────────
    void backupMedia() {
        boolean granted = Build.VERSION.SDK_INT >= 33
                ? checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                : checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            log("Media: permission not granted, skipped.");
            return;
        }
        int okCount = 0, failCount = 0;
        okCount += uploadCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "photos");
        okCount += uploadCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "videos");
        prefs.edit().putLong("last_media", System.currentTimeMillis()).apply();
        runOnUiThread(this::refreshStatus);
        log("Media: done (" + okCount + " files uploaded)");
    }

    int uploadCollection(Uri collection, String category) {
        log(category + ": scanning...");
        int ok = 0, total = 0;
        Cursor c = getContentResolver().query(collection,
                new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
        if (c != null) {
            int idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID);
            int nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            total = c.getCount();
            int i = 0;
            while (c.moveToNext()) {
                i++;
                long id = c.getLong(idIdx);
                String name = c.getString(nameIdx);
                Uri fileUri = Uri.withAppendedPath(collection, String.valueOf(id));
                try (InputStream in = getContentResolver().openInputStream(fileUri)) {
                    if (in == null) continue;
                    boolean up = storeStream(category, name, in, "application/octet-stream");
                    if (up) ok++;
                    if (i % 10 == 0) log(category + ": " + i + "/" + total);
                } catch (Exception e) {
                    Log.w("BackupApp", "media upload failed: " + name, e);
                }
            }
            c.close();
        }
        log(category + ": " + ok + "/" + total + " uploaded");
        return ok;
    }

    // ── arbitrary folder (SAF) ─────────────────────────────────
    void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, REQ_PICK_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_FOLDER && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            runBackupTask(() -> backupFolder(treeUri));
        } else if (requestCode == REQ_ALL_FILES_ACCESS) {
            log("All files access: " + (hasAllFilesAccess() ? "granted, tap \"All Device Folders\" again" : "still not granted"));
        } else if (requestCode == REQ_INSTALL_UNKNOWN_SOURCES) {
            if (pendingApk != null && getPackageManager().canRequestPackageInstalls()) {
                installApk(pendingApk);
            } else {
                log("Update: install permission not granted.");
            }
        }
    }

    void backupFolder(Uri treeUri) {
        DocumentFile root = DocumentFile.fromTreeUri(this, treeUri);
        if (root == null) {
            log("Files: could not open folder.");
            return;
        }
        log("Files: scanning " + root.getName() + "...");
        int[] counters = {0, 0};
        walkAndUpload(root, "", counters);
        prefs.edit().putLong("last_files", System.currentTimeMillis()).apply();
        runOnUiThread(this::refreshStatus);
        log("Files: " + counters[0] + "/" + counters[1] + " uploaded");
    }

    // ── entire device storage (all folders) ─────────────────────
    boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    void backupAllFiles() {
        if (!hasAllFilesAccess()) {
            if (Build.VERSION.SDK_INT >= 30) {
                toast("Grant \"All files access\" for Backup App, then tap this button again.");
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivityForResult(i, REQ_ALL_FILES_ACCESS);
                } catch (Exception e) {
                    startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                            REQ_ALL_FILES_ACCESS);
                }
            } else {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERMS);
            }
            return;
        }
        runBackupTask(this::backupAllFilesNow);
    }

    void backupAllFilesNow() {
        File root = Environment.getExternalStorageDirectory();
        log("All Files: scanning " + root + " ...");
        int[] counters = {0, 0};
        File[] top = root.listFiles();
        if (top != null) {
            for (File f : top) {
                if (f.isDirectory() && SKIP_TOP_LEVEL.contains(f.getName())) continue;
                walkAndUploadFile(f, f.getName(), counters);
            }
        }
        prefs.edit().putLong("last_files", System.currentTimeMillis()).apply();
        runOnUiThread(this::refreshStatus);
        log("All Files: " + counters[0] + "/" + counters[1] + " uploaded");
    }

    void walkAndUploadFile(File f, String rel, int[] counters) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children == null) return;
            for (File c : children) walkAndUploadFile(c, rel + "/" + c.getName(), counters);
        } else {
            counters[1]++;
            try (InputStream in = new FileInputStream(f)) {
                boolean ok = storeStream("files", rel, in, "application/octet-stream");
                if (ok) counters[0]++;
                if (counters[1] % 20 == 0) log("files: " + counters[0] + "/" + counters[1]);
            } catch (Exception e) {
                Log.w("BackupApp", "file upload failed: " + rel, e);
            }
        }
    }

    // ── installed apps (metadata + best-effort APK copy) ───────
    void backupApps() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        StringBuilder json = new StringBuilder("[\n");
        int count = 0, apkOk = 0;
        log("Apps: found " + apps.size() + " installed apps, reading...");
        for (ApplicationInfo ai : apps) {
            boolean isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            String versionName = "";
            long versionCode = 0;
            try {
                PackageInfo pi = pm.getPackageInfo(ai.packageName, 0);
                versionName = pi.versionName != null ? pi.versionName : "";
                versionCode = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            } catch (Exception ignored) {}
            String label = String.valueOf(pm.getApplicationLabel(ai));

            if (count > 0) json.append(",\n");
            json.append("  {\"package\":").append(jsonStr(ai.packageName))
                    .append(",\"name\":").append(jsonStr(label))
                    .append(",\"versionName\":").append(jsonStr(versionName))
                    .append(",\"versionCode\":").append(versionCode)
                    .append(",\"system\":").append(isSystem).append("}");
            count++;

            // Best-effort: back up the installed APK for user (non-system) apps.
            if (!isSystem && ai.sourceDir != null) {
                try (InputStream in = new FileInputStream(ai.sourceDir)) {
                    boolean ok = storeStream("apps", ai.packageName + ".apk", in, "application/vnd.android.package-archive");
                    if (ok) apkOk++;
                } catch (Exception e) {
                    Log.w("BackupApp", "apk backup skipped: " + ai.packageName, e);
                }
            }
        }
        json.append("\n]\n");
        boolean ok = storeBytes("apps", "apps.json",
                json.toString().getBytes(StandardCharsets.UTF_8), "application/json");
        if (ok) {
            prefs.edit().putLong("last_apps", System.currentTimeMillis()).apply();
            runOnUiThread(this::refreshStatus);
        }
        log("Apps: " + count + " listed, " + apkOk + " APKs uploaded, list " + (ok ? "ok" : "FAILED"));
    }

    void walkAndUpload(DocumentFile dir, String relPrefix, int[] counters) {
        DocumentFile[] children = dir.listFiles();
        for (DocumentFile f : children) {
            // Same self-recursion guard as backupAllFilesNow(): if the user
            // picked a folder that happens to contain this app's own
            // local-mode output (e.g. the whole sdcard root), don't copy it
            // back into itself in local mode.
            if (relPrefix.isEmpty() && localMode && LOCAL_BACKUP_DIRNAME.equals(f.getName())) continue;
            String rel = relPrefix.isEmpty() ? f.getName() : relPrefix + "/" + f.getName();
            if (f.isDirectory()) {
                walkAndUpload(f, rel, counters);
            } else {
                counters[1]++;
                try (InputStream in = getContentResolver().openInputStream(f.getUri())) {
                    if (in == null) continue;
                    boolean ok = storeStream("files", rel, in,
                            f.getType() != null ? f.getType() : "application/octet-stream");
                    if (ok) counters[0]++;
                    if (counters[1] % 10 == 0) log("files: " + counters[0] + "/" + counters[1]);
                } catch (Exception e) {
                    Log.w("BackupApp", "file upload failed: " + rel, e);
                }
            }
        }
    }

    // ── store (routes to WiFi upload or local-device write) ────
    File localBackupRoot() {
        return new File(Environment.getExternalStorageDirectory(), LOCAL_BACKUP_DIRNAME);
    }

    boolean storeBytes(String category, String relPath, byte[] bytes, String contentType) {
        try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(bytes)) {
            return storeStream(category, relPath, in, contentType);
        } catch (Exception e) {
            return false;
        }
    }

    boolean storeStream(String category, String relPath, InputStream in, String contentType) {
        return localMode ? saveLocal(category, relPath, in) : uploadStream(category, relPath, in, contentType);
    }

    // Writes straight onto this device's own shared storage under
    // /storage/emulated/0/BackupApp/<category>/<relPath> - MTP already
    // exposes that path the instant a USB cable is plugged in, so this needs
    // no server, no network, and works with the phone fully offline.
    boolean saveLocal(String category, String relPath, InputStream in) {
        try {
            File dest = safeLocalFile(category, relPath);
            if (dest == null) return false;
            File parent = dest.getParentFile();
            if (parent != null) parent.mkdirs();
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            return true;
        } catch (Exception e) {
            Log.w("BackupApp", "local save failed: " + category + "/" + relPath, e);
            return false;
        }
    }

    // relPath ultimately comes from real filenames (MediaStore/DocumentFile/
    // package names) rather than untrusted network input, but a crafted or
    // unusual name (e.g. containing "..") could still resolve outside the
    // backup root - resolve and verify containment before ever writing.
    File safeLocalFile(String category, String relPath) {
        File root = localBackupRoot();
        File dest = new File(new File(root, category), relPath);
        try {
            String rootPath = root.getCanonicalPath() + File.separator;
            String destPath = dest.getCanonicalPath();
            if (!(destPath + File.separator).startsWith(rootPath) && !destPath.equals(root.getCanonicalPath())) {
                Log.w("BackupApp", "rejected path escaping backup root: " + relPath);
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        return dest;
    }

    // ── network upload (multipart/form-data, no external deps) ─
    static final String BOUNDARY = "----BackupAppBoundary7a1c9";

    boolean uploadBytes(String category, String relPath, byte[] bytes, String contentType) {
        try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(bytes)) {
            return uploadStream(category, relPath, in, contentType);
        } catch (Exception e) {
            return false;
        }
    }

    boolean uploadStream(String category, String relPath, InputStream in, String contentType) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(SERVER_URL + "/api/upload");
            conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setChunkedStreamingMode(64 * 1024);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
            conn.setRequestProperty("X-Backup-Key", BuildConfig.BACKUP_KEY);

            try (OutputStream raw = conn.getOutputStream();
                 BufferedOutputStream out = new BufferedOutputStream(raw)) {
                writeField(out, "device", deviceId);
                writeField(out, "category", category);
                writeField(out, "relpath", relPath);

                out.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
                        + relPath.replace("\"", "'") + "\"\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            Log.w("BackupApp", "upload failed: " + relPath, e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    void writeField(OutputStream out, String name, String value) throws Exception {
        out.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write((value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    // ── tiny json helpers (no external deps) ───────────────────
    String jsonStr(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        return sb.append("\"").toString();
    }

    String jsonArr(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(jsonStr(items.get(i)));
        }
        return sb.append("]").toString();
    }
}
