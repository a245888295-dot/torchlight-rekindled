// The game's activity: NativeActivity, which loads libtorchlight.so and runs
// its android_main(), with two changes.
//
// Back (on a phone with gesture navigation, a swipe in from either side, easy
// to make while playing with the touch pad) opens the panel the port adds on
// top of the surface -- resolution, frame limit, the frame-rate overlay, and
// the way out of the game -- instead of finishing the activity and so ending
// the run. Back again closes it. The panel is a view on this activity's decor
// view, so the engine keeps its own loop and window; nothing here touches the
// native side.
//
// The game is left where it was when it is minimised: the engine waits in the
// background for its window to come back.
package port.android;

import android.app.AlertDialog;
import android.app.NativeActivity;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

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
        // Above the native surface: the engine draws into its own window below
        // this one, and the panel is a view of the activity's, so it needs no
        // permission, no extra window and no manifest entry.
        ((ViewGroup) getWindow().getDecorView()).addView(menu, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // From Android 13 Back arrives as a callback (the predictive back
        // gesture); onBackPressed() below is the older phones' path.
        if (Build.VERSION.SDK_INT >= 33) {
            OnBackInvokedCallback toggle = this::toggleMenu;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, toggle);
        }
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
        toggleMenu();
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
}
