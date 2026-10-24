// SPDX-License-Identifier: Apache-2.0
package io.github.tyreyn.floatingplayer;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * A Music Assistant mini player that floats above other apps.
 *
 * <p>SDK overlays live inside the Kiosk Satellite window and disappear once another app comes to the front, so this
 * plugin adds a regular system window (TYPE_APPLICATION_OVERLAY) instead. The plugin runs inside the Kiosk Satellite
 * process, which already holds the "Display over other apps" permission. Player state and controls go through the
 * kiosk's own Music Assistant connection (maCall / maSubscribe).
 */
public final class FloatingPlayerPlugin implements KioskPlugin {
    private static final String SUBSCRIPTION = "player";
    private static final String PLAYER_SETTING = "player";
    private static final String AUTO = "auto";

    private final Handler main = new Handler(Looper.getMainLooper());
    private PluginHost host;
    private Context ctx;
    private WindowManager wm;

    // Settings
    private String playerSetting = AUTO;
    private boolean showOnKiosk;
    private int savedX = -1;
    private int savedY = -1;
    private boolean hiddenByCommand;
    // Title of the track whose window the close button dismissed
    private volatile String dismissedTitle;

    // The kiosk's own player, from getSendspinPlayer / sendspin.player
    private volatile String kioskPlayerId = "";
    // The player currently followed
    private volatile String playerId = "";

    // Player state: written on the plugin's callback worker, read on the main thread
    private volatile String state = "idle";
    private volatile String title = "";
    private volatile String artist = "";
    private volatile String imageUrl = "";
    private volatile double duration;
    private volatile double elapsed;
    private volatile long elapsedAt;

