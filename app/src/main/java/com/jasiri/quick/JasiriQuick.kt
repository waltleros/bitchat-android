package com.jasiri.quick

import com.jasiri.sos.peerGatedTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide owner of the quick message runtime; attach/detach are driven by JasiriHooks. */
object JasiriQuick {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val runtime: QuickRuntime by lazy { QuickRuntime(scope, System::currentTimeMillis, QuickInbox.events) }

    fun onMeshReady(mesh: com.bitchat.android.mesh.MeshService) {
        try {
            runtime.attach(
                mesh.myPeerID,
                peerGatedTransport(
                    send = { payload -> mesh.sendJasiriQuick(payload) },
                    peerCount = { com.bitchat.android.service.MeshServiceHolder.meshService?.getActivePeerCount() ?: 0 }
                )
            )
        } catch (_: Exception) { }
    }

    fun onMeshCleared() {
        try { runtime.detach() } catch (_: Exception) { }
    }
}
