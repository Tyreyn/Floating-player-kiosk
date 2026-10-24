// SPDX-License-Identifier: Apache-2.0
package pl.kuchnia.kiosk.odtwarzacz;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Pływający odtwarzacz nad innymi aplikacjami. Wbudowane nakładki SDK żyją tylko w oknie Kiosk Satellite i znikają,
 * gdy kiosk schodzi w tło, więc okienko jest zwykłym oknem systemowym (TYPE_APPLICATION_OVERLAY) – wtyczka działa w
 * procesie KS, który ma uprawnienie „wyświetlanie nad innymi aplikacjami”. Dane i sterowanie: Music Assistant przez
 * połączenie kiosku (host.maCall / maSubscribe).
 */
public final class PlywajacyOdtwarzacz implements KioskPlugin {
    private static final String SUB = "odtwarzacz";

    private final Handler main = new Handler(Looper.getMainLooper());
    private PluginHost host;
    private Context ctx;
    private WindowManager wm;

    private String playerId = "up3cd38586";
    private boolean showOnKiosk;
    private boolean hiddenByUser;
    private int savedX = -1;
    private int savedY = -1;

    // stan odtwarzacza (wątek wtyczki pisze, główny czyta – tylko proste pola)
    private volatile String state = "idle";
    private volatile String title = "";
    private volatile String artist = "";
    private volatile String imageUrl = "";
    private volatile double duration;
    private volatile double elapsed;
    private volatile long elapsedAt;

    // widok (tylko wątek główny)
    private FrameLayout root;
    private WindowManager.LayoutParams lp;
    private ImageView art;
    private TextView titleView;
    private TextView artistView;
    private TextView playButton;
    private View progress;
    private String shownImage = "";
    private boolean attached;
    private boolean running;

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
            host.status("Brak kontekstu aplikacji – okienko nie może się pojawić.", true);
            return;
        }
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        apply(settings);
        follow();
        running = true;
        main.post(tick);
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) {
            host.status("Kiosk Satellite nie ma uprawnienia „wyświetlanie nad innymi aplikacjami”.", true);
        } else {
            host.status("Gotowe – okienko pokaże się nad innymi aplikacjami, gdy gra muzyka.", false);
        }
    }

    @Override
    public void configure(Map<String, Object> settings) throws Exception {
        String before = playerId;
        apply(settings);
        if (!before.equals(playerId)) follow();
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
        if ("hide".equals(command)) hiddenByUser = true;
        else if ("show".equals(command)) hiddenByUser = false;
        main.post(refreshNow);
    }

    @Override
    public void onEvent(String event, Map<String, Object> payload) throws Exception {
        if (!("ks.ma.subscription." + SUB).equals(event)) return;
        if (!"event".equals(payload.get("type"))) {
            if ("reconnected".equals(payload.get("type"))) load();
            return;
        }
        Object data = payload.get("data");
        if (data instanceof Map) player((Map<?, ?>) data);
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

    // ---------- dane ----------

    private void apply(Map<String, Object> settings) {
        Object p = settings.get("playerId");
        if (p instanceof String && !((String) p).trim().isEmpty()) playerId = ((String) p).trim();
        showOnKiosk = Boolean.TRUE.equals(settings.get("showOnKiosk"));
        savedX = number(settings.get("x"), -1);
        savedY = number(settings.get("y"), -1);
    }

    private void follow() {
        if (host == null) return;
        host.maUnsubscribe(SUB);
        Map<String, Object> filter = new HashMap<>();
        filter.put("events", Arrays.asList("player_updated"));
        filter.put("objectId", playerId);
        final PluginHost h = host;
        host.maSubscribe(SUB, filter, new PluginHost.CommandCallback() {
            @Override
            public void onResult(boolean ok, Object data, String error) {
                if (!ok) h.status("Subskrypcja Music Assistant nie powiodła się: " + error, true);
            }
        });
        load();
    }

    private void load() {
        if (host == null) return;
        host.maCall("players/get", Collections.<String, Object>singletonMap("player_id", playerId), new PluginHost.CommandCallback() {
            @Override
            public void onResult(boolean ok, Object data, String error) {
                if (ok && data instanceof Map) player((Map<?, ?>) data);
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
            title = "";
            artist = "";
            imageUrl = "";
            duration = 0;
        }
        main.post(refreshNow);
    }

    private void command(String cmd) {
        if (host == null) return;
        final PluginHost h = host;
        h.maCall("players/cmd/" + cmd, Collections.<String, Object>singletonMap("player_id", playerId), new PluginHost.CommandCallback() {
            @Override
            public void onResult(boolean ok, Object data, String error) {
                if (!ok) h.status("Polecenie " + cmd + " nie powiodło się: " + error, true);
            }
        });
    }

    // ---------- widok (wątek główny) ----------

    private void refresh() {
        if (ctx == null) return;
        boolean hasMedia = !title.isEmpty() && ("playing".equals(state) || "paused".equals(state) || "idle".equals(state));
        boolean kioskOnTop = kioskInForeground();
        boolean want = hasMedia && !hiddenByUser && (showOnKiosk || !kioskOnTop) && canDraw();
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
            if (host != null) host.status("Nie udało się pokazać okienka: " + e.getMessage(), true);
        }
    }

    private void detach() {
        if (!attached) return;
        try {
            wm.removeView(root);
        } catch (RuntimeException ignored) {
            // okno już usunięte
        }
        attached = false;
    }

    private void place() {
        if (lp == null) return;
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        if (savedX < 0 || savedY < 0) {
            lp.x = dm.widthPixels - dp(380);
            lp.y = dm.heightPixels - dp(150);
        } else {
            lp.x = savedX;
            lp.y = savedY;
        }
        if (attached) {
            try {
                wm.updateViewLayout(root, lp);
            } catch (RuntimeException ignored) {
                // okno zniknęło w międzyczasie
            }
        }
    }

    private void build() {
        lp = new WindowManager.LayoutParams(
                dp(360), WindowManager.LayoutParams.WRAP_CONTENT,
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

        row.addView(button("⏮", "previous"));
        playButton = button("▶", "play_pause");
        row.addView(playButton);
        row.addView(button("⏭", "next"));

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

        // Przeciąganie za okładkę albo tytuł; pozycja zapisuje się w ustawieniach wtyczki.
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
                            // okno zniknęło
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
        final PluginHost h = host;
        if (h == null) return;
        final Map<String, Object> values = new HashMap<>();
        values.put("x", savedX);
        values.put("y", savedY);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    h.updateSettings(values);
                } catch (RuntimeException | LinkageError e) {
                    // starsza wersja KS – pozycja zostaje do restartu
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

    private TextView button(String symbol, final String cmd) {
        TextView b = new TextView(ctx);
        b.setText(symbol);
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

    // ---------- pomocnicze ----------

    /** Kiosk Satellite na wierzchu (jego ekran widoczny) – wtedy okienko się chowa. */
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
