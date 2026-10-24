// The Android build's launcher: the screen that stands between the icon and
// the engine.
//
// The game's files are never in the APK. The player keeps `pak.zip` and
// `music/` in a folder of their choosing; this asks for it once with the
// system's folder picker, remembers it, checks it on every start, and says
// plainly what is missing. The engine (GameActivity, libtorchlight.so) then
// reads it by path, which is why it needs "All files access": a folder chosen
// through the picker is otherwise a content URI, and the engine opens files.
//
// It also unpacks what the APK does carry, which is part of the program and
// not of the game, into the app's own files: OGRE's shader library and the
// port's own interface pack (`ui_pack/`, what a PC pak is drawn with when no
// `console_ui/` is beside it).
//
// The screen itself is four pages under a bottom bar -- 首页 (the game folder
// and Play), 设置 (resolution, frame limit, cache), 补丁 (the engine's
// config.txt switches) and 关于 (versions, signature, paths) -- so every
// setting can be looked at again after the first start instead of being asked
// once in a chain of dialogs. The interface is Chinese; the game's own text is
// not touched by this file.
package port.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.res.AssetManager;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;

public final class Launcher extends Activity {
    private static final int PICK_FOLDER = 1;

    private static final String PREFS = "launcher";
    private static final String KEY_FOLDER = "game_data";
    private static final String KEY_NO_MUSIC = "play_without_music";
    private static final String KEY_SCALE = "render_scale";
    private static final String KEY_LIMIT = "fps_limit";
    private static final String KEY_OVERLAY = "fps_overlay";
    private static final String KEY_CAMERA = "free_camera";

    /** The resolutions offered, in percent of the screen's width and height: the engine's range. */
    private static final int MIN_SCALE = 25;
    private static final int SCALE_STEP = 5;
    /** The frame limits offered; 0, at both ends of the slider, is none. */
    private static final int[] LIMITS = {0, 15, 30, 45, 60, 75, 90, 105, 120, 0};
    /** The engine's own default on Android, when config.txt does not say. */
    private static final int DEFAULT_LIMIT = 60;

    private static final int PAGE_HOME = 0;
    private static final int PAGE_SETTINGS = 1;
    private static final int PAGE_PATCHES = 2;
    private static final int PAGE_ABOUT = 3;

    /** Which build this is: the About page shows it, and `strings` on the installed dex finds it. */
    private static final String BUILD = "zh4";

    private static final String[] TAB_ICONS = {"⌂", "⚙", "✦", "ⓘ"};
    private static final String[] TAB_NAMES = {"首页", "设置", "补丁", "关于"};

    // A dark room and a lamp: the game's own palette, which the bar and the
    // cards keep so the launcher does not flash white before the engine does.
    private static final int C_BG = 0xFF0C0A09;
    private static final int C_CARD = 0xFF181310;
    private static final int C_CARD_HI = 0xFF221A15;
    private static final int C_GOLD = 0xFFC8A24A;
    private static final int C_GOLD_DIM = 0x66C8A24A;
    private static final int C_TEXT = 0xFFEDE6D8;
    private static final int C_DIM = 0xFF9C9285;
    private static final int C_RED = 0xFFE04B42;
    private static final int C_GREEN = 0xFF8FBF6A;

    /**
     * The game is running in this process (in the background, or this activity was started over
     * it): it is brought back rather than started again. The process ends with the game.
     */
    private static boolean gameRunning;

    /**
     * How many GameActivity instances are up. One for the game, and briefly two
     * when the in-game panel restarts it: the old one's onDestroy must not
     * report that the game has gone.
     */
    private static int games;

    /** GameActivity came up: from now on the icon returns to the game, not to this screen. */
    static void gameStarted() {
        games++;
        gameRunning = true;
    }

    /** GameActivity went away, unless another has already started in its place. */
    static void gameEnded() {
        games = Math.max(0, games - 1);
        gameRunning = games > 0;
    }

    /** The game has been started; nothing more to do here. */
    private boolean started;

