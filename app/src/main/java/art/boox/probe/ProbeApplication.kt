package art.boox.probe

import android.app.Application
import android.os.Build
import android.util.Log
import com.onyx.android.sdk.rx.RxBaseAction
import com.onyx.android.sdk.utils.ResManager
import org.lsposed.hiddenapibypass.HiddenApiBypass

class ProbeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Match OnyxPenDemo/DemoApplication before any EpdController or TouchHelper use.
        // This exemption applies only to this process; no global Android setting is changed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Log.i("BooxProbe", "Firmware API access=${HiddenApiBypass.addHiddenApiExemptions("")}")
        }
        ResManager.init(this)
        RxBaseAction.init(this)
    }
}
