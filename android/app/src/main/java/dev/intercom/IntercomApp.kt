package dev.intercom

import android.app.Application
import dev.intercom.data.Config
import dev.intercom.sender.SenderController

class IntercomApp : Application() {
    lateinit var sender: SenderController
        private set

    override fun onCreate() {
        super.onCreate()
        Config.init(this)
        sender = SenderController(this)
    }
}
