package com.jasiri

/** Single entry point from upstream MeshServiceHolder into all JASIRI runtimes. Never throws. */
object JasiriHooks {
    fun onMeshReady(mesh: com.bitchat.android.mesh.MeshService) {
        try { com.jasiri.sos.JasiriSos.onMeshReady(mesh) } catch (_: Exception) { }
        try { com.jasiri.quick.JasiriQuick.onMeshReady(mesh) } catch (_: Exception) { }
    }

    fun onMeshCleared() {
        try { com.jasiri.sos.JasiriSos.onMeshCleared() } catch (_: Exception) { }
        try { com.jasiri.quick.JasiriQuick.onMeshCleared() } catch (_: Exception) { }
    }
}
