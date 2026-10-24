// The panel the game's Back gesture opens: the three things the engine reads
// at start (the resolution it renders at, the frame limit, the frame-rate
// overlay) and the way out of the game.
//
// It cannot be live, and it does not pretend to be: `read_render_scale()` and
// `load_run_config()` run once, in `android_main` before the engine starts, so
// what is written here is read on the next start. The panel says so. The one
// exception is the overlay, which the engine also toggles with L3 in the game.
//
// It is a plain view on top of the activity's decor view, above the native
// surface, so the engine keeps drawing behind it and nothing needs a new
// window, a permission or a manifest entry.
package port.android;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

final class GameMenu extends FrameLayout {
    /** What the panel cannot do itself: leave, hide, or end the game. */
    interface Host {
        void minimise();

        void quitGame();
    }

    private static final int MIN_SCALE = 25;
    private static final int SCALE_STEP = 5;
    private static final int[] LIMITS = {0, 15, 30, 45, 60, 75, 90, 105, 120, 0};
    private static final int DEFAULT_LIMIT = 60;

    private static final int C_SCRIM = 0xB3000000;
    private static final int C_CARD = 0xF2181310;
    private static final int C_BUTTON = 0xFF2A211A;
    private static final int C_GOLD = 0xFFC8A24A;
    private static final int C_GOLD_DIM = 0x66C8A24A;
    private static final int C_TEXT = 0xFFEDE6D8;
    private static final int C_DIM = 0xFF9C9285;
    private static final int C_RED = 0xFFE04B42;

    private final Activity host;
    private final Host callbacks;
    /** The game folder, from the launcher's `game_data.txt`; null if that is missing. */
    private final String folder;
    private final int[] screen;

    GameMenu(Activity host, Host callbacks) {
        super(host);
        this.host = host;
        this.callbacks = callbacks;
        this.folder = folderOf(host);
        this.screen = screenSize(host);

        setBackgroundColor(C_SCRIM);
        setVisibility(GONE);

        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(14));
        card.setBackground(round(C_CARD, C_GOLD_DIM, dp(16)));
        card.setClickable(true);  // a tap on the card is not a tap on the scrim

        card.addView(text("游戏设置", 20, C_GOLD, true));
        card.addView(text("分辨率与帧率在游戏启动时读取，改动在下次进入时生效；"
                + "帧率浮层也可以在游戏中按 L3 切换。", 11, C_DIM, false));

        if (folder == null) {
            card.addView(text("找不到游戏目录（启动器没有写 game_data.txt），帧率设置无处可写。",
                    12, C_RED, false));
        }

        // ----------------------------------------------------- the resolution
        int scale = readScale(host);
        TextView scaleLabel = text("渲染分辨率　" + scaleText(scale), 13, C_TEXT, false);
        card.addView(spaced(scaleLabel, 14, 2));
        card.addView(slider((100 - MIN_SCALE) / SCALE_STEP, (scale - MIN_SCALE) / SCALE_STEP,
                position -> {
                    int percent = MIN_SCALE + position * SCALE_STEP;
                    scaleLabel.setText("渲染分辨率　" + scaleText(percent));
                    writeScale(host, percent);
                }));

        // ------------------------------------------------------ the frame limit
        int limit = folder == null ? DEFAULT_LIMIT : Launcher.readConfig(folder, "fps_limit", DEFAULT_LIMIT);
        TextView limitLabel = text("帧率上限　" + limitText(limit), 13, C_TEXT, false);
        card.addView(spaced(limitLabel, 14, 2));
        card.addView(slider(LIMITS.length - 1, nearestLimit(limit), position -> {
            int fps = LIMITS[position];
            limitLabel.setText("帧率上限　" + limitText(fps));
            if (folder != null) {
                Launcher.writeConfig(folder, "fps_limit", fps, DEFAULT_LIMIT,
                        "the most frames a second the game draws; 0 for no limit.");
            }
        }));

        // ---------------------------------------------------------- the overlay
        boolean overlay = folder != null && Launcher.readConfig(folder, "fps", 0) != 0;
        card.addView(switchRow("显示帧数", "游戏中按 L3 也能开关", overlay, checked -> {
            if (folder != null) {
                Launcher.writeConfig(folder, "fps", checked ? 1 : 0, 0,
                        "the frame-rate overlay; L3 switches it in the game, and it is kept here.");
            }
        }));

        // ----------------------------------------------------------- the ways out
        LinearLayout top = new LinearLayout(host);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(button("返回游戏", true, v -> close()), weight());
        top.addView(button("最小化", false, v -> callbacks.minimise()), weight());
        card.addView(spaced(top, 16, 0));
        card.addView(spaced(button("退出游戏", false, v -> callbacks.quitGame()), 8, 0));

