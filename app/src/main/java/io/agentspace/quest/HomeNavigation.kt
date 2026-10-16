package io.agentspace.quest

import android.app.Activity
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri

internal object HomeNavigation {
    // Meta requires a system HOME intent. CATEGORY_HOME excludes our private window activity;
    // lint's action-only match against its MAIN filter is a false positive.
    @android.annotation.SuppressLint("UnsafeImplicitIntentLaunch")
    fun enterHome(activity: Activity) {
        val panel = Intent(activity, HomeActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = PendingIntent.getActivity(activity, 200, panel, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("extra_launch_in_home_pending_intent", pending))
        activity.finishAndRemoveTask()
    }
    fun enterImmersive(activity: Activity, onStarted: () -> Unit) {
        activity.startActivity(Intent(activity, AgentSpaceActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        onStarted()
        closeHomeTasks(activity, childrenOnly = false)
    }
    fun openWindow(activity: Activity, target: HomeWindowTarget) {
        val data = Uri.parse(target.encode())
        val tasks = activity.getSystemService(ActivityManager::class.java).appTasks
        val existing = tasks.firstOrNull {
            it.taskInfo.baseIntent.component?.className == HomeWindowActivity::class.java.name && it.taskInfo.baseIntent.data == data
        }
        if (existing != null) { existing.moveToFront(); return }
        activity.startActivity(Intent(activity, HomeWindowActivity::class.java).setData(data).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        })
    }
    fun closeHomeTasks(activity: Activity, childrenOnly: Boolean = true) {
        val classes = if (childrenOnly) setOf(HomeWindowActivity::class.java.name)
            else setOf(HomeActivity::class.java.name, HomeWindowActivity::class.java.name)
        activity.getSystemService(ActivityManager::class.java).appTasks.filter { it.taskInfo.baseIntent.component?.className?.let(classes::contains) == true }
            .forEach { it.finishAndRemoveTask() }
    }
}
