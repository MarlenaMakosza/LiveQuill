package dev.handnotes

import android.app.Application

class HandNotesApp : Application() {
    lateinit var repo: Repo
        private set
    lateinit var sync: SyncClient
        private set
    lateinit var history: History
        private set

    override fun onCreate() {
        super.onCreate()
        repo = Repo(this)
        sync = SyncClient(this, repo)
        history = History(repo)
        repo.onLocalOp = sync::onLocalOp
        sync.start()
    }
}
