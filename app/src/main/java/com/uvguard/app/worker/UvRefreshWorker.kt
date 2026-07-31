package com.uvguard.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.glance.appwidget.updateAll
import com.uvguard.app.data.UvRepository
import com.uvguard.app.widget.UvWidget

class UvRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = UvRepository(applicationContext)
        return try {
            val position = repo.getPositionSelonPreference() ?: return Result.retry()
            val uv = repo.getUvActuel(position.first, position.second)
            repo.setDernierUvConnu(uv)

            // Rafraîchit tous les widgets Mode 1 affichés
            UvWidget().updateAll(applicationContext)

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "uv_refresh_periodique"
    }
}
