package me.huanlin.gbuca

import android.app.Application
import androidx.room.Room
import me.huanlin.gbuca.data.local.CredentialStore
import me.huanlin.gbuca.data.local.PersistentCookieJar
import me.huanlin.gbuca.data.export.IcsExportManager
import me.huanlin.gbuca.data.local.SettingsStore
import me.huanlin.gbuca.data.local.room.AppDatabase
import me.huanlin.gbuca.data.remote.Endpoints
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
    lateinit var icsExport: IcsExportManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(this)
        // 会话归属按用户配置的域名判定；client 每请求现场解析地址
        cookieJar = PersistentCookieJar(File(filesDir, "cookies.json"), { settings.jwxtHost }, { settings.iaaaHost })
        client = GbuClient(cookieJar) { Endpoints(settings.jwxtHost, settings.iaaaHost) }
        db = Room.databaseBuilder(this, AppDatabase::class.java, "gbuca.db")
            .build()
        creds = CredentialStore(this)
        repo = CourseRepository(client, db, creds, settings)
        reminderScheduler = ReminderScheduler(this, settings)
        icsExport = IcsExportManager(this, repo, settings)
    }

    companion object {
        lateinit var instance: GbuCaApp
            private set
    }
}
