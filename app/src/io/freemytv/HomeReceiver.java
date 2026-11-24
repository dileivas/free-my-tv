package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * System notices for the main process: the TV booted, this app was updated, the sleep timer ended, or the installer
 * answered about an update (Updates). Not exported.
 */
public class HomeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        // LOCKED_BOOT_COMPLETED chega antes de desbloquear: o Prefs ainda não está legível e o
        // Keeper (não direct-boot-aware) falharia. Nesse caso esperamos o USER_UNLOCKED/BOOT_COMPLETED.
        android.os.UserManager users = (android.os.UserManager) context.getSystemService(Context.USER_SERVICE);
        if (users != null && !users.isUserUnlocked()) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            HomeMode.bootCheck(context);
            FireHomeKeeper.start(context);   // Fire TV only; no-op elsewhere
            Updates.schedule(context);
        } else if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
            FireHomeKeeper.start(context);   // agora o armazenamento do app está legível
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            HomeMode.verify(context);   // app updated: complete Free My TV mode if something is missing
            HomeMode.ensureRemoteService(context, 3_000);
            Updates.schedule(context);
            later(() -> Updates.cleanUp(context));
        } else if (Updates.ACTION.equals(action)) {
            later(() -> Updates.status(context, intent));
        } else if (SleepTimer.ACTION.equals(action)) {
            long setAt = intent.getLongExtra("setAt", 0);
            later(() -> SleepTimer.fire(context, setAt));   // ADB is network
        }
    }

    /** Off the main thread (Updates may wait for a check under way), with the notice held open until it's done. */
    private void later(Runnable work) {
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                work.run();
            } finally {
                result.finish();
            }
        }).start();
    }
}
