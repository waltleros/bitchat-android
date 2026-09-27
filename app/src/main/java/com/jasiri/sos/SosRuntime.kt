package com.jasiri.sos

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Connects this phone's own SOS ([own]) and the board of other people's SOS to a mesh
 * transport that can come and go, and to a peer identity that can change (panic wipe).
 *
 * Lock ordering: the runtime's lock may call into the controller or the board; neither ever
 * calls back into the runtime. The shared [SosSender] never takes the runtime's lock.
 */
class SosRuntime(
    private val scope: CoroutineScope,
    private val clockMillis: () -> Long,
    private val inbox: Flow<ReceivedSos>,
    private val tickIntervalMillis: Long = 30_000,
    ownConfig: OwnSosConfig = OwnSosConfig(),
    private val boardConfig: SosBoardConfig = SosBoardConfig()
) {
    private val lock = Any()

    @Volatile
    private var transport: ((ByteArray) -> Boolean)? = null

    @Volatile
    private var currentBoard: SosBoard? = null
    private var boardJobs: List<Job> = emptyList()

    private val sender = SosSender { payload ->
        val t = transport ?: return@SosSender false
        try {
            t(payload)
        } catch (_: Exception) {
            false
        }
    }

    val own: OwnSosController = OwnSosController(sender, scope, clockMillis, config = ownConfig)

    private val _entries = MutableStateFlow<List<SosEntry>>(emptyList())

    /** Mirror of the current board's entries; empty before the first attach. */
    val entries: StateFlow<List<SosEntry>> = _entries.asStateFlow()

    private val _myPeerID = MutableStateFlow<String?>(null)

    /** Null before the first attach. */
    val myPeerID: StateFlow<String?> = _myPeerID.asStateFlow()

    init {
        scope.launch {
            while (true) {
                delay(tickIntervalMillis)
                currentBoard?.tick()
            }
        }
    }

    fun attach(peerID: String, transport: (ByteArray) -> Boolean) {
        try {
            synchronized(lock) {
                this.transport = transport
                val board = currentBoard
                if (board != null && _myPeerID.value == peerID) return
                if (board != null) {
                    own.abandon()
                    boardJobs.forEach { it.cancel() }
                    boardJobs = emptyList()
                    _entries.value = emptyList()
                }
                createBoardLocked(peerID)
            }
        } catch (_: Exception) {
        }
    }

    /** Drops the transport only; the board, its entries and the own SOS state are kept. */
    fun detach() {
        transport = null
    }

    fun acknowledge(sosId: Long): Boolean = act { it.acknowledge(sosId) }

    fun claim(sosId: Long): Boolean = act { it.claim(sosId) }

    fun resolve(sosId: Long): Boolean = act { it.resolve(sosId) }

    /** Refuses while detached so the board never records an action that could not be sent. */
    private inline fun act(action: (SosBoard) -> Boolean): Boolean {
        if (transport == null) return false
        val board = synchronized(lock) { currentBoard } ?: return false
        return try {
            action(board)
        } catch (_: Exception) {
            false
        }
    }

    private fun createBoardLocked(peerID: String) {
        val board = SosBoard(peerID, sender, clockMillis, boardConfig)
        currentBoard = board
        _myPeerID.value = peerID
        val collectJob = board.collectFrom(inbox, scope)
        val mirrorJob = scope.launch {
            board.entries.collect { list ->
                synchronized(lock) {
                    if (currentBoard === board) _entries.value = list
                }
            }
        }
        boardJobs = listOf(collectJob, mirrorJob)
    }
}
