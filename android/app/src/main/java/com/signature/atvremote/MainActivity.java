package com.signature.atvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.speech.RecognizerIntent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.signature.atvremote.protocol.CompanionClient;
import com.signature.atvremote.protocol.HapSrp;
import com.signature.atvremote.protocol.ProvenanceClient;
import com.signature.atvremote.ui.TouchpadView;
import com.signature.atvremote.ui.WiiPadView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Apple TV System Remote & Provenance Wii Controller for Motorola Signature.
 * Designed with native iOS / tvOS Control Center Remote typography & surfaces,
 * real-time live app/game discovery from the Apple TV, and strict IP privacy.
 */
public final class MainActivity extends Activity implements SensorEventListener {

    private static final String PREFS_NAME = "atv_signature_prefs";
    private static final String KEY_HOST = "atv_host";
    private static final String KEY_PORT = "atv_port";
    private static final String KEY_NAME = "atv_name";
    private static final String KEY_CREDS = "atv_companion_creds";

    private static final String DEFAULT_HOST = "192.168.1.10";
    private static final int DEFAULT_PORT = 49153;
    private static final String DISPLAY_TITLE = "Sala";
    private static final int REQ_VOICE_SIRI = 1001;

    private static final String PROVENANCE_BUNDLE_ID = "org.provenance-emu.provenance";
    private static final String MARIO_KART_BUNDLE_ID = "com.example.wii.mariokart";
    private static final String WWE12_BUNDLE_ID = "com.example.wii.wwe12";

    private Typeface tfRegular;
    private Typeface tfMedium;
    private Typeface tfBold;

    private CompanionClient companionClient;
    private SharedPreferencesWrapper prefs;

    private TextView statusDotView;
    private TextView statusTitleView;
    private TextView statusSubView;

    private LinearLayout tabRemoteContent;
    private LinearLayout tabWiiContent;
    private ScrollView tabAppsContent;
    private Button tabBtnRemote;
    private Button tabBtnWii;
    private Button tabBtnApps;

    private TouchpadView touchpadView;
    private WiiPadView wiiPadView;
    private LinearLayout wiiLiveGamesStrip;
    private LinearLayout gamesListContainer;
    private LinearLayout appsListContainer;
    private TextView gamesCountHeader;
    private TextView appsCountHeader;
    private TextView liveSyncBadgeText;
    private TextView provenanceStatusText;
    private AlertDialog activePinDialog;

    private final Map<String, String> lastLiveApps = new LinkedHashMap<>();
    private final List<String> lastLiveRoms = new ArrayList<>();
    private final Handler liveSyncHandler = new Handler(Looper.getMainLooper());
    private final Runnable liveSyncRunnable = new Runnable() {
        @Override
        public void run() {
            pollLiveInstalledAppsAndGames();
            liveSyncHandler.postDelayed(this, 3500L);
        }
    };

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private boolean tiltModeEnabled = false;
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;

    private static final class SharedPreferencesWrapper {
        final SharedPreferences sp;
        SharedPreferencesWrapper(SharedPreferences sp) {
            this.sp = sp;
        }
        String getHost() { return sp.getString(KEY_HOST, DEFAULT_HOST); }
        int getPort() { return sp.getInt(KEY_PORT, DEFAULT_PORT); }
        String getCreds() { return sp.getString(KEY_CREDS, ""); }
        void saveTarget(String name, String host, int port) {
            sp.edit().putString(KEY_NAME, name).putString(KEY_HOST, host).putInt(KEY_PORT, port).apply();
        }
        void saveCreds(String creds) {
            sp.edit().putString(KEY_CREDS, creds).apply();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        enableHighRefreshRateAndDarkBars();

        tfRegular = Typeface.create("sans-serif", Typeface.NORMAL);
        tfMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        tfBold = Typeface.create("sans-serif", Typeface.BOLD);

        prefs = new SharedPreferencesWrapper(getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
        if (prefs.sp.getString(KEY_HOST, null) == null) {
            prefs.saveTarget(DISPLAY_TITLE, DEFAULT_HOST, DEFAULT_PORT);
        }
        if (prefs.getCreds().isEmpty()) {
            String externalCreds = loadCredsFromLocalFile();
            if (!externalCreds.isEmpty()) {
                prefs.saveCreds(externalCreds);
            }
        }

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }

        companionClient = new CompanionClient(new CompanionClient.Listener() {
            @Override
            public void onStateChanged(boolean connected, String statusText, long rttMs) {
                String cleanStatus = sanitizeNoIp(statusText);
                runOnUiThread(() -> {
                    updateConnectionBanner(connected, cleanStatus, rttMs);
                    if (connected) {
                        pollLiveInstalledAppsAndGames();
                    }
                });
            }

            @Override
            public void onEventReceived(String eventName, Map<String, Object> content) {
                runOnUiThread(() -> pollLiveInstalledAppsAndGames());
            }

            @Override
            public void onError(String message) {
                String cleanMsg = sanitizeNoIp(message);
                runOnUiThread(() -> updateConnectionBanner(companionClient.isConnected(), cleanMsg, -1));
            }
        });

        setContentView(buildRootLayout());
        int initialTab = getIntent() != null ? getIntent().getIntExtra("tab", 0) : 0;
        selectTab(initialTab);
        startMdnsDiscovery();
        if (getIntent() == null || !getIntent().getBooleanExtra("skip_auto_pair", false)) {
            connectToAppleTv();
        }
    }

    private static String sanitizeNoIp(String text) {
        if (text == null) return "";
        return text
                .replaceAll("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b", "Sala")
                .replaceAll(":4915\\d", "");
    }

