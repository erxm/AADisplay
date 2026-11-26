package io.github.nitsuya.aa.display.xposed;

import android.view.Surface;
import android.view.SurfaceControl;
import io.github.nitsuya.aa.display.xposed.IVirtualDisplayCreatedListener;
import io.github.nitsuya.aa.display.model.RecentTask;

interface ICoreManager {

    String getVersionName();
    int getVersionCode();
    int getUid();
    long getBuildTime();

    void onCreateDisplay(int with, int height, int densityDpi, in Surface surface, IVirtualDisplayCreatedListener listener);
    void setDisplaySurface(in Surface surface);
    void onDestroyDisplay();

    void startLauncher();
    void startActivity(String packageName, int userId);
    void startTaskId(int taskId, String packageName, int userId);
    void moveTaskId(int taskId, boolean isVirtualDisplay);
    void moveTaskToFront(int taskId);
    void moveSecondTaskToFront();
    void removeTask(int taskId);
    void pressKey(int action);
    void touch(in MotionEvent motionEvent);
    void toggleDisplayPower();
    void displayPower(boolean displayPower);

    void addMirror(in SurfaceControl surfaceControl);
    void removeMirror(in SurfaceControl surfaceControl);

    RecentTask getRecentTask();

    void testCode(String action);
    void toast(String msg);
    void printLog(String tag, String msg);

    /**
     * gearhead :projection / :car report Coolwalk rail snapshot for display-profile settle.
     * phase is RailPhase.code. Appended at end to keep existing Stub ordinals stable.
     */
    oneway void reportCoolwalkRailSnapshot(int phase, int touchRailWidthPx, int fullHuWidthPx, int facetDisplayId);

    /**
     * Returns int[4]: phase, touchRailWidthPx, fullHuWidthPx, facetDisplayId.
     */
    int[] getCoolwalkRailSnapshot();

    /** SystemClock.uptimeMillis of the latest gearhead soft reconnect; 0 if none. */
    long getCoolwalkReconnectEpochMs();

    /**
     * system_server -> AADisplay: gearhead cannot reliably deliver package-targeted
     * broadcasts on Android 13+, so relay the full-bleed signal through the system uid.
     */
    oneway void notifyCoolwalkFullBleed();

    /**
     * AAP route B: the VirtualDisplay was created by the app process (healthy SF
     * composition); system_server only takes over task hosting / input injection /
     * mirroring / phone-side DisplayWindow. Appended at end to keep Stub ordinals stable.
     */
    void onExternalDisplayAttached(int displayId, int width, int height, int densityDpi, IVirtualDisplayCreatedListener listener);
}
