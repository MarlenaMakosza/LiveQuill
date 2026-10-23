package io.github.marlenamakosza.livequill

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.marlenamakosza.livequill.shared.Msg
import io.github.marlenamakosza.livequill.shared.Proto
import io.github.marlenamakosza.livequill.shared.PushRequest
import io.github.marlenamakosza.livequill.shared.ServerAddress
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Empties the outbox over HTTP when the app is closed. WorkManager starts it once a network
 * is up and retries with backoff until the server is reachable.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val repo = (applicationContext as LiveQuillApp).repo
        val url = repo.serverUrl.ifEmpty { return Result.success() }
        val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()
        val endpoint = ServerAddress.http(url) + "/api/push"
        while (true) {
            val batch = repo.db.outbox(0, 200)
            if (batch.isEmpty()) return Result.success()
            val body = Proto.json.encodeToString(PushRequest.serializer(), PushRequest(repo.token, repo.deviceId, batch))
            val req = Request.Builder().url(endpoint).post(body.toRequestBody("application/json".toMediaType())).build()
            val ack = runCatching {
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) null else Proto.decode(r.body.string()) as? Msg.Ack
                }
            }.getOrNull() ?: return Result.retry()
            repo.db.ackOutbox(ack.upTo)
            if (ack.upTo < batch.last().seq) return Result.retry()
        }
    }

    companion object {
        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequest.Builder(SyncWorker::class.java)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("push-outbox", ExistingWorkPolicy.KEEP, req)
        }
    }
}
