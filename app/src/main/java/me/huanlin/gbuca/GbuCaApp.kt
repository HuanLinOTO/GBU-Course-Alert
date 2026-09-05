package me.huanlin.gbuca

import android.app.Application
import androidx.room.Room
import me.huanlin.gbuca.data.local.CredentialStore
import me.huanlin.gbuca.data.local.PersistentCookieJar
import me.huanlin.gbuca.data.local.SettingsStore
import me.huanlin.gbuca.data.local.room.AppDatabase
import me.huanlin.gbuca.data.remote.GbuClient
import me.huanlin.gbuca.data.repo.CourseRepository
import me.huanlin.gbuca.reminder.ReminderScheduler
import java.io.File

class GbuCaApp : Application() {

    lateinit var cookieJar: PersistentCookieJar
        private set
    lateinit var client: GbuClient
        private set
    lateinit var db: AppDatabase
        private set
    lateinit var repo: CourseRepository
        private set
    lateinit var creds: CredentialStore
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var reminderScheduler: ReminderScheduler
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        cookieJar = PersistentCookieJar(File(filesDir, "cookies.json"))
        client = GbuClient(cookieJar)
        db = Room.databaseBuilder(this, AppDatabase::class.java, "gbuca.db")
            .fallbackToDestructiveMigration()
            .build()
        creds = CredentialStore(this)
        settings = SettingsStore(this)
        repo = CourseRepository(client, db, creds, settings)
        reminderScheduler = ReminderScheduler(this, settings)
    }

    companion object {
        lateinit var instance: GbuCaApp
            private set
    }
}
