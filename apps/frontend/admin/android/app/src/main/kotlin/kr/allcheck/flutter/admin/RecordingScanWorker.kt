package kr.allcheck.flutter.admin

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class RecordingScanWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = if (RecordingCollector(applicationContext).scan() && runAttemptCount < 3) Result.retry() else Result.success()
}
