package com.jasiri.sos

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide owner of the SOS runtime; attach/detach are driven by MeshServiceHolder. */
object JasiriSos {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val runtime: SosRuntime by lazy { SosRuntime(scope, System::currentTimeMillis, SosInbox.events) }

    fun onMeshReady(mesh: com.bitchat.android.mesh.MeshService) {
        try {
            runtime.attach(
                mesh.myPeerID,
                peerGatedTransport(
                    send = { payload -> mesh.sendJasiriSos(payload) },
                    peerCount = { com.bitchat.android.service.MeshServiceHolder.meshService?.getActivePeerCount() ?: 0 }
                )
            )
        } catch (_: Exception) { }
    }

    fun onMeshCleared() {
        try { runtime.detach() } catch (_: Exception) { }
    }
}