    private LinearLayout content;
    private LinearLayout bar;
    private int page = PAGE_HOME;
    /** A switch being set from code must not be read as the player changing it. */
    private boolean setting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setBackground(round(C_CARD, 0, 0));
        bar.setPadding(0, dp(6), 0, dp(6));
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < TAB_NAMES.length; ++i) {
            final int index = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(6), dp(4), dp(6), dp(4));
            item.setTag("tab" + i);
            TextView icon = text(TAB_ICONS[i], 20, C_DIM, false);
            icon.setGravity(Gravity.CENTER);
            TextView name = text(TAB_NAMES[i], 11, C_DIM, false);
            name.setGravity(Gravity.CENTER);
            item.addView(icon);
            item.addView(name);
            item.setOnClickListener(v -> select(index));
            bar.addView(item, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        // The system bars sit over the top of the screen and under the bar:
        // give the content and the bar their room.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        setContentView(root);
        select(PAGE_HOME);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gameRunning) {
            // The game is behind this screen: go back to it, as the icon does.
            started = true;
            startActivity(new Intent(this, GameActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            finish();
            return;
        }
        // Back from the "all files access" screen, or from the picker: what
        // was granted has to be shown.
        if (content != null) select(page);
    }

    // ---------------------------------------------------------------- pages

    private void select(int index) {
        page = index;
        for (int i = 0; i < bar.getChildCount(); ++i) {
            LinearLayout item = (LinearLayout) bar.getChildAt(i);
            boolean on = i == index;
            ((TextView) item.getChildAt(0)).setTextColor(on ? C_GOLD : C_DIM);
            ((TextView) item.getChildAt(1)).setTextColor(on ? C_GOLD : C_DIM);
            ((TextView) item.getChildAt(1)).getPaint().setFakeBoldText(on);
            item.invalidate();
        }
        content.removeAllViews();
        if (index == PAGE_HOME) home();
        else if (index == PAGE_SETTINGS) settings();
        else if (index == PAGE_PATCHES) patches();
        else about();
    }

    private void home() {
        header("火炬之光 · 重燃", "Torchlight Rekindled · 安卓移植版");

        String folder = prefs().getString(KEY_FOLDER, null);
        File dir = folder == null ? null : new File(folder);
        boolean storage = Environment.isExternalStorageManager();
        boolean pak = dir != null && (new File(dir, "pak.zip").isFile() || new File(dir, "Pak.zip").isFile());
        File music = dir == null ? null : new File(dir, "music");
        boolean hasMusic = music != null && music.isDirectory();

        if (!storage) {
            LinearLayout warn = card();
            warn.addView(text("需要「所有文件访问」权限", 16, C_GOLD, true));
            warn.addView(text("游戏引擎按路径直接读取 pak.zip 和 music/，而通过系统选择器选中的目录"
                    + "默认只是一个内容 URI，引擎打不开。开启该权限后，本程序才能读到你自己那份游戏文件。",
                    13, C_DIM, false));
            warn.addView(button("去开启权限", true, v -> askStorage()));
            content.addView(warn);
        }

        LinearLayout status = card();
        status.addView(text("游戏状态", 15, C_GOLD, true));
        row(status, "存储权限", storage ? "已授权" : "未授权", storage);
        row(status, "游戏目录", folder == null ? "未选择" : folder, folder != null);
        row(status, "pak.zip", dir == null ? "—" : (pak ? "已找到 · " + mb(new File(dir,
                new File(dir, "pak.zip").isFile() ? "pak.zip" : "Pak.zip").length()) : "缺失"), pak);
        row(status, "music/", dir == null ? "—" : (hasMusic ? "已找到 · "
                + count(music) + " 首" : "缺失（可无音乐游玩）"), hasMusic);
        content.addView(status);

        LinearLayout actions = card();
        actions.addView(button(folder == null ? "选择游戏目录" : "更换游戏目录", false,
                v -> pickFolder()));
        actions.addView(button("重新校验文件", false, v -> {
            select(PAGE_HOME);
            toast("已重新校验");
        }));
        content.addView(actions);

        if (gameRunning) {
            content.addView(button("返回游戏", true, v -> {
                startActivity(new Intent(this, GameActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                finish();
            }));
        } else {
            content.addView(button("开始游戏", true, v -> play()));
        }

        LinearLayout help = card();
        help.addView(text("说明", 15, C_GOLD, true));
        help.addView(text("本程序不含游戏本体。请把你自己那份《火炬之光》的 pak.zip（Xbox 360 版）"
                + "和 music/ 文件夹放在同一个目录里，例如 Download/Torchlight，然后在上面选择该目录。",
                13, C_DIM, false));
        content.addView(help);
    }

    private void settings() {
        header("设置", "修改后立即生效；分辨率在下次进入游戏时生效");

        String folder = prefs().getString(KEY_FOLDER, null);
        if (folder == null) {
            LinearLayout hint = card();
            hint.addView(text("还没有选择游戏目录", 15, C_GOLD, true));
            hint.addView(text("这些开关先记在本程序里；选好目录、进游戏时会写进游戏目录的 config.txt。",
                    12, C_DIM, false));
            content.addView(hint);
        }
        int[] screen = screenSize();
        int scale = Math.max(MIN_SCALE, Math.min(100, prefs().getInt(KEY_SCALE, 100)));
        int limit = limitNow(folder);

        LinearLayout render = card();
        render.addView(text("渲染分辨率", 15, C_GOLD, true));
        TextView scaleLabel = text(scaleText(screen, scale), 13, C_TEXT, false);
        render.addView(scaleLabel);
        SeekBar scaleBar = slider((100 - MIN_SCALE) / SCALE_STEP, (scale - MIN_SCALE) / SCALE_STEP,
                p -> {
                    int percent = MIN_SCALE + p * SCALE_STEP;
                    scaleLabel.setText(scaleText(screen, percent));
                    prefs().edit().putInt(KEY_SCALE, percent).apply();
                });
        render.addView(scaleBar);
        render.addView(text("百分比越低越省电、越流畅；100% 即屏幕原生分辨率。", 12, C_DIM, false));
        content.addView(render);

        LinearLayout frames = card();
        frames.addView(text("帧率上限", 15, C_GOLD, true));
        TextView limitLabel = text(limitText(limit), 13, C_TEXT, false);
        frames.addView(limitLabel);
        int at = nearestLimit(limit);
        SeekBar limitBar = slider(LIMITS.length - 1, at, p -> {
            int fps = LIMITS[p];
            limitLabel.setText(limitText(fps));
            prefs().edit().putInt(KEY_LIMIT, fps).apply();
            if (folder != null) writeConfig(folder, "fps_limit", fps, DEFAULT_LIMIT,
                    "the most frames a second the game draws; 0 for no limit.");
        });
        frames.addView(limitBar);
        frames.addView(text("滑到两端为不限制；写入游戏目录的 config.txt。", 12, C_DIM, false));
        content.addView(frames);

        LinearLayout cache = card();
        cache.addView(text("缓存与文件", 15, C_GOLD, true));
        cache.addView(text("首次启动会把 APK 内的着色器库（RTShaderLib）和界面包（ui_pack）解压到"
                + "本程序私有目录，约 " + mb(unpackedSize()) + "。", 13, C_DIM, false));
        cache.addView(button("清理解压缓存", false, v -> {
            delete(new File(getFilesDir(), "RTShaderLib"));
            delete(new File(getFilesDir(), "ui_pack"));
            new File(getFilesDir(), "RTShaderLib.stamp").delete();
            toast("已清理，下次启动重新解压");
            select(PAGE_SETTINGS);
        }));
        cache.addView(button(folder == null ? "选择游戏目录" : "更换游戏目录", false, v -> pickFolder()));
        content.addView(cache);
    }

    private void patches() {
        header("补丁", "引擎读取的 config.txt 开关，改完进游戏即生效");

        String folder = prefs().getString(KEY_FOLDER, null);
        if (folder == null) {
            LinearLayout hint = card();
            hint.addView(text("还没有选择游戏目录", 15, C_GOLD, true));
            hint.addView(text("这些开关先记在本程序里；选好目录、进游戏时会写进游戏目录的 config.txt。",
                    12, C_DIM, false));
            content.addView(hint);
        }
        boolean overlay = flagNow(folder, "fps", KEY_OVERLAY, false);
        boolean camera = flagNow(folder, "free_camera", KEY_CAMERA, false);

        LinearLayout on = card();
        on.addView(text("可用开关", 15, C_GOLD, true));
        switchRow(on, "帧率浮层", "左上角显示实时帧率；游戏中 L3 也能切换", overlay, checked -> {
            prefs().edit().putBoolean(KEY_OVERLAY, checked).apply();
            if (folder != null) writeConfig(folder, "fps", checked ? 1 : 0, 0,
                    "the frame-rate overlay; L3 switches it in the game, and it is kept here.");
            toast(checked ? "已开启帧率浮层" : "已关闭帧率浮层");
        });
        switchRow(on, "自由相机", "右摇杆转视角，双指捏合缩放，R3 复位", camera, checked -> {
            prefs().edit().putBoolean(KEY_CAMERA, checked).apply();
            if (folder != null) writeConfig(folder, "free_camera", checked ? 1 : 0, 0,
                    "the right stick turns the camera and R3 resets it; a pinch zooms.");
            toast(checked ? "已开启自由相机" : "已关闭自由相机");
        });
        content.addView(on);

        LinearLayout todo = card();
        todo.addView(text("尚未实现", 15, C_GOLD, true));
        todo.addView(text("下面这些开关要在引擎（libtorchlight.so）里改，不在启动器能碰的范围内：",
                12, C_DIM, false));
        disabled(todo, "跳过开场动画");
        disabled(todo, "X 键跳过任意过场视频");
        disabled(todo, "修复启动黑屏");
        disabled(todo, "关闭 SSAO");
        content.addView(todo);
    }

    private void about() {
        header("关于", "社区汉化 · 启动器重做");

        LinearLayout app = card();
        app.addView(text("程序", 15, C_GOLD, true));
        row(app, "名称", "Torchlight Rekindled（汉化版）", true);
        row(app, "版本", versionName() + "（versionCode " + versionCode() + "）", true);
        row(app, "构建", "汉化版 " + BUILD, true);
        row(app, "包名", getPackageName(), true);
        row(app, "启动器", "启动器已重做：底部导航 · 中文界面", true);
        row(app, "签名 SHA-256", signature(), true);
        content.addView(app);

        LinearLayout engine = card();
        engine.addView(text("引擎与数据", 15, C_GOLD, true));
        File lib = new File(getApplicationInfo().nativeLibraryDir, "libtorchlight.so");
        row(engine, "引擎库", (lib.isFile() ? mb(lib.length()) : "缺失") + " · "
                + getApplicationInfo().nativeLibraryDir, lib.isFile());
        String folder = prefs().getString(KEY_FOLDER, null);
        row(engine, "游戏目录", folder == null ? "未选择" : folder, folder != null);
        row(engine, "解压缓存", mb(unpackedSize()), true);
        content.addView(engine);

        LinearLayout credit = card();
        credit.addView(text("致谢", 15, C_GOLD, true));
        credit.addView(text("上游项目：github.com/GreenSwampDweller/torchlight-rekindled\n"
                + "引擎基于 OGRE 重写，不含任何游戏数据；游戏本体版权归 Runic Games 所有，"
                + "请使用你自己合法持有的副本。", 12, C_DIM, false));
        content.addView(credit);
    }

    // ------------------------------------------------------------ the flow

    /** Play, or the question that stands in the way of it. */
    private void play() {
        if (!Environment.isExternalStorageManager()) {
            askStorage();
            return;
        }
        String folder = prefs().getString(KEY_FOLDER, null);
        if (folder == null) {
            toast("请先选择游戏目录");
            pickFolder();
            return;
        }
        File dir = new File(folder);
        if (!new File(dir, "pak.zip").isFile() && !new File(dir, "Pak.zip").isFile()) {
            new AlertDialog.Builder(this)
                    .setTitle("找不到 pak.zip")
                    .setMessage("目录里没有 pak.zip：\n\n  " + folder + "\n\n请选择放着 pak.zip"
                            + "（Xbox 360 版）和 music/ 的那个目录。")
                    .setPositiveButton("重新选择", (d, w) -> pickFolder())
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        if (!new File(dir, "music").isDirectory()
                && !folder.equals(prefs().getString(KEY_NO_MUSIC, ""))) {
            askAboutMusic(folder);
            return;
        }
        start(folder);
    }

    private void pickFolder() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER);
    }

    private void askStorage() {
        new AlertDialog.Builder(this)
                .setTitle("允许访问游戏文件")
                .setMessage("游戏要从你选择的目录里按路径读取 pak.zip 和 music/。安卓只有"
                        + "「所有文件访问」权限才允许这样读目录。\n\n在接下来的页面里为本程序打开该开关，"
                        + "然后返回这里。")
                .setPositiveButton("去设置", (d, w) -> startActivity(new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()))))
                .setNegativeButton("取消", null)
                .show();
    }

    private void askAboutMusic(String folder) {
        new AlertDialog.Builder(this)
                .setTitle("缺少 music/ 文件夹")
                .setMessage("目录里没有 music/：\n\n  " + folder + "\n\n关卡主题曲不在 pak.zip 里。"
                        + "请把游戏里的 music/ 文件夹放到它旁边，或者选择无音乐游玩。")
                .setPositiveButton("无音乐游玩", (d, w) -> {
                    prefs().edit().putString(KEY_NO_MUSIC, folder).apply();
                    start(folder);
                })
                .setNeutralButton("重新选择", (d, w) -> pickFolder())
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FOLDER) return;
        if (result == RESULT_OK && data != null && data.getData() != null) {
            String path = pathOf(data.getData());
            if (path != null) {
                prefs().edit().putString(KEY_FOLDER, path).remove(KEY_NO_MUSIC).apply();
                toast("已选择：" + path);
            } else {
                toast("这个目录读不到路径，请换一个（例如内部存储的 Download 下）");
            }
        }
        select(PAGE_HOME);
    }

    private void start(String folder) {
        try {
            unpackShaderLibrary();
        } catch (IOException e) {
            new AlertDialog.Builder(this)
                    .setTitle("无法解压程序自带文件")
                    .setMessage("着色器库写入本程序私有目录失败（" + e.getMessage()
                            + "）。请清理出一些空间后重试。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        applyStored(folder);
        File app = getExternalFilesDir(null);
        try (OutputStream out = new FileOutputStream(new File(app, "game_data.txt"))) {
            out.write((folder + "\n").getBytes("UTF-8"));
        } catch (IOException e) {
            // The engine then reads the app's own folder, and says what it does not find there.
        }
        try (OutputStream out = new FileOutputStream(new File(app, "render_scale.txt"))) {
            out.write((prefs().getInt(KEY_SCALE, 100) + "\n").getBytes("UTF-8"));
        } catch (IOException e) {
            // The engine then renders at the screen's own resolution.
        }
        started = true;
        startActivity(new Intent(this, GameActivity.class));
        finish();
    }

    // ------------------------------------------------------------- reading

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /** The frame limit in force: the folder's config.txt, else what the settings last said. */
    private int limitNow(String folder) {
        if (folder != null) {
            try {
                File file = new File(folder, "config.txt");
                if (file.isFile()) return readConfig(folder, "fps_limit", DEFAULT_LIMIT);
            } catch (RuntimeException e) {
                // fall through to the stored value
            }
        }
        return prefs().getInt(KEY_LIMIT, DEFAULT_LIMIT);
    }

    /** A config.txt 0/1 switch, else the stored value, else `fallback`. */
    private boolean flagNow(String folder, String key, String pref, boolean fallback) {
        if (folder != null && new File(folder, "config.txt").isFile()) {
            return readConfig(folder, key, fallback ? 1 : 0) != 0;
        }
        return prefs().getBoolean(pref, fallback);
    }

    /** The screen's size in landscape, which is how the game is played. */
    private int[] screenSize() {
        Rect bounds = getWindowManager().getMaximumWindowMetrics().getBounds();
        int w = bounds.width();
        int h = bounds.height();
        return new int[] {Math.max(w, h), Math.min(w, h)};
    }

    /**
     * What the engine renders at for a percentage: the screen's size times 1/factor, the factor
     * written and read back as the engine gives it to OGRE, so the figure shown is the frame's.
     */
    private static int scaled(int size, int percent) {
        if (percent >= 100) return size;
        float factor = (float) Double.parseDouble(String.format(Locale.ROOT, "%.6f", 100.0 / percent));
        return (int) (size * (1.0f / factor));
    }

    private static String scaleText(int[] screen, int percent) {
        return percent + "%　" + scaled(screen[0], percent) + " × " + scaled(screen[1], percent)
                + (percent == 100 ? "（屏幕原生）" : "");
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

    /** Whether config.txt already names this key: the engine's own value is kept when it does. */
    static boolean hasConfig(String folder, String wanted) {
        try {
            for (String line : read(new File(folder, "config.txt")).split("\r?\n")) {
                String[] kv = line.split("#", 2)[0].split("=", 2);
                if (kv.length >= 2 && kv[0].trim().equalsIgnoreCase(wanted)) return true;
            }
        } catch (IOException e) {
            // No file yet.
        }
        return false;
    }

    /**
     * The switches this screen owns, written into a config.txt that does not name them yet: the
     * engine writes its own on the first start, and from then on it is the one that knows (L3
     * toggles the overlay inside the game, and that has to survive the next start).
     */
    private void applyStored(String folder) {
        SharedPreferences p = prefs();
        if (p.contains(KEY_LIMIT) && !hasConfig(folder, "fps_limit")) {
            writeConfig(folder, "fps_limit", p.getInt(KEY_LIMIT, DEFAULT_LIMIT), DEFAULT_LIMIT,
                    "the most frames a second the game draws; 0 for no limit.");
        }
        if (p.contains(KEY_OVERLAY) && !hasConfig(folder, "fps")) {
            writeConfig(folder, "fps", p.getBoolean(KEY_OVERLAY, false) ? 1 : 0, 0,
                    "the frame-rate overlay; L3 switches it in the game, and it is kept here.");
        }
        if (p.contains(KEY_CAMERA) && !hasConfig(folder, "free_camera")) {
            writeConfig(folder, "free_camera", p.getBoolean(KEY_CAMERA, false) ? 1 : 0, 0,
                    "the right stick turns the camera and R3 resets it; a pinch zooms.");
        }
    }

    /**
     * A number line of `config.txt` in the game folder, as the engine reads it (`fps_limit`, or
     * `0`/`1` for a switch): `fallback`, the engine's own default, when it says nothing.
     */
    static int readConfig(String folder, String wanted, int fallback) {
        try {
            for (String line : read(new File(folder, "config.txt")).split("\r?\n")) {
                String[] kv = line.split("#", 2)[0].split("=", 2);
                if (kv.length < 2 || !kv[0].trim().equalsIgnoreCase(wanted)) continue;
                int value = Integer.parseInt(kv[1].trim());
                if (value >= 0 && value <= 1000) return value;
            }
        } catch (IOException | NumberFormatException e) {
            // No file yet, or a line the engine would pass over too.
        }
        return fallback;
    }

    /**
     * One line of `config.txt` set, the rest of the file kept; a key it lacks is added under its
     * comment. With no file yet, and the engine's default asked for, none is made: the engine
     * writes its own on the first start.
     */
    static void writeConfig(String folder, String wanted, int value, int fallback,
            String comment) {
        File file = new File(folder, "config.txt");
        String text;
        try {
            text = read(file);
        } catch (IOException e) {
            if (value == fallback) return;
            text = "";
        }
        StringBuilder out = new StringBuilder();
        boolean set = false;
        String[] lines = text.isEmpty() ? new String[0] : text.split("\r?\n", -1);
        // The piece after the last line break is not a line.
        int count = lines.length > 0 && lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;
        for (int i = 0; i < count; ++i) {
            String line = lines[i];
            String key = line.split("#", 2)[0].split("=", 2)[0].trim();
            if (!set && key.equalsIgnoreCase(wanted) && line.contains("=")) {
                out.append(wanted).append('=').append(value).append('\n');
                set = true;
            } else {
                out.append(line).append('\n');
            }
        }
        if (!set) {
            out.append("# ").append(wanted).append(": ").append(comment).append('\n');
            out.append(wanted).append('=').append(value).append('\n');
        }
        try (OutputStream o = new FileOutputStream(file)) {
            o.write(out.toString().getBytes("UTF-8"));
        } catch (IOException e) {
            // The engine then keeps what the file said.
        }
    }

    /**
     * A folder the picker returned, as a path the engine can open: `primary:Download/x` is the
     * phone's own storage, `XXXX-XXXX:x` an SD card's. Null for anything else.
     */
    private static String pathOf(Uri tree) {
        String id = DocumentsContract.getTreeDocumentId(tree);
        int colon = id.indexOf(':');
        if (colon < 0) return null;
        String volume = id.substring(0, colon);
        String rest = id.substring(colon + 1);
        String root = volume.equals("primary")
                ? Environment.getExternalStorageDirectory().getPath()
                : "/storage/" + volume;
        return rest.isEmpty() ? root : root + "/" + rest;
    }

    // ------------------------------------------------------------ the views

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
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(3), 1f);
        if (bold) view.getPaint().setFakeBoldText(true);
        return view;
    }

    private void header(String title, String subtitle) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(20), dp(18), dp(20), dp(10));
        TextView t = text(title, 24, C_GOLD, true);
        head.addView(t);
        head.addView(text(subtitle, 12, C_DIM, false));
        content.addView(head);
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(16));
        box.setBackground(round(C_CARD, C_GOLD_DIM, dp(14)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), dp(6), dp(16), dp(6));
        box.setLayoutParams(lp);
        return box;
    }

    private void row(LinearLayout box, String label, String value, boolean good) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(0, dp(7), 0, dp(7));
        TextView key = text(label, 13, C_DIM, false);
        TextView val = text(value, 13, good ? C_TEXT : C_RED, false);
        val.setGravity(Gravity.END);
        val.setTextIsSelectable(true);
        line.addView(key, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(val, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2f));
        box.addView(line);
    }

    private void disabled(LinearLayout box, String label) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(10), 0, dp(10));
        TextView name = text(label, 14, 0xFF6E6659, false);
        TextView tag = text("需改引擎", 11, 0xFF6E6659, false);
        tag.setGravity(Gravity.END);
        line.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(tag);
        box.addView(line);
    }

    private TextView button(String label, boolean primary, View.OnClickListener click) {
        TextView view = text(label, 15, primary ? 0xFF1A1410 : C_TEXT, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(16), dp(14), dp(16), dp(14));
        view.setBackground(round(primary ? C_GOLD : C_CARD_HI,
                primary ? 0 : C_GOLD_DIM, dp(12)));
        view.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), dp(8), dp(16), dp(8));
        view.setLayoutParams(lp);
        return view;
    }

    private interface Label {
        void of(int position);
    }

    private SeekBar slider(int max, int position, Label label) {
        SeekBar seek = new SeekBar(this);
        seek.setMax(max);
        seek.setProgress(position);
        seek.setProgressTintList(ColorStateList.valueOf(C_GOLD));
        seek.setThumbTintList(ColorStateList.valueOf(C_GOLD));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                if (fromUser) label.of(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {}

            @Override
            public void onStopTrackingTouch(SeekBar b) {}
        });
        return seek;
    }

    private interface Toggle {
        void of(boolean checked);
    }

    private void switchRow(LinearLayout box, String label, String hint, boolean on, Toggle changed) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(8), 0, dp(8));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(label, 15, C_TEXT, true));
        words.addView(text(hint, 11, C_DIM, false));

        Switch sw = new Switch(this);
        sw.setChecked(on);
        sw.setThumbTintList(ColorStateList.valueOf(C_GOLD));
        sw.setTrackTintList(ColorStateList.valueOf(0x66C8A24A));
        setting = true;
        sw.setOnCheckedChangeListener((b, checked) -> {
            if (setting) return;
            changed.of(checked);
        });
        setting = false;

        line.addView(words, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(sw);
        box.addView(line);
    }

    // ------------------------------------------------------------ the facts

    private String versionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "?" : info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private long versionCode() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** The APK's signing certificate, SHA-256, as the phone sees it. */
    private String signature() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
            Signature[] signs = info.signingInfo.getApkContentsSigners();
            if (signs == null || signs.length == 0) return "?";
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(signs[0].toByteArray());
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format(Locale.ROOT, "%02X:", b));
            out.setLength(out.length() - 1);
            return out.toString();
        } catch (PackageManager.NameNotFoundException | java.security.NoSuchAlgorithmException e) {
            return "?";
        }
    }

    private long unpackedSize() {
        return size(new File(getFilesDir(), "RTShaderLib")) + size(new File(getFilesDir(), "ui_pack"));
    }

    private static long size(File file) {
        if (file.isFile()) return file.length();
        File[] children = file.listFiles();
        long total = 0;
        if (children != null) for (File child : children) total += size(child);
        return total;
    }

    private static int count(File dir) {
        String[] names = dir.list();
        return names == null ? 0 : names.length;
    }

    private static String mb(long bytes) {
        if (bytes <= 0) return "0 MB";
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ the files

    /**
     * OGRE's shader library (`RTShaderLib/`, `Main/` inside it) and the interface pack
     * (`ui_pack/`), from the APK's assets into the app's files, once for each install or update
     * of the program.
     */
    private void unpackShaderLibrary() throws IOException {
        long installed;
        try {
            installed = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            installed = 0;
        }
        File stamp = new File(getFilesDir(), "RTShaderLib.stamp");
        String want = Long.toString(installed);
        if (stamp.isFile() && want.equals(read(stamp))) return;
        copyAssets(getAssets(), "RTShaderLib", new File(getFilesDir(), "RTShaderLib"));
        // Replaced whole: a file of an older pack (or one spelt in another case) would stay.
        File pack = new File(getFilesDir(), "ui_pack");
        delete(pack);
        copyAssets(getAssets(), "ui_pack", pack);
        try (OutputStream out = new FileOutputStream(stamp)) {
            out.write(want.getBytes("UTF-8"));
        }
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) delete(child);
        }
        file.delete();
    }

    private static void copyAssets(AssetManager assets, String from, File to) throws IOException {
        String[] names = assets.list(from);
        if (names == null || names.length == 0) {
            try (InputStream in = assets.open(from); OutputStream out = new FileOutputStream(to)) {
                byte[] buffer = new byte[65536];
                for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            }
            return;
        }
        if (!to.isDirectory() && !to.mkdirs()) throw new IOException("cannot make " + to);
        for (String name : names) copyAssets(assets, from + "/" + name, new File(to, name));
    }

    static String read(File file) throws IOException {
        try (InputStream in = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            for (int n; (n = in.read(buffer)) > 0; ) bytes.write(buffer, 0, n);
            return new String(bytes.toByteArray(), "UTF-8").trim();
        }
    }
}
