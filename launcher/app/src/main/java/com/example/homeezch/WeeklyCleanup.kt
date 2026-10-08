package com.example.homeezch

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.Executors

internal object WeeklyCleanup {
    private const val ID = 15007
    private const val WEEK = 7 * 24 * 60 * 60 * 1000L
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (scheduler.getPendingJob(ID) == null) runCatching { scheduler.schedule(JobInfo.Builder(ID,
            ComponentName(context, WeeklyCleanupService::class.java)).setPeriodic(WEEK, 24 * 60 * 60 * 1000L)
            .setRequiresDeviceIdle(true).setPersisted(true).build()) }
    }
    fun clean(context: Context) {
        val cutoff = System.currentTimeMillis() - WEEK
        // Only stale weather response files owned by this application. Downloads,
        // active installer sessions, videos and other applications are never touched.
        context.cacheDir.listFiles()?.filter { it.isFile && it.name.startsWith("open-meteo-") && it.name.endsWith(".json") && it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }
}
class WeeklyCleanupService : JobService() {
    private val worker = Executors.newSingleThreadExecutor()
    override fun onStartJob(params: JobParameters): Boolean {
        worker.execute { runCatching { WeeklyCleanup.clean(this) }; android.os.Handler(mainLooper).post { jobFinished(params, false) } }
        return true
    }
    override fun onStopJob(params: JobParameters) = true
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
