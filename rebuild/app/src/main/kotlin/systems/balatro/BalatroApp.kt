package systems.balatro

import android.app.Application
import systems.balatro.bridge.Telemetry

/** App entry — telemetry comes up before anything else, so even startup crashes are caught. */
class BalatroApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Telemetry.init(this)
    }
}