    // Views: main thread only
    private FrameLayout root;
    private WindowManager.LayoutParams lp;
    private ImageView art;
    private TextView titleView;
    private TextView artistView;
    private TextView playButton;
    private View progress;
    private String shownImage = "";
    private boolean attached;
    private volatile boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            refresh();
            main.postDelayed(this, 1000);
        }
    };

    private final Runnable refreshNow = new Runnable() {
        @Override
        public void run() {
            refresh();
        }
    };

    @Override
    public void start(PluginHost host, Map<String, Object> settings) throws Exception {
        this.host = host;
        ctx = application();
        if (ctx == null) {
            host.status("No application context. The player window cannot be shown.", true);
            return;
        }
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        apply(settings);
        running = true;

        host.subscribe("sendspin.player");
        host.executeCommand("getSendspinPlayer", Collections.<String, Object>emptyMap(), new PluginHost.CommandCallback() {
            @Override
            public void onResult(boolean ok, Object data, String error) {
                if (ok && data instanceof Map) kioskPlayer((Map<?, ?>) data);
                else follow();
            }
        });
        listPlayers(null);
        main.post(tick);
        if (!canDraw()) reportReady(host);
    }

    @Override
    public void configure(Map<String, Object> settings) throws Exception {
        String before = playerSetting;
        apply(settings);
        if (!before.equals(playerSetting)) follow();
        main.post(new Runnable() {
            @Override
            public void run() {
                place();
                refresh();
            }
        });
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) throws Exception {
        if ("hide".equals(command)) {
            hiddenByCommand = true;
        } else if ("show".equals(command)) {
            hiddenByCommand = false;
            dismissedTitle = null;
        } else if ("show_on_kiosk".equals(command) || "apps_only".equals(command)) {
            hiddenByCommand = false;
            dismissedTitle = null;
            showOnKiosk = "show_on_kiosk".equals(command);
            saveSetting("showOnKiosk", showOnKiosk);
        }
        main.post(refreshNow);
    }

    @Override
    public void onEvent(String event, Map<String, Object> payload) throws Exception {
        if (("ks.ma.subscription." + SUBSCRIPTION).equals(event)) {
            Object type = payload.get("type");
            if ("event".equals(type)) {
                Object data = payload.get("data");
                if (data instanceof Map) player((Map<?, ?>) data);
            } else if ("reconnected".equals(type)) {
                load();
            }
        } else if ("ks.sendspin.player".equals(event)) {
            kioskPlayer(payload);
        } else if ("settings.choices".equals(event) && PLAYER_SETTING.equals(payload.get("key"))) {
            Object run = payload.get("run");
            listPlayers(run instanceof String ? (String) run : null);
        }
    }

    @Override
    public void stop() throws Exception {
        running = false;
        main.removeCallbacks(tick);
        main.post(new Runnable() {
            @Override
            public void run() {
                detach();
            }
        });
        host = null;
    }

    // ---------- Music Assistant ----------

    private void apply(Map<String, Object> settings) {
        Object p = settings.get(PLAYER_SETTING);
        playerSetting = p instanceof String && !((String) p).trim().isEmpty() ? ((String) p).trim() : AUTO;
        showOnKiosk = Boolean.TRUE.equals(settings.get("showOnKiosk"));
        savedX = number(settings.get("x"), -1);
        savedY = number(settings.get("y"), -1);
    }

    private void kioskPlayer(Map<?, ?> snapshot) {
        String id = text(snapshot.get("maPlayerId"), "");
        boolean changed = !id.equals(kioskPlayerId);
        kioskPlayerId = id;
        if (changed || playerId.isEmpty()) follow();
    }

    /** Follows the chosen player, or the kiosk's own player when the setting is Automatic. */
    private void follow() {
        PluginHost h = host;
        if (h == null) return;
        String target = AUTO.equals(playerSetting) ? kioskPlayerId : playerSetting;
        if (target.equals(playerId) && !target.isEmpty()) return;
        h.maUnsubscribe(SUBSCRIPTION);
        playerId = target;
        clearMedia();
        main.post(refreshNow);
        if (target.isEmpty()) {
            if (!canDraw()) reportReady(h);
            else h.status("Waiting for this kiosk's Music Assistant player. Pick a player in the settings to follow another one.", false);
            return;
        }
        Map<String, Object> filter = new HashMap<>();
        filter.put("events", Arrays.asList("player_updated"));
        filter.put("objectId", target);
        final PluginHost callbackHost = h;
        h.maSubscribe(SUBSCRIPTION, filter, new PluginHost.CommandCallback() {
            @Override
            public void onResult(boolean ok, Object data, String error) {
                if (ok) reportReady(callbackHost);
                else callbackHost.status("Could not follow Music Assistant: " + error, true);
            }
        });
        load();
    }

    private void reportReady(PluginHost h) {
        if (!canDraw()) {
            h.status("Kiosk Satellite is not allowed to display over other apps. Grant it in Android Settings > Apps > Kiosk Satellite.", true);
        } else {
            h.status("Ready. The player appears above other apps while music is loaded.", false);
        }
    }

    private void load() {
        PluginHost h = host;
        if (h == null || playerId.isEmpty()) return;
        h.maCall("players/get", Collections.<String, Object>singletonMap("player_id", playerId), new PluginHost.CommandCallback() {
            @Override
            public void onResult(boolean ok, Object data, String error) {
                if (ok && data instanceof Map) player((Map<?, ?>) data);
            }
        });
    }

    /** Lists Music Assistant players as the choices of the Player setting. */
    private void listPlayers(final String run) {
        final PluginHost h = host;
        if (h == null) return;
        if (run != null) h.setupResult(run, "running", "Asking Music Assistant for its players");
        h.maCall("players/all", Collections.<String, Object>emptyMap(), new PluginHost.CommandCallback() {
            @Override
            public void onResult(boolean ok, Object data, String error) {
                if (!ok || !(data instanceof List)) {
                    if (run != null) h.setupResult(run, "error", "Music Assistant did not list its players: " + error);
                    return;
                }
                List<String> ids = new ArrayList<>();
                List<String> labels = new ArrayList<>();
                ids.add(AUTO);
                labels.add("Automatic (this kiosk's player)");
                for (Object o : (List<?>) data) {
                    if (!(o instanceof Map) || ids.size() >= 200) continue;
                    Map<?, ?> p = (Map<?, ?>) o;
                    String id = text(p.get("player_id"), "");
                    if (id.isEmpty() || ids.contains(id)) continue;
                    String name = text(p.get("display_name"), text(p.get("name"), id));
                    ids.add(id);
                    labels.add(name.length() > 80 ? name.substring(0, 80) : name);
                }
                try {
                    h.setSettingChoices(PLAYER_SETTING, ids.toArray(new String[0]), labels.toArray(new String[0]));
                    if (run != null) h.setupResult(run, "success", "Found " + (ids.size() - 1) + " players.");
                } catch (UnsupportedOperationException e) {
                    // Older Kiosk Satellite versions without plugin choices: the setting stays a text field.
                }
            }
        });
    }

    private void player(Map<?, ?> p) {
        Object pid = p.get("player_id");
        if (pid != null && !playerId.equals(pid)) return;
        state = text(p.get("playback_state"), text(p.get("state"), "idle"));
        Object media = p.get("current_media");
        if (media instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) media;
            title = text(m.get("title"), "");
            artist = text(m.get("artist"), "");
            imageUrl = text(m.get("image_url"), "");
            duration = dbl(m.get("duration"));
            elapsed = dbl(m.get("elapsed_time"));
            elapsedAt = System.currentTimeMillis();
        } else {
            clearMedia();
        }
        main.post(refreshNow);
    }

    private void clearMedia() {
        title = "";
        artist = "";
        imageUrl = "";
        duration = 0;
        elapsed = 0;
    }

    private void command(final String cmd) {
        final PluginHost h = host;
        if (h == null || playerId.isEmpty()) return;
        h.maCall("players/cmd/" + cmd, Collections.<String, Object>singletonMap("player_id", playerId), new PluginHost.CommandCallback() {
            @Override
            public void onResult(boolean ok, Object data, String error) {
                if (!ok) h.status("The " + cmd + " command failed: " + error, true);
            }
        });
    }

    // ---------- Window (main thread) ----------

    private void refresh() {
        if (ctx == null) return;
        // Music Assistant reports a paused player as idle, so idle with a title still counts as loaded media.
        boolean hasMedia = !title.isEmpty() && ("playing".equals(state) || "paused".equals(state) || "idle".equals(state));
        boolean dismissed = dismissedTitle != null && dismissedTitle.equals(title);
        boolean want = hasMedia && !hiddenByCommand && !dismissed && (showOnKiosk || !kioskInForeground()) && canDraw();
        if (!want) {
            detach();
            return;
        }
        attach();
        if (!attached) return;
        titleView.setText(title);
        artistView.setText(artist);
        artistView.setVisibility(artist.isEmpty() ? View.GONE : View.VISIBLE);
        playButton.setText("playing".equals(state) ? "⏸" : "▶");
        double pos = elapsed + ("playing".equals(state) ? (System.currentTimeMillis() - elapsedAt) / 1000.0 : 0);
        float frac = duration > 0 ? (float) Math.max(0, Math.min(1, pos / duration)) : 0f;
        LinearLayout.LayoutParams pp = (LinearLayout.LayoutParams) progress.getLayoutParams();
        if (Math.abs(pp.weight - frac) > 0.002f) {
            pp.weight = frac;
            progress.setLayoutParams(pp);
        }
        if (!imageUrl.equals(shownImage)) {
            shownImage = imageUrl;
            loadImage(imageUrl);
        }
    }

    private void attach() {
        if (attached) return;
        if (root == null) build();
        place();
        try {
            wm.addView(root, lp);
            attached = true;
        } catch (RuntimeException e) {
            PluginHost h = host;
            if (h != null) h.status("Could not show the player window: " + e.getMessage(), true);
        }
    }

    private void detach() {
        if (!attached) return;
        try {
            wm.removeView(root);
        } catch (RuntimeException ignored) {
            // Already removed
        }
        attached = false;
    }

    private void place() {
        if (lp == null) return;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        if (savedX < 0 || savedY < 0) {
            lp.x = dm.widthPixels - dp(400);
            lp.y = dm.heightPixels - dp(150);
        } else {
            lp.x = savedX;
            lp.y = savedY;
        }
        if (attached) {
            try {
                wm.updateViewLayout(root, lp);
            } catch (RuntimeException ignored) {
                // Removed in the meantime
            }
        }
    }

    private void build() {
        lp = new WindowManager.LayoutParams(
                dp(390), WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        root = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(235, 28, 28, 30));
        bg.setCornerRadius(dp(18));
        root.setBackground(bg);
        root.setElevation(dp(8));
        root.setPadding(dp(10), dp(10), dp(12), dp(8));

        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(row);

        art = new ImageView(ctx);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable artBg = new GradientDrawable();
        artBg.setColor(Color.argb(60, 255, 255, 255));
        artBg.setCornerRadius(dp(10));
        art.setBackground(artBg);
        art.setClipToOutline(true);
        row.addView(art, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tp.setMargins(dp(12), 0, dp(6), 0);
        row.addView(texts, tp);

        titleView = label(16, Color.WHITE, true);
        artistView = label(13, Color.argb(190, 255, 255, 255), false);
        texts.addView(titleView);
        texts.addView(artistView);

        row.addView(button("⏮", "previous", "Previous track"));
        playButton = button("▶", "play_pause", "Play or pause");
        row.addView(playButton);
        row.addView(button("⏭", "next", "Next track"));

        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setContentDescription("Close the window. The music keeps playing.");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        close.setTextColor(Color.argb(170, 255, 255, 255));
        close.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(30), dp(46));
        cp.setMargins(dp(2), 0, 0, 0);
        close.setLayoutParams(cp);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Only the window closes. It comes back with the next track or a Show command.
                dismissedTitle = title;
                refresh();
            }
        });
        row.addView(close);

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable track = new GradientDrawable();
        track.setColor(Color.argb(60, 255, 255, 255));
        track.setCornerRadius(dp(2));
        bar.setBackground(track);
        bar.setWeightSum(1f);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3));
        bp.setMargins(0, dp(8), 0, 0);
        column.addView(bar, bp);
        progress = new View(ctx);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(Color.WHITE);
        fill.setCornerRadius(dp(2));
        progress.setBackground(fill);
        bar.addView(progress, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0f));

        // Drag by the artwork or the title. The position is saved in the plugin's settings.
        View.OnTouchListener drag = new View.OnTouchListener() {
            private float startX, startY;
            private int baseX, baseY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = e.getRawX();
                        startY = e.getRawY();
                        baseX = lp.x;
                        baseY = lp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - startX;
                        float dy = e.getRawY() - startY;
                        if (Math.abs(dx) + Math.abs(dy) > dp(6)) moved = true;
                        lp.x = Math.max(0, baseX + Math.round(dx));
                        lp.y = Math.max(0, baseY + Math.round(dy));
                        try {
                            wm.updateViewLayout(root, lp);
                        } catch (RuntimeException ignored) {
                            // Removed in the meantime
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) savePosition();
                        return true;
                    default:
                        return false;
                }
            }
        };
        art.setOnTouchListener(drag);
        texts.setOnTouchListener(drag);
    }

    private void savePosition() {
        savedX = lp.x;
        savedY = lp.y;
        Map<String, Object> values = new HashMap<>();
        values.put("x", savedX);
        values.put("y", savedY);
        saveSettings(values);
    }

    private void saveSetting(String key, Object value) {
        saveSettings(Collections.singletonMap(key, value));
    }

    /** Saves the named settings off the calling thread, so the settings page shows what the window does. */
    private void saveSettings(final Map<String, Object> values) {
        final PluginHost h = host;
        if (h == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    h.updateSettings(values);
                } catch (RuntimeException | LinkageError e) {
                    // Older Kiosk Satellite versions: the change lasts until the plugin restarts.
                }
            }
        }).start();
    }

    private TextView label(int sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView button(String symbol, final String cmd, String description) {
        TextView b = new TextView(ctx);
        b.setText(symbol);
        b.setContentDescription(description);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(Color.argb(45, 255, 255, 255));
        b.setBackground(d);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(46), dp(46));
        p.setMargins(dp(4), 0, 0, 0);
        b.setLayoutParams(p);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                command(cmd);
            }
        });
        return b;
    }

    private void loadImage(final String url) {
        art.setImageDrawable(null);
        if (url.isEmpty()) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = download(url);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (url.equals(shownImage) && art != null) art.setImageBitmap(bmp);
                    }
                });
            }
        }).start();
    }

    private static Bitmap download(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            InputStream in = c.getInputStream();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            return BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------- Helpers ----------

    /** True while a Kiosk Satellite screen is in front. The window then stays hidden unless Show on kiosk is on. */
    private boolean kioskInForeground() {
        try {
            ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(info);
            return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private boolean canDraw() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx);
    }

    private static Context application() {
        try {
            Object app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Exception e) {
            return null;
        }
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    private static String text(Object o, String fallback) {
        return o instanceof String && !((String) o).isEmpty() ? (String) o : fallback;
    }

    private static double dbl(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0;
    }

    private static int number(Object o, int fallback) {
        return o instanceof Number ? ((Number) o).intValue() : fallback;
    }
}