        ScrollView scroll = new ScrollView(host);
        scroll.setFillViewport(true);
        scroll.addView(card, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setOnClickListener(v -> close());  // a tap beside the card closes it
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(24), dp(24), dp(24), dp(24));
        addView(scroll, lp);

        // The panel is over the system bars too: keep the card inside them.
        setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    boolean opened() {
        return getVisibility() == VISIBLE;
    }

    void open() {
        setVisibility(VISIBLE);
        bringToFront();
    }

    void close() {
        setVisibility(GONE);
    }

    // ------------------------------------------------------------- the files

    private static String folderOf(Activity host) {
        File root = host.getExternalFilesDir(null);
        if (root == null) return null;
        try {
            String path = Launcher.read(new File(root, "game_data.txt"));
            return path.isEmpty() ? null : path;
        } catch (IOException e) {
            return null;
        }
    }

    /** The percentage the launcher last wrote, or the screen's own. */
    private static int readScale(Activity host) {
        File root = host.getExternalFilesDir(null);
        if (root == null) return 100;
        try {
            int percent = (int) Double.parseDouble(Launcher.read(new File(root, "render_scale.txt")));
            return Math.max(MIN_SCALE, Math.min(100, percent));
        } catch (IOException | NumberFormatException e) {
            return 100;
        }
    }

    private static void writeScale(Activity host, int percent) {
        File root = host.getExternalFilesDir(null);
        if (root == null) return;
        try (OutputStream out = new FileOutputStream(new File(root, "render_scale.txt"))) {
            out.write((percent + "\n").getBytes("UTF-8"));
        } catch (IOException e) {
            // The engine then renders at the screen's own resolution.
        }
    }

    // ------------------------------------------------------------- the views

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private static GradientDrawable round(int color, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        if (stroke != 0) shape.setStroke(1, stroke);
        return shape;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(host);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(3), 1f);
        if (bold) view.getPaint().setFakeBoldText(true);
        return view;
    }

    private View spaced(View view, int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(top), 0, dp(bottom));
        view.setLayoutParams(lp);
        return view;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private TextView button(String label, boolean primary, View.OnClickListener click) {
        TextView view = text(label, 15, primary ? 0xFF1A1410 : C_TEXT, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(14), dp(13), dp(14), dp(13));
        view.setBackground(round(primary ? C_GOLD : C_BUTTON, primary ? 0 : C_GOLD_DIM, dp(12)));
        view.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        view.setLayoutParams(lp);
        return view;
    }

    private interface Label {
        void of(int position);
    }

    private SeekBar slider(int max, int position, Label label) {
        SeekBar seek = new SeekBar(host);
        seek.setMax(max);
        seek.setProgress(position);
        seek.setProgressTintList(ColorStateList.valueOf(C_GOLD));
        seek.setThumbTintList(ColorStateList.valueOf(C_GOLD));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) label.of(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        return seek;
    }

    private interface Toggle {
        void of(boolean checked);
    }

    private View switchRow(String label, String hint, boolean on, Toggle changed) {
        LinearLayout line = new LinearLayout(host);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(10), 0, dp(10));

        LinearLayout words = new LinearLayout(host);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(label, 15, C_TEXT, true));
        words.addView(text(hint, 11, C_DIM, false));

        Switch sw = new Switch(host);
        sw.setChecked(on);
        sw.setThumbTintList(ColorStateList.valueOf(C_GOLD));
        sw.setTrackTintList(ColorStateList.valueOf(0x66C8A24A));
        sw.setOnCheckedChangeListener((b, checked) -> changed.of(checked));

        line.addView(words, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(sw);
        return line;
    }

    // ------------------------------------------------------------- the figures

    private int[] screenSize(Activity activity) {
        Rect bounds = activity.getWindowManager().getMaximumWindowMetrics().getBounds();
        int w = bounds.width();
        int h = bounds.height();
        return new int[] {Math.max(w, h), Math.min(w, h)};
    }

    private String scaleText(int percent) {
        int w = percent >= 100 ? screen[0] : scaled(screen[0], percent);
        int h = percent >= 100 ? screen[1] : scaled(screen[1], percent);
        return percent + "%　" + w + " × " + h + (percent == 100 ? "（屏幕原生）" : "");
    }

    /** What the engine renders at for a percentage, the way the launcher works it out. */
    private static int scaled(int size, int percent) {
        if (percent >= 100) return size;
        float factor = (float) Double.parseDouble(String.format(Locale.ROOT, "%.6f", 100.0 / percent));
        return (int) (size * (1.0f / factor));
    }

    private static String limitText(int fps) {
        return fps == 0 ? "不限制" : fps + " 帧/秒";
    }

    private static int nearestLimit(int limit) {
        if (limit == 0) return LIMITS.length - 1;
        int at = 0;
        for (int i = 1; i < LIMITS.length - 1; ++i) {
            if (Math.abs(LIMITS[i] - limit) < Math.abs(LIMITS[at] - limit)) at = i;
        }
        return at;
    }
}