    private void enableHighRefreshRateAndDarkBars() {
        Window window = getWindow();
        if (window == null) return;
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.setStatusBarColor(0xFF0A0A0C);
        window.setNavigationBarColor(0xFF0A0A0C);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Display display = getDisplay();
            if (display != null) {
                Display.Mode[] modes = display.getSupportedModes();
                Display.Mode bestMode = null;
                for (Display.Mode m : modes) {
                    if (bestMode == null || m.getRefreshRate() > bestMode.getRefreshRate()) {
                        bestMode = m;
                    }
                }
                if (bestMode != null) {
                    WindowManager.LayoutParams params = window.getAttributes();
                    params.preferredDisplayModeId = bestMode.getModeId();
                    window.setAttributes(params);
                }
            }
        }
    }

    private View buildRootLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0A0A0C);
        root.setPadding(dp(18), dp(48), dp(18), dp(24));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(
                        dp(18) + bars.left,
                        Math.max(dp(44), bars.top + dp(8)),
                        dp(18) + bars.right,
                        Math.max(dp(18), bars.bottom + dp(8))
                );
                return insets;
            });
        }

        root.addView(buildSystemTopBar());
        root.addView(buildSegmentedTabs());

        FrameLayout contentFrame = new FrameLayout(this);
        LinearLayout.LayoutParams frameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        frameLp.topMargin = dp(10);
        contentFrame.setLayoutParams(frameLp);

        tabRemoteContent = buildRemoteTab();
        tabWiiContent = buildWiiTab();
        tabAppsContent = buildAppsTab();

        contentFrame.addView(tabRemoteContent);
        contentFrame.addView(tabWiiContent);
        contentFrame.addView(tabAppsContent);

        root.addView(contentFrame);
        return root;
    }

    /**
     * Minimalist iOS Apple TV Remote Top Bar:
     * Left: Circular Keyboard button
     * Center: Device Pill ("Sala" + live status dot & subtitle)
     * Right: Circular Power button
     */
    private View buildSystemTopBar() {
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        // Left: Keyboard button
        Button kbBtn = createTopCircleButton("⌨", 0xFF1C1C1E, 0xFF2E2E32);
        kbBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            showKeyboardInputDialog();
        });
        topBar.addView(kbBtn);

        // Center: Apple TV Selector Pill
        LinearLayout centerPill = new LinearLayout(this);
        centerPill.setOrientation(LinearLayout.VERTICAL);
        centerPill.setGravity(Gravity.CENTER);
        centerPill.setBackground(roundRect(0xFF1C1C1E, 0xFF2C2C2E, dp(22), dp(1)));
        centerPill.setPadding(dp(18), dp(7), dp(18), dp(7));
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pillLp.leftMargin = dp(12);
        pillLp.rightMargin = dp(12);
        centerPill.setLayoutParams(pillLp);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER);

        statusDotView = new TextView(this);
        statusDotView.setText("●");
        statusDotView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        statusDotView.setTextColor(0xFF30D158);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dotLp.rightMargin = dp(6);
        statusDotView.setLayoutParams(dotLp);
        titleRow.addView(statusDotView);

        statusTitleView = new TextView(this);
        statusTitleView.setText(DISPLAY_TITLE);
        statusTitleView.setTextColor(0xFFFFFFFF);
        statusTitleView.setTypeface(tfMedium);
        statusTitleView.setLetterSpacing(-0.01f);
        statusTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        titleRow.addView(statusTitleView);

        centerPill.addView(titleRow);

        statusSubView = new TextView(this);
        statusSubView.setText("Apple TV 4K  •  Conectando…");
        statusSubView.setTextColor(0xFF8E8E93);
        statusSubView.setTypeface(tfRegular);
        statusSubView.setLetterSpacing(0.01f);
        statusSubView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        centerPill.addView(statusSubView);

        centerPill.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            showConnectionMenuDialog();
        });
        topBar.addView(centerPill);

        // Right: Power button
        Button pwrBtn = createTopCircleButton("⏻", 0xFF1C1C1E, 0xFF2E2E32);
        pwrBtn.setText("●");
        pwrBtn.setTextColor(0xFFFF453A);
        pwrBtn.setOnTouchListener((v, event) -> {
            int act = event.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                v.setAlpha(0.65f);
                companionClient.sendHidButtonState(CompanionClient.HID_SLEEP, true);
                return true;
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                v.setAlpha(1.0f);
                companionClient.sendHidButtonState(CompanionClient.HID_SLEEP, false);
                return true;
            }
            return false;
        });
        topBar.addView(pwrBtn);

        return topBar;
    }

    private Button createTopCircleButton(String symbol, int bg, int border) {
        Button b = new Button(this);
        b.setText(symbol);
        b.setAllCaps(false);
        b.setFocusable(false);
        b.setTextColor(0xFFE5E5EA);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        b.setTypeface(tfMedium);
        b.setBackground(roundRect(bg, border, dp(21), dp(1)));
        b.setPadding(0, 0, 0, 0);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        return b;
    }

    /**
     * Apple UISegmentedControl dark style (`#1C1C1E` track with `#3A3A3C` active pill).
     */
    private View buildSegmentedTabs() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(roundRect(0xFF1C1C1E, 0xFF28282C, dp(12), dp(1)));
        bar.setPadding(dp(3), dp(3), dp(3), dp(3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        bar.setLayoutParams(lp);

        tabBtnRemote = createTabButton("Control", 0);
        tabBtnWii = createTabButton("Mando Wii", 1);
        tabBtnApps = createTabButton("Instalados", 2);

        bar.addView(tabBtnRemote);
        bar.addView(tabBtnWii);
        bar.addView(tabBtnApps);
        return bar;
    }

    private Button createTabButton(String title, int index) {
        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        b.setFocusable(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        b.setTypeface(tfMedium);
        b.setLetterSpacing(0.01f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            selectTab(index);
        });
        return b;
    }

    public void selectTab(int index) {
        tabRemoteContent.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        tabWiiContent.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        tabAppsContent.setVisibility(index == 2 ? View.VISIBLE : View.GONE);

        styleTabButton(tabBtnRemote, index == 0);
        styleTabButton(tabBtnWii, index == 1);
        styleTabButton(tabBtnApps, index == 2);

        pollLiveInstalledAppsAndGames();
    }

    private void styleTabButton(Button b, boolean selected) {
        if (selected) {
            b.setBackground(roundRect(0xFF3A3A3C, 0xFF48484A, dp(9), dp(1)));
            b.setTextColor(0xFFFFFFFF);
        } else {
            b.setBackgroundColor(Color.TRANSPARENT);
            b.setTextColor(0xFF8E8E93);
        }
    }

    /**
     * Tab 0: Native iOS / tvOS Siri Remote Layout.
     * - Large brushed dark-titanium Clickpad (`TouchpadView`)
     * - Iconic 3-column physical Siri Remote button cluster below:
     *   Col 1: Circular `<` (Back/Menu) + Circular `▶︎❙❙` (Play/Pause)
     *   Col 2: Circular `TV` (Home) + Mode/Skip pill
     *   Col 3: Integrated vertical Volume Rocker Pill (`+` / `VOL` / `−`)
     */
    private LinearLayout buildRemoteTab() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Siri Remote Clickpad
        touchpadView = new TouchpadView(this, new TouchpadView.TouchpadListener() {
            @Override
            public void onHidButtonState(int buttonCode, boolean down) {
                companionClient.sendHidButtonState(buttonCode, down);
            }

            @Override
            public void onHidButtonClick(int buttonCode) {
                companionClient.pressHidButton(buttonCode, 35);
            }

            @Override
            public void onTouchMove(int phase, float normX, float normY) {
                int x1000 = Math.round(normX * 1000f);
                int y1000 = Math.round(normY * 1000f);
                companionClient.sendTouchEvent(x1000, y1000, phase);
            }
        });
        LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        padLp.bottomMargin = dp(14);
        touchpadView.setLayoutParams(padLp);
        col.addView(touchpadView);

        // Iconic Siri Remote 3-Column Button Cluster
        int circleSize = dp(72);
        int gapY = dp(12);
        int totalClusterHeight = circleSize * 2 + gapY;

        LinearLayout cluster = new LinearLayout(this);
        cluster.setOrientation(LinearLayout.HORIZONTAL);
        cluster.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clusterLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clusterLp.bottomMargin = dp(10);
        cluster.setLayoutParams(clusterLp);

        // Column 1: Back (<) over Play/Pause (▶︎❙❙)
        LinearLayout colLeft = new LinearLayout(this);
        colLeft.setOrientation(LinearLayout.VERTICAL);
        colLeft.setGravity(Gravity.CENTER_HORIZONTAL);
        colLeft.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View backBtn = createSiriCircleButton("‹", "ATRÁS", CompanionClient.HID_MENU, circleSize);
        LinearLayout.LayoutParams topCircleLp = new LinearLayout.LayoutParams(circleSize, circleSize);
        topCircleLp.bottomMargin = gapY;
        backBtn.setLayoutParams(topCircleLp);
        colLeft.addView(backBtn);

        View playBtn = createSiriCircleButton("▶︎❙❙", "PLAY", CompanionClient.HID_PLAY_PAUSE, circleSize);
        playBtn.setLayoutParams(new LinearLayout.LayoutParams(circleSize, circleSize));
        colLeft.addView(playBtn);
        cluster.addView(colLeft);

        // Column 2: TV/Home over Touchpad Mode toggle
        LinearLayout colMid = new LinearLayout(this);
        colMid.setOrientation(LinearLayout.VERTICAL);
        colMid.setGravity(Gravity.CENTER_HORIZONTAL);
        colMid.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View homeBtn = createSiriCircleButton("TV", "HOME", CompanionClient.HID_HOME, circleSize);
        LinearLayout.LayoutParams midTopLp = new LinearLayout.LayoutParams(circleSize, circleSize);
        midTopLp.bottomMargin = gapY;
        homeBtn.setLayoutParams(midTopLp);
        colMid.addView(homeBtn);

        View siriBtn = createSiriCircleButton("◉", "SIRI", CompanionClient.HID_SIRI, circleSize);
        siriBtn.setLayoutParams(new LinearLayout.LayoutParams(circleSize, circleSize));
        colMid.addView(siriBtn);
        cluster.addView(colMid);

        // Column 3: Vertical Volume Rocker Pill (+ / VOL / −)
        LinearLayout colRight = new LinearLayout(this);
        colRight.setOrientation(LinearLayout.VERTICAL);
        colRight.setGravity(Gravity.CENTER_HORIZONTAL);
        colRight.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout volRocker = new LinearLayout(this);
        volRocker.setOrientation(LinearLayout.VERTICAL);
        volRocker.setGravity(Gravity.CENTER_HORIZONTAL);
        volRocker.setBackground(roundRect(0xFF242426, 0xFF38383A, circleSize / 2, dp(1)));
        volRocker.setLayoutParams(new LinearLayout.LayoutParams(circleSize, totalClusterHeight));

        Button volUp = new Button(this);
        volUp.setText("+");
        volUp.setFocusable(false);
        volUp.setTextColor(0xFFFFFFFF);
        volUp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        volUp.setTypeface(tfRegular);
        volUp.setBackgroundColor(Color.TRANSPARENT);
        volUp.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        bindHoldHid(volUp, CompanionClient.HID_VOLUME_UP);
        volRocker.addView(volUp);

        TextView volLabel = new TextView(this);
        volLabel.setText("VOL");
        volLabel.setGravity(Gravity.CENTER);
        volLabel.setTextColor(0xFF636366);
        volLabel.setTypeface(tfMedium);
        volLabel.setLetterSpacing(0.08f);
        volLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        volRocker.addView(volLabel);

        Button volDown = new Button(this);
        volDown.setText("−");
        volDown.setFocusable(false);
        volDown.setTextColor(0xFFFFFFFF);
        volDown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        volDown.setTypeface(tfRegular);
        volDown.setBackgroundColor(Color.TRANSPARENT);
        volDown.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        bindHoldHid(volDown, CompanionClient.HID_VOLUME_DOWN);
        volRocker.addView(volDown);

        colRight.addView(volRocker);
        cluster.addView(colRight);

        col.addView(cluster);

        // Bottom subtle utility bar: Skip -10s  |  Modo Clickpad/D-Pad  |  Skip +10s
        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER_VERTICAL);

        Button skipBack = createSubtlePillButton("↺ 10s");
        skipBack.setLayoutParams(new LinearLayout.LayoutParams(0, dp(36), 0.7f));
        skipBack.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            companionClient.sendMediaControl(CompanionClient.MCC_SKIP_BY, Collections.singletonMap("_skpS", -10.0));
        });
        bottomRow.addView(skipBack);

        Button padModeBtn = createSubtlePillButton("Clickpad Táctil");
        LinearLayout.LayoutParams pmLp = new LinearLayout.LayoutParams(0, dp(36), 1.3f);
        pmLp.leftMargin = dp(8);
        pmLp.rightMargin = dp(8);
        padModeBtn.setLayoutParams(pmLp);
        padModeBtn.setOnClickListener(v -> {
            boolean next = !touchpadView.isDpadHoldMode();
            touchpadView.setDpadHoldMode(next);
            padModeBtn.setText(next ? "D-Pad Continuo" : "Clickpad Táctil");
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        });
        bottomRow.addView(padModeBtn);

        Button skipFwd = createSubtlePillButton("10s ↻");
        skipFwd.setLayoutParams(new LinearLayout.LayoutParams(0, dp(36), 0.7f));
        skipFwd.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            companionClient.sendMediaControl(CompanionClient.MCC_SKIP_BY, Collections.singletonMap("_skpS", 10.0));
        });
        bottomRow.addView(skipFwd);

        col.addView(bottomRow);
        return col;
    }

    private View createSiriCircleButton(String glyph, String caption, int hidCode, int sizePx) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(roundRect(0xFF242426, 0xFF38383A, sizePx / 2, dp(1)));

        TextView icon = new TextView(this);
        icon.setText(glyph);
        icon.setGravity(Gravity.CENTER);
        icon.setTextColor(0xFFFFFFFF);
        icon.setTypeface(tfMedium);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, glyph.length() > 2 ? 13f : 18f);
        box.addView(icon);

        TextView sub = new TextView(this);
        sub.setText(caption);
        sub.setGravity(Gravity.CENTER);
        sub.setTextColor(0xFF8E8E93);
        sub.setTypeface(tfMedium);
        sub.setLetterSpacing(0.06f);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        box.addView(sub);

        if (hidCode == CompanionClient.HID_SIRI) {
            box.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                triggerSiriVoiceAndSearch();
            });
            box.setOnLongClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showKeyboardInputDialog();
                return true;
            });
        } else {
            bindHoldHid(box, hidCode);
        }
        return box;
    }

    private void bindHoldHid(View view, int hidCode) {
        view.setOnTouchListener((v, event) -> {
            int act = event.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                v.setAlpha(0.68f);
                companionClient.sendHidButtonState(hidCode, true);
                return true;
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                v.setAlpha(1.0f);
                companionClient.sendHidButtonState(hidCode, false);
                return true;
            }
            return false;
        });
    }

    private Button createSubtlePillButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setFocusable(false);
        b.setTextColor(0xFFE5E5EA);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        b.setTypeface(tfMedium);
        b.setLetterSpacing(0.02f);
        b.setBackground(roundRect(0xFF1C1C1E, 0xFF2C2C2E, dp(18), dp(1)));
        b.setPadding(dp(10), 0, dp(10), 0);
        return b;
    }

    /**
     * Tab 1: Mando Wii with Live Installed Games Strip.
     */
    private LinearLayout buildWiiTab() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        Button turboBtn = createSubtlePillButton("Turbo A/B: Desactivado");
        turboBtn.setOnClickListener(v -> {
            boolean next = !wiiPadView.isTurboEnabled();
            wiiPadView.setTurboEnabled(next);
            turboBtn.setText(next ? "Turbo A/B: Activo" : "Turbo A/B: Desactivado");
            turboBtn.setBackground(roundRect(next ? 0xFF2C2C2E : 0xFF1C1C1E,
                    next ? 0xFF0A84FF : 0xFF2C2C2E, dp(18), dp(1)));
        });
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, dp(34), 1f);
        tLp.rightMargin = dp(8);
        turboBtn.setLayoutParams(tLp);
        topBar.addView(turboBtn);

        Button tiltBtn = createSubtlePillButton("Giroscopio: Desactivado");
        tiltBtn.setOnClickListener(v -> {
            tiltModeEnabled = !tiltModeEnabled;
            updateAccelerometerRegistration();
            tiltBtn.setText(tiltModeEnabled ? "Giroscopio: Activo" : "Giroscopio: Desactivado");
            tiltBtn.setBackground(roundRect(tiltModeEnabled ? 0xFF2C2C2E : 0xFF1C1C1E,
                    tiltModeEnabled ? 0xFF30D158 : 0xFF2C2C2E, dp(18), dp(1)));
        });
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(0, dp(34), 1f);
        tiltBtn.setLayoutParams(gLp);
        topBar.addView(tiltBtn);

        col.addView(topBar);

        wiiPadView = new WiiPadView(this, (hidButton, pressed) ->
                companionClient.sendHidButtonState(hidButton, pressed));
        LinearLayout.LayoutParams wiiLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        wiiLp.topMargin = dp(8);
        wiiLp.bottomMargin = dp(8);
        wiiPadView.setLayoutParams(wiiLp);
        col.addView(wiiPadView);

        // Live strip of installed Wii games on the Apple TV
        HorizontalScrollView gameStripScroll = new HorizontalScrollView(this);
        gameStripScroll.setHorizontalScrollBarEnabled(false);
        wiiLiveGamesStrip = new LinearLayout(this);
        wiiLiveGamesStrip.setOrientation(LinearLayout.HORIZONTAL);
        gameStripScroll.addView(wiiLiveGamesStrip);
        col.addView(gameStripScroll);

        rebuildWiiLiveGamesStrip();
        return col;
    }

    private void rebuildWiiLiveGamesStrip() {
        if (wiiLiveGamesStrip == null) return;
        wiiLiveGamesStrip.removeAllViews();

        Map<String, String> games = extractInstalledGamesMap();
        for (Map.Entry<String, String> entry : games.entrySet()) {
            String bundleId = entry.getKey();
            String name = entry.getValue();
            addQuickLaunchChip(wiiLiveGamesStrip, name, () ->
                    companionClient.launchApp(bundleId, () ->
                            runOnUiThread(() -> Toast.makeText(this, "Abriendo " + name, Toast.LENGTH_SHORT).show())));
        }
        addQuickLaunchChip(wiiLiveGamesStrip, "TV Home", () ->
                companionClient.pressHidButton(CompanionClient.HID_HOME, 40));
    }

    private void addQuickLaunchChip(LinearLayout parent, String text, Runnable action) {
        Button chip = createSubtlePillButton(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        lp.rightMargin = dp(8);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            action.run();
        });
        parent.addView(chip);
    }

    /**
     * Tab 2: Real-Time Installed Games & tvOS Applications on Apple TV Sala.
     */
    private ScrollView buildAppsTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);

        // Live sync header pill
        LinearLayout syncRow = new LinearLayout(this);
        syncRow.setOrientation(LinearLayout.HORIZONTAL);
        syncRow.setGravity(Gravity.CENTER_VERTICAL);
        syncRow.setBackground(roundRect(0xFF161618, 0xFF262629, dp(14), dp(1)));
        syncRow.setPadding(dp(14), dp(10), dp(14), dp(10));

        liveSyncBadgeText = new TextView(this);
        liveSyncBadgeText.setText("● Sincronizando en tiempo real con Sala…");
        liveSyncBadgeText.setTextColor(0xFF30D158);
        liveSyncBadgeText.setTypeface(tfMedium);
        liveSyncBadgeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        liveSyncBadgeText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        syncRow.addView(liveSyncBadgeText);

        Button syncNowBtn = createSubtlePillButton("Actualizar");
        syncNowBtn.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));
        syncNowBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            pollLiveInstalledAppsAndGames();
        });
        syncRow.addView(syncNowBtn);
        container.addView(syncRow);

        // Section 1: JUEGOS INSTALADOS EN APPLE TV
        gamesCountHeader = createSectionHeader("JUEGOS INSTALADOS EN APPLE TV");
        container.addView(gamesCountHeader);

        gamesListContainer = new LinearLayout(this);
        gamesListContainer.setOrientation(LinearLayout.VERTICAL);
        container.addView(gamesListContainer);

        // Provenance ROMs & Library Card
        LinearLayout provCard = new LinearLayout(this);
        provCard.setOrientation(LinearLayout.VERTICAL);
        provCard.setBackground(roundRect(0xFF1C1C1E, 0xFF2C2C2E, dp(16), dp(1)));
        provCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pcLp.topMargin = dp(8);
        provCard.setLayoutParams(pcLp);

        provenanceStatusText = new TextView(this);
        provenanceStatusText.setText("Servidor de ROMs Provenance en espera");
        provenanceStatusText.setTextColor(0xFF8E8E93);
        provenanceStatusText.setTypeface(tfRegular);
        provenanceStatusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        provCard.addView(provenanceStatusText);

        Button refreshLibBtn = createSubtlePillButton("Recargar Biblioteca Provenance");
        LinearLayout.LayoutParams rlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
        rlLp.topMargin = dp(8);
        refreshLibBtn.setLayoutParams(rlLp);
        refreshLibBtn.setOnClickListener(v -> triggerProvenanceLibraryRefresh());
        provCard.addView(refreshLibBtn);
        container.addView(provCard);

        // Section 2: APLICACIONES INSTALADAS EN APPLE TV
        appsCountHeader = createSectionHeader("APLICACIONES EN APPLE TV");
        container.addView(appsCountHeader);

        appsListContainer = new LinearLayout(this);
        appsListContainer.setOrientation(LinearLayout.VERTICAL);
        container.addView(appsListContainer);

        rebuildInstalledListsUi();
        scroll.addView(container);
        return scroll;
    }

    private TextView createSectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFF8E8E93);
        tv.setTypeface(tfMedium);
        tv.setLetterSpacing(0.06f);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        lp.bottomMargin = dp(6);
        lp.leftMargin = dp(4);
        tv.setLayoutParams(lp);
        return tv;
    }

    /**
     * Polls the Apple TV in real time via Companion (`FetchLaunchableApplicationsEvent`)
     * and Provenance Web Server (`/list?path=/`) to keep installed games & apps live.
     */
    private void pollLiveInstalledAppsAndGames() {
        if (companionClient != null && companionClient.isConnected()) {
            companionClient.fetchLaunchableApps(apps -> runOnUiThread(() -> {
                if (apps != null && !apps.isEmpty()) {
                    lastLiveApps.clear();
                    lastLiveApps.putAll(apps);
                    rebuildInstalledListsUi();
                    rebuildWiiLiveGamesStrip();
                    String timeStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                    if (liveSyncBadgeText != null) {
                        liveSyncBadgeText.setText("● En vivo desde Sala  •  Actualizado " + timeStr);
                        liveSyncBadgeText.setTextColor(0xFF30D158);
                    }
                }
            }));
        }
        ProvenanceClient.checkWebServerAndRoms(prefs.getHost(), (online, roms) -> runOnUiThread(() -> {
            lastLiveRoms.clear();
            if (roms != null) {
                lastLiveRoms.addAll(roms);
            }
            if (provenanceStatusText != null) {
                if (online) {
                    String extra = lastLiveRoms.isEmpty() ? "" : (" • " + lastLiveRoms.size() + " archivos en raíz");
                    provenanceStatusText.setText("● Servidor Web Provenance activo en Sala" + extra);
                    provenanceStatusText.setTextColor(0xFF30D158);
                } else {
                    provenanceStatusText.setText("Servidor Web Provenance en espera (se activa al abrir Provenance)");
                    provenanceStatusText.setTextColor(0xFF8E8E93);
                }
            }
        }));
    }

    private static boolean isGameBundle(String bundleId, String name) {
        String b = bundleId.toLowerCase(Locale.ROOT);
        String n = name != null ? name.toLowerCase(Locale.ROOT) : "";
        return b.contains(".wii.")
                || b.contains("provenance")
                || b.contains("dolphin")
                || b.contains("retroarch")
                || n.contains("[wii]")
                || n.contains("mario kart")
                || n.contains("wwe")
                || n.contains("provenance");
    }

    private Map<String, String> extractInstalledGamesMap() {
        Map<String, String> games = new LinkedHashMap<>();
        if (!lastLiveApps.isEmpty()) {
            for (Map.Entry<String, String> entry : lastLiveApps.entrySet()) {
                if (isGameBundle(entry.getKey(), entry.getValue())) {
                    games.put(entry.getKey(), entry.getValue());
                }
            }
        }
        if (games.isEmpty()) {
            games.put(MARIO_KART_BUNDLE_ID, "[Wii] Mario Kart");
            games.put(WWE12_BUNDLE_ID, "[Wii] WWE '12");
            games.put(PROVENANCE_BUNDLE_ID, "Provenance");
        }
        return games;
    }

    private void rebuildInstalledListsUi() {
        if (gamesListContainer == null || appsListContainer == null) return;
        gamesListContainer.removeAllViews();
        appsListContainer.removeAllViews();

        Map<String, String> games = extractInstalledGamesMap();
        for (Map.Entry<String, String> entry : games.entrySet()) {
            String bundle = entry.getKey();
            String name = entry.getValue();
            String sub = bundle.contains(".wii.")
                    ? "Juego Wii Standalone instalado en Sala"
                    : "Emulador / Consola Wii en Sala";
            addInstalledRow(gamesListContainer, name, sub, bundle, true);
        }
        if (gamesCountHeader != null) {
            gamesCountHeader.setText("JUEGOS INSTALADOS EN SALA (" + games.size() + ")");
        }

        List<Map.Entry<String, String>> normalApps = new ArrayList<>();
        for (Map.Entry<String, String> entry : lastLiveApps.entrySet()) {
            if (!isGameBundle(entry.getKey(), entry.getValue())) {
                normalApps.add(entry);
            }
        }
        normalApps.sort((a, b) -> a.getValue().compareToIgnoreCase(b.getValue()));

        if (normalApps.isEmpty()) {
            addInstalledRow(appsListContainer, "YouTube", "App tvOS instalada", "com.google.ios.youtube", false);
            addInstalledRow(appsListContainer, "Netflix", "App tvOS instalada", "com.netflix.Netflix", false);
            addInstalledRow(appsListContainer, "Apple TV", "App del sistema tvOS", "com.apple.TVWatchList", false);
            addInstalledRow(appsListContainer, "Spotify", "App tvOS instalada", "com.spotify.client", false);
            addInstalledRow(appsListContainer, "Ajustes", "Configuración de Apple TV", "com.apple.TVSettings", false);
            if (appsCountHeader != null) {
                appsCountHeader.setText("APLICACIONES EN SALA");
            }
        } else {
            for (Map.Entry<String, String> entry : normalApps) {
                String bundle = entry.getKey();
                String sub = bundle.startsWith("com.apple.") ? "App del sistema tvOS" : "App instalada en Apple TV";
                addInstalledRow(appsListContainer, entry.getValue(), sub, bundle, false);
            }
            if (appsCountHeader != null) {
                appsCountHeader.setText("APLICACIONES EN SALA (" + normalApps.size() + ")");
            }
        }
    }

    private void addInstalledRow(
            LinearLayout parent, String name, String subtitle, String bundleId, boolean isGame) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(roundRect(0xFF1C1C1E, 0xFF2A2A2D, dp(14), dp(1)));
        row.setPadding(dp(14), dp(11), dp(12), dp(11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText(name);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(tfMedium);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        textCol.addView(title);

        TextView sub = new TextView(this);
        sub.setText(subtitle);
        sub.setTextColor(0xFF8E8E93);
        sub.setTypeface(tfRegular);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        textCol.addView(sub);

        row.addView(textCol);

        Button openBtn = new Button(this);
        openBtn.setText(isGame ? "Jugar" : "Abrir");
        openBtn.setAllCaps(false);
        openBtn.setFocusable(false);
        openBtn.setTextColor(isGame ? 0xFFFFFFFF : 0xFF0A84FF);
        openBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        openBtn.setTypeface(tfMedium);
        openBtn.setBackground(roundRect(
                isGame ? 0xFF0A84FF : 0xFF2C2C2E,
                isGame ? 0xFF0A84FF : 0xFF3A3A3C,
                dp(15), dp(1)));
        openBtn.setPadding(dp(14), dp(4), dp(14), dp(4));
        openBtn.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));
        openBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            companionClient.launchApp(bundleId, () -> runOnUiThread(() -> {
                Toast.makeText(this, "Abriendo " + name + " en Sala", Toast.LENGTH_SHORT).show();
                if (isGame) {
                    selectTab(1); // Switch automatically to Mando Wii when launching a game
                }
            }));
        });
        row.addView(openBtn);

        parent.addView(row);
    }

    private void connectToAppleTv() {
        String credsStr = prefs.getCreds();
        if (credsStr == null || credsStr.isEmpty()) {
            triggerStartPairing();
            return;
        }
        try {
            HapSrp.HapCredentials creds = HapSrp.HapCredentials.parse(credsStr);
            companionClient.connectAsync(prefs.getHost(), prefs.getPort(), creds);
        } catch (Exception e) {
            updateConnectionBanner(false, "Revinculando…", -1);
        }
    }

    private void triggerStartPairing() {
        companionClient.startPairingAsync(prefs.getHost(), prefs.getPort(),
                () -> runOnUiThread(this::showPinEntryDialog));
    }

    private void updateConnectionBanner(boolean connected, String statusText, long rttMs) {
        statusTitleView.setText(DISPLAY_TITLE);
        if (connected) {
            statusDotView.setTextColor(0xFF30D158);
            String latency = rttMs >= 0 ? ("  •  " + rttMs + " ms") : "";
            statusSubView.setText("Apple TV 4K  •  Conectado" + latency);
        } else {
            statusDotView.setTextColor(0xFFFF9F0A);
            statusSubView.setText(sanitizeNoIp(statusText));
        }
    }

    private void showPinEntryDialog() {
        if (isFinishing()) return;
        if (activePinDialog != null && activePinDialog.isShowing()) {
            return;
        }
        EditText pinInput = new EditText(this);
        pinInput.setHint("Código de 4 dígitos");
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        pinInput.setGravity(Gravity.CENTER);
        pinInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);

        activePinDialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Vincular con Apple TV Sala")
                .setMessage("Ingresá los 4 dígitos que aparecen en la pantalla de tu Apple TV:")
                .setView(pinInput)
                .setCancelable(true)
                .setPositiveButton("Vincular", (d, w) -> {
                    String pin = pinInput.getText().toString().trim();
                    if (!pin.isEmpty()) {
                        companionClient.finishPairingAsync(pin, creds -> {
                            String serialized = creds.toString();
                            prefs.saveCreds(serialized);
                            exportAtvCredsJson(serialized);
                            runOnUiThread(() -> Toast.makeText(
                                    MainActivity.this,
                                    "Vinculado con Apple TV Sala",
                                    Toast.LENGTH_LONG).show());
                        });
                    }
                })
                .setNegativeButton("Cancelar", null)
                .create();
        activePinDialog.show();
    }

    private void showKeyboardInputDialog() {
        EditText input = new EditText(this);
        input.setHint("Escribir o buscar en Apple TV…");
        input.setSingleLine(true);

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Teclado y Búsqueda de Apple TV")
                .setMessage("Escribí qué buscar o qué texto enviar a Sala:")
                .setView(input)
                .setPositiveButton("Buscar / Reemplazar", (d, w) ->
                        companionClient.sendTextInput(input.getText().toString(), true))
                .setNeutralButton("Agregar", (d, w) ->
                        companionClient.sendTextInput(input.getText().toString(), false))
                .setNegativeButton("Cerrar", null)
                .show();
    }

    /**
     * Activa Siri / Búsqueda en el Apple TV Sala y abre el micrófono de voz del Motorola Signature
     * para dictar búsquedas o ejecutar comandos por voz ("Abrir YouTube", "Jugar Mario Kart", "Pausa", etc.).
     */
    private void triggerSiriVoiceAndSearch() {
        companionClient.activateSiriOrSearch(null);
        try {
            Intent voiceIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            voiceIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            voiceIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            voiceIntent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Siri • Hablá para controlar o buscar en Sala…");
            startActivityForResult(voiceIntent, REQ_VOICE_SIRI);
        } catch (Exception e) {
            Toast.makeText(this, "Abriendo búsqueda en Apple TV Sala…", Toast.LENGTH_SHORT).show();
            showKeyboardInputDialog();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VOICE_SIRI && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                String spoken = results.get(0);
                if (spoken != null && !spoken.trim().isEmpty()) {
                    handleSiriVoiceCommand(spoken.trim());
                }
            }
        }
    }

    private static String normalizeVoice(String s) {
        if (s == null) return "";
        String norm = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return norm.replaceAll("\\p{InCombiningDiacriticalMarks}+", "").trim();
    }

    private void handleSiriVoiceCommand(String spokenText) {
        String norm = normalizeVoice(spokenText);

        // 1. Comandos de navegación / reproducción por voz
        if (norm.equals("pausa") || norm.equals("pausar") || norm.equals("reproducir")
                || norm.equals("play") || norm.equals("continuar")) {
            companionClient.pressHidButton(CompanionClient.HID_PLAY_PAUSE, 40);
            Toast.makeText(this, "Siri: Play / Pausa", Toast.LENGTH_SHORT).show();
            return;
        }
        if (norm.equals("inicio") || norm.equals("home") || norm.equals("menu principal")
                || norm.equals("ir al inicio")) {
            companionClient.pressHidButton(CompanionClient.HID_HOME, 40);
            Toast.makeText(this, "Siri: Inicio de Apple TV", Toast.LENGTH_SHORT).show();
            return;
        }
        if (norm.equals("atras") || norm.equals("volver") || norm.equals("menu")) {
            companionClient.pressHidButton(CompanionClient.HID_MENU, 40);
            Toast.makeText(this, "Siri: Atrás", Toast.LENGTH_SHORT).show();
            return;
        }
        if (norm.contains("subir volumen") || norm.contains("mas volumen")) {
            for (int i = 0; i < 3; i++) {
                companionClient.pressHidButton(CompanionClient.HID_VOLUME_UP, 30);
            }
            Toast.makeText(this, "Siri: Subiendo volumen", Toast.LENGTH_SHORT).show();
            return;
        }
        if (norm.contains("bajar volumen") || norm.contains("menos volumen")) {
            for (int i = 0; i < 3; i++) {
                companionClient.pressHidButton(CompanionClient.HID_VOLUME_DOWN, 30);
            }
            Toast.makeText(this, "Siri: Bajando volumen", Toast.LENGTH_SHORT).show();
            return;
        }
        if (norm.contains("apagar") || norm.contains("suspender") || norm.contains("reposo")) {
            companionClient.pressHidButton(CompanionClient.HID_SLEEP, 40);
            Toast.makeText(this, "Siri: Suspendiendo Apple TV", Toast.LENGTH_SHORT).show();
            return;
        }

        // 2. Comandos para abrir apps o juegos instalados ("abrir youtube", "jugar mario kart", etc.)
        for (Map.Entry<String, String> entry : lastLiveApps.entrySet()) {
            String bundleId = entry.getKey();
            String appName = entry.getValue();
            String cleanName = normalizeVoice(appName.replace("[Wii]", ""));
            if (!cleanName.isEmpty() && norm.contains(cleanName)) {
                companionClient.launchApp(bundleId, () -> runOnUiThread(() -> {
                    Toast.makeText(this, "Siri: Abriendo " + appName + " en Sala", Toast.LENGTH_SHORT).show();
                    if (isGameBundle(bundleId, appName)) {
                        selectTab(1);
                    }
                }));
                return;
            }
        }

        // 3. Cualquier otra frase hablada: dictar directamente en la búsqueda del Apple TV
        Toast.makeText(this, "Siri buscando en Sala: \"" + spokenText + "\"", Toast.LENGTH_SHORT).show();
        companionClient.sendTextInput(spokenText, true);
    }

    private void showConnectionMenuDialog() {
        String[] options = new String[]{
                "Sincronizar juegos y apps ahora",
                "Reconectar con Apple TV Sala",
                "Vincular nuevo código PIN"
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Apple TV 4K • Sala")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        pollLiveInstalledAppsAndGames();
                    } else if (which == 1) {
                        connectToAppleTv();
                    } else if (which == 2) {
                        triggerStartPairing();
                    }
                })
                .show();
    }

    private void triggerProvenanceLibraryRefresh() {
        ProvenanceClient.uploadRefreshFlag(prefs.getHost(), (ok, message) -> runOnUiThread(() -> {
            String cleanMsg = sanitizeNoIp(message);
            Toast.makeText(MainActivity.this, cleanMsg, Toast.LENGTH_SHORT).show();
            if (provenanceStatusText != null) {
                provenanceStatusText.setText(cleanMsg);
                provenanceStatusText.setTextColor(ok ? 0xFF30D158 : 0xFFFF9F0A);
            }
        }));
    }

    private String loadCredsFromLocalFile() {
        File[] candidates = new File[]{
                new File(getExternalFilesDir(null), "atv-creds.json"),
                new File(getFilesDir(), "atv-creds.json")
        };
        for (File f : candidates) {
            if (f != null && f.exists() && f.length() > 0) {
                try (FileInputStream fis = new FileInputStream(f)) {
                    byte[] buf = new byte[(int) f.length()];
                    int read = fis.read(buf);
                    if (read > 0) {
                        JSONObject obj = new JSONObject(new String(buf, 0, read, StandardCharsets.UTF_8));
                        String c = obj.optString("companion", "").trim();
                        if (!c.isEmpty()) {
                            return c;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return "";
    }

    private File exportAtvCredsJson(String companionCreds) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("id", "apple-tv-companion");
            obj.put("name", DISPLAY_TITLE);
            obj.put("companion", companionCreds);
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            File out = new File(dir, "atv-creds.json");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(obj.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private void startMdnsDiscovery() {
        try {
            nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
            if (nsdManager == null) return;
            discoveryListener = new NsdManager.DiscoveryListener() {
                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {}
                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
                @Override
                public void onDiscoveryStarted(String serviceType) {}
                @Override
                public void onDiscoveryStopped(String serviceType) {}

                @Override
                public void onServiceFound(NsdServiceInfo serviceInfo) {
                    String sName = serviceInfo.getServiceName();
                    if (sName == null || !sName.toLowerCase(Locale.ROOT).contains("sala")) {
                        return;
                    }
                    nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo si, int err) {}

                        @Override
                        public void onServiceResolved(NsdServiceInfo resolved) {
                            if (resolved.getHost() == null) return;
                            String hostIp = resolved.getHost().getHostAddress();
                            int port = resolved.getPort();
                            if (hostIp != null && !hostIp.contains(":") && port > 0) {
                                boolean changed = !hostIp.equals(prefs.getHost()) || port != prefs.getPort();
                                prefs.saveTarget(DISPLAY_TITLE, hostIp, port);
                                runOnUiThread(() -> {
                                    statusTitleView.setText(DISPLAY_TITLE);
                                    if (changed && !companionClient.isConnected()) {
                                        connectToAppleTv();
                                    }
                                });
                            }
                        }
                    });
                }

                @Override
                public void onServiceLost(NsdServiceInfo serviceInfo) {}
            };
            nsdManager.discoverServices("_companion-link._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            companionClient.pressHidButton(CompanionClient.HID_VOLUME_UP, 30);
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            companionClient.pressHidButton(CompanionClient.HID_VOLUME_DOWN, 30);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void updateAccelerometerRegistration() {
        if (sensorManager == null || accelerometer == null) return;
        sensorManager.unregisterListener(this);
        if (tiltModeEnabled) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        } else if (wiiPadView != null) {
            wiiPadView.setTiltDirections(false, false, false, false);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!tiltModeEnabled || wiiPadView == null || event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
            return;
        }
        float ax = event.values[0];
        float ay = event.values[1];
        float threshold = 3.2f;
        boolean left = ax > threshold;
        boolean right = ax < -threshold;
        boolean up = ay < -threshold;
        boolean down = ay > threshold + 2.5f;
        wiiPadView.setTiltDirections(up, down, left, right);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    protected void onPause() {
        super.onPause();
        liveSyncHandler.removeCallbacks(liveSyncRunnable);
        if (wiiPadView != null) {
            wiiPadView.releaseAll();
        }
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccelerometerRegistration();
        liveSyncHandler.removeCallbacks(liveSyncRunnable);
        liveSyncHandler.post(liveSyncRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        liveSyncHandler.removeCallbacks(liveSyncRunnable);
        if (nsdManager != null && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception ignored) {
            }
        }
        if (companionClient != null) {
            companionClient.disconnectAsync();
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static GradientDrawable roundRect(int fillColor, int strokeColor, int radiusPx, int strokeWidthPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(fillColor);
        gd.setCornerRadius(radiusPx);
        gd.setStroke(strokeWidthPx, strokeColor);
        return gd;
    }
}
