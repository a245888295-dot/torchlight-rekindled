// The game's activity: NativeActivity, which loads libtorchlight.so and runs
// its android_main(), with two changes of this port's own.
//
// Back (on a phone with gesture navigation, a swipe in from either side, easy
// to make while playing with the touch pad) opens the panel: the render
// resolution, the frame limit, the frame-rate overlay, and the way out of the
// game. Back again closes it. The panel is a dialog -- a window above this
// activity's -- so it is above the engine's surface, and it takes the touches
// while it is up.
//
// Every path Back can arrive by is handled, because which one the system uses
// depends on the phone: a key event (three-button navigation, a keyboard), the
// activity's onBackPressed (gesture navigation on an app that has not opted
// into the predictive back callback), or the OnBackInvokedCallback itself.
//
// Each of those writes a line to the app's own log (`panel.log` in its files),
// so a back that does nothing can be told from a panel that does not show.
package port.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NativeActivity;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class GameActivity extends NativeActivity {
    /** The panel over the surface; null until onCreate has run. */
    private GameMenu menu;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Launcher.gameStarted();

        menu = new GameMenu(this, new GameMenu.Host() {
            @Override
            public void minimise() {
                menu.close();
                moveTaskToBack(true);
            }

            @Override
            public void quitGame() {
                askToQuit();
            }
        });

        // From Android 13 Back may arrive as a callback (the predictive back
        // gesture); the other paths are below, for the phones that do not.
        if (Build.VERSION.SDK_INT >= 33) {
            OnBackInvokedCallback toggle = this::toggleMenu;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, toggle);
        }
        trace(this, "game: panel ready");
    }

    /** Back opens the panel, and closes it again while it is open. */
    private void toggleMenu() {
        if (menu == null) return;
        if (menu.opened()) menu.close();
        else menu.open();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        trace(this, "back: onBackPressed");
        toggleMenu();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                trace(this, "back: key event");
                toggleMenu();
            }
            return true;  // taken here: the engine must not see it as well
        }
        return super.dispatchKeyEvent(event);
    }

    private void askToQuit() {
        new AlertDialog.Builder(this)
                .setTitle("退出游戏？")
                .setMessage("未保存的进度会丢失。下次进入游戏时，刚才改的分辨率和帧率才会生效。")
                .setPositiveButton("退出", (d, w) -> finish())
                .setNegativeButton("继续游戏", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        Launcher.gameEnded();
        super.onDestroy();
    }

    /**
     * A line in the app's own log, and in logcat: what Back did and whether the
     * panel came up. Kept small -- the file is rewritten once it passes 64 KB.
     */
    static void trace(Activity activity, String line) {
        android.util.Log.i("torchlight", line);
        File file = new File(activity.getFilesDir(), "panel.log");
        try {
            if (file.length() > 65536) file.delete();
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                String stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
                out.write((stamp + " " + line + "\n").getBytes("UTF-8"));
            }
        } catch (IOException e) {
            // Logging is a courtesy; it must never break the game.
        }
    }
}
