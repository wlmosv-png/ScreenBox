package io.github.wlmosv.screenbox.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.wlmosv.screenbox.core.Graph
import io.github.wlmosv.screenbox.data.db.ProtectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * 测试数据准备（debug only）。
 * 把某个 id 设为 PROTECTED，用于验证删除安全链路。
 */
class TestSetupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Graph.init(context.applicationContext)
        val app = context.applicationContext
        val id = intent.getLongExtra("id", -1L)
        val mode = intent.getStringExtra("mode") ?: "protect"

        CoroutineScope(Dispatchers.IO).launch {
            val dao = Graph.db.screenshotDao()
            if (id > 0) {
                when (mode) {
                    "protect" -> dao.setProtection(id, ProtectionStatus.PROTECTED, System.currentTimeMillis())
                    "favorite" -> dao.setFavorite(id, true, System.currentTimeMillis())
                    "unprotect" -> dao.setProtection(id, ProtectionStatus.NORMAL, System.currentTimeMillis())
                }
            }
            val shot = dao.byId(id)
            File(app.getExternalFilesDir(null), "setup_result.txt").writeText(
                "id=$id mode=$mode -> protectionStatus=${shot?.protectionStatus} isFavorite=${shot?.isFavorite}"
            )
        }
    }
}
