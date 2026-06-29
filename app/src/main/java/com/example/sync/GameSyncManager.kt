package com.example.sync

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

enum class GameMode {
    LOCAL_AI_SIMULATION,
    ONLINE_MULTIPLAYER
}

enum class GameState {
    LOBBY,
    HOSTING_AR,
    RESOLVING_AR,
    PLAYING,
    GAME_OVER
}

data class PlayerData(
    var id: String = "",
    var name: String = "",
    var health: Int = 100,
    var posX: Float = 0f,
    var posY: Float = 0f,
    var posZ: Float = -2f, // Default 2 meters in front
    var rotX: Float = 0f,
    var rotY: Float = 0f,
    var rotZ: Float = 0f,
    var rotW: Float = 1f,
    var isShooting: Boolean = false,
    var lastUpdate: Long = 0
)

class GameSyncManager(private val context: Context) {

    // Network & Mode Config
    var gameMode = MutableStateFlow(GameMode.LOCAL_AI_SIMULATION)
    var gameState = MutableStateFlow(GameState.LOBBY)

    // Identity
    val localPlayerId = "player_" + (1000..9999).random()
    var isHost = false

    // Sync state
    val roomId = MutableStateFlow<String?>(null)
    val cloudAnchorId = MutableStateFlow<String?>(null)

    // Player Data
    val localPlayer = MutableStateFlow(PlayerData(id = localPlayerId, name = "أنت"))
    val opponentPlayer = MutableStateFlow(PlayerData(id = "opponent", name = "الخصم"))

    // Events
    private val _shootEvents = MutableSharedFlow<String>() // Emits playerId of shooter
    val shootEvents: SharedFlow<String> = _shootEvents

    private val _hitEvents = MutableSharedFlow<String>() // Emits playerId of player hit
    val hitEvents: SharedFlow<String> = _hitEvents

    // Firebase References
    private var firebaseApp: FirebaseApp? = null
    private var database: FirebaseDatabase? = null
    private var roomRef: DatabaseReference? = null
    private var roomListener: ValueEventListener? = null

    // Jobs
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var aiSimulationJob: Job? = null

    init {
        // Safe Firebase initialization
        try {
            firebaseApp = FirebaseApp.initializeApp(context)
            if (firebaseApp != null) {
                database = FirebaseDatabase.getInstance()
                Log.d("GameSyncManager", "Firebase successfully initialized.")
            } else {
                Log.w("GameSyncManager", "Firebase initialization returned null, default to Local AI.")
            }
        } catch (e: Exception) {
            Log.e("GameSyncManager", "Firebase credentials missing or setup failed. Falling back to Local AI.", e)
        }
    }

    /**
     * Start Solo AI Simulation Mode
     */
    fun startLocalAISimulation() {
        gameMode.value = GameMode.LOCAL_AI_SIMULATION
        gameState.value = GameState.PLAYING
        isHost = true
        roomId.value = "محاكاة_محلي"
        
        localPlayer.value = PlayerData(id = localPlayerId, name = "البطل (أنت)", health = 100)
        opponentPlayer.value = PlayerData(
            id = "ai_bot",
            name = "روبوت التدريب AI",
            health = 100,
            posX = 0f,
            posY = 0f,
            posZ = -2.5f // 2.5 meters in front
        )

        // Start AI Movement Thread
        aiSimulationJob?.cancel()
        aiSimulationJob = scope.launch {
            var angle = 0f
            while (gameState.value == GameState.PLAYING) {
                delay(50)
                angle += 0.03f
                
                // Move AI opponent in a circle / sinusoidal pattern relative to the anchor
                val currentOpponent = opponentPlayer.value
                val newX = 1.5f * sin(angle)
                val newZ = -2.5f + cos(angle * 0.5f) * 0.5f
                val newY = 0.2f * sin(angle * 2) // sinusoidal float

                opponentPlayer.value = currentOpponent.copy(
                    posX = newX,
                    posY = newY,
                    posZ = newZ,
                    lastUpdate = System.currentTimeMillis()
                )

                // Occasional AI firing simulation (every 3-5 seconds)
                if ((1..100).random() == 50) {
                    _shootEvents.emit("ai_bot")
                    
                    // 25% chance to hit the local player (unblocked by cover)
                    if ((1..4).random() == 1) {
                        val hp = localPlayer.value.health - 10
                        if (hp >= 0) {
                            localPlayer.value = localPlayer.value.copy(health = hp)
                            _hitEvents.emit(localPlayerId)
                            if (hp <= 0) {
                                gameState.value = GameState.GAME_OVER
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Create Online Firebase Room
     */
    fun createRoom(onResult: (String?, String?) -> Unit) {
        val db = database
        if (db == null) {
            onResult(null, "Firebase غير مهيأ. يرجى تهيئة Firebase أولاً!")
            return
        }

        val code = (1000..9999).random().toString()
        isHost = true
        gameMode.value = GameMode.ONLINE_MULTIPLAYER
        gameState.value = GameState.HOSTING_AR
        roomId.value = code

        roomRef = db.getReference("rooms").child(code)
        
        val initialRoomData = mapOf(
            "gameState" to GameState.HOSTING_AR.name,
            "hostAnchorId" to "",
            "lastShotBy" to "",
            "lastShotTime" to 0L,
            "players" to mapOf(
                localPlayerId to mapOf(
                    "id" to localPlayerId,
                    "name" to "المضيف (Host)",
                    "health" to 100,
                    "posX" to 0f, "posY" to 0f, "posZ" to 0f,
                    "rotX" to 0f, "rotY" to 0f, "rotZ" to 0f, "rotW" to 1f,
                    "isShooting" to false,
                    "lastUpdate" to System.currentTimeMillis()
                )
            )
        )

        roomRef?.setValue(initialRoomData)?.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                observeRoom(code)
                onResult(code, null)
            } else {
                onResult(null, task.exception?.localizedMessage ?: "حدث خطأ في إنشاء الغرفة")
            }
        }
    }

    /**
     * Join Online Firebase Room
     */
    fun joinRoom(code: String, onResult: (Boolean, String?) -> Unit) {
        val db = database
        if (db == null) {
            onResult(false, "Firebase غير مهيأ. يرجى تهيئة Firebase أولاً!")
            return
        }

        isHost = false
        gameMode.value = GameMode.ONLINE_MULTIPLAYER
        roomId.value = code

        roomRef = db.getReference("rooms").child(code)
        roomRef?.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    onResult(false, "الغرفة غير موجودة!")
                    return
                }

                // Register client player
                val clientData = mapOf(
                    "id" to localPlayerId,
                    "name" to "المنضم (Client)",
                    "health" to 100,
                    "posX" to 0f, "posY" to 0f, "posZ" to 0f,
                    "rotX" to 0f, "rotY" to 0f, "rotZ" to 0f, "rotW" to 1f,
                    "isShooting" to false,
                    "lastUpdate" to System.currentTimeMillis()
                )

                roomRef?.child("players")?.child(localPlayerId)?.setValue(clientData)
                    ?.addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            gameState.value = GameState.RESOLVING_AR
                            observeRoom(code)
                            onResult(true, null)
                        } else {
                            onResult(false, task.exception?.localizedMessage)
                        }
                    }
            }

            override fun onCancelled(error: DatabaseError) {
                onResult(false, error.message)
            }
        })
    }

    /**
     * Start Room Listener
     */
    private fun observeRoom(code: String) {
        roomListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return

                // Get state
                val stateStr = snapshot.child("gameState").getValue(String::class.java) ?: GameState.LOBBY.name
                val snapGameState = GameState.valueOf(stateStr)
                if (gameState.value != snapGameState) {
                    gameState.value = snapGameState
                }

                // Cloud Anchor
                val anchorId = snapshot.child("hostAnchorId").getValue(String::class.java)
                if (!anchorId.isNullOrEmpty() && cloudAnchorId.value != anchorId) {
                    cloudAnchorId.value = anchorId
                }

                // Shoot Event
                val lastShotBy = snapshot.child("lastShotBy").getValue(String::class.java) ?: ""
                val lastShotTime = snapshot.child("lastShotTime").getValue(Long::class.java) ?: 0L
                if (lastShotBy.isNotEmpty() && lastShotBy != localPlayerId && System.currentTimeMillis() - lastShotTime < 500) {
                    scope.launch {
                        _shootEvents.emit(lastShotBy)
                    }
                }

                // Extract Players
                val playersSnapshot = snapshot.child("players")
                for (playerSnap in playersSnapshot.children) {
                    val pid = playerSnap.child("id").getValue(String::class.java) ?: ""
                    if (pid.isEmpty()) continue

                    val hp = playerSnap.child("health").getValue(Int::class.java) ?: 100
                    val posX = playerSnap.child("posX").getValue(Float::class.java) ?: 0f
                    val posY = playerSnap.child("posY").getValue(Float::class.java) ?: 0f
                    val posZ = playerSnap.child("posZ").getValue(Float::class.java) ?: 0f
                    val rotX = playerSnap.child("rotX").getValue(Float::class.java) ?: 0f
                    val rotY = playerSnap.child("rotY").getValue(Float::class.java) ?: 0f
                    val rotZ = playerSnap.child("rotZ").getValue(Float::class.java) ?: 0f
                    val rotW = playerSnap.child("rotW").getValue(Float::class.java) ?: 1f
                    val name = playerSnap.child("name").getValue(String::class.java) ?: "لاعب"

                    val pData = PlayerData(
                        id = pid,
                        name = name,
                        health = hp,
                        posX = posX, posY = posY, posZ = posZ,
                        rotX = rotX, rotY = rotY, rotZ = rotZ, rotW = rotW,
                        lastUpdate = System.currentTimeMillis()
                    )

                    if (pid == localPlayerId) {
                        if (localPlayer.value.health != hp) {
                            localPlayer.value = localPlayer.value.copy(health = hp)
                            scope.launch { _hitEvents.emit(localPlayerId) }
                            if (hp <= 0) {
                                updateRoomState(GameState.GAME_OVER)
                            }
                        }
                    } else {
                        val prevHp = opponentPlayer.value.health
                        opponentPlayer.value = pData
                        if (prevHp != hp && hp < prevHp) {
                            scope.launch { _hitEvents.emit(pid) }
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("GameSyncManager", "Database Listener cancelled", error.toException())
            }
        }
        roomRef?.addValueEventListener(roomListener!!)
    }

    /**
     * Publishes Host Cloud Anchor ID once hosted
     */
    fun setRoomCloudAnchor(anchorId: String) {
        cloudAnchorId.value = anchorId
        if (gameMode.value == GameMode.ONLINE_MULTIPLAYER) {
            roomRef?.child("hostAnchorId")?.setValue(anchorId)
            updateRoomState(GameState.PLAYING)
        }
    }

    /**
     * Changes room game state
     */
    fun updateRoomState(state: GameState) {
        gameState.value = state
        if (gameMode.value == GameMode.ONLINE_MULTIPLAYER) {
            roomRef?.child("gameState")?.setValue(state.name)
        }
    }

    /**
     * Updates Local Player's Shared coordinates (calculated relative to Cloud Anchor)
     */
    fun updateLocalPose(pos: FloatArray, rot: FloatArray) {
        val currentLocal = localPlayer.value
        localPlayer.value = currentLocal.copy(
            posX = pos[0], posY = pos[1], posZ = pos[2],
            rotX = rot[0], rotY = rot[1], rotZ = rot[2], rotW = rot[3]
        )

        if (gameMode.value == GameMode.ONLINE_MULTIPLAYER) {
            val playersRef = roomRef?.child("players")?.child(localPlayerId)
            val updates = mapOf(
                "posX" to pos[0], "posY" to pos[1], "posZ" to pos[2],
                "rotX" to rot[0], "rotY" to rot[1], "rotZ" to rot[2], "rotW" to rot[3],
                "lastUpdate" to System.currentTimeMillis()
            )
            playersRef?.updateChildren(updates)
        }
    }

    /**
     * Triggers Shoot Animation and Sync
     */
    fun triggerLocalShoot() {
        scope.launch {
            _shootEvents.emit(localPlayerId)
        }
        if (gameMode.value == GameMode.ONLINE_MULTIPLAYER) {
            val updates = mapOf(
                "lastShotBy" to localPlayerId,
                "lastShotTime" to System.currentTimeMillis()
            )
            roomRef?.updateChildren(updates)
        }
    }

    /**
     * Damaging opponent
     */
    fun hitOpponent(damage: Int = 10) {
        val currentOpp = opponentPlayer.value
        val newHp = (currentOpp.health - damage).coerceAtLeast(0)
        
        if (gameMode.value == GameMode.LOCAL_AI_SIMULATION) {
            opponentPlayer.value = currentOpp.copy(health = newHp)
            scope.launch {
                _hitEvents.emit("ai_bot")
            }
            if (newHp <= 0) {
                gameState.value = GameState.GAME_OVER
            }
        } else {
            roomRef?.child("players")?.child(currentOpp.id)?.child("health")?.setValue(newHp)
        }
    }

    /**
     * Cleanups and resets
     */
    fun resetGame() {
        aiSimulationJob?.cancel()
        roomListener?.let { roomRef?.removeEventListener(it) }
        roomRef = null
        roomListener = null
        
        gameState.value = GameState.LOBBY
        roomId.value = null
        cloudAnchorId.value = null
        localPlayer.value = PlayerData(id = localPlayerId, name = "أنت", health = 100)
        opponentPlayer.value = PlayerData(id = "opponent", name = "الخصم", health = 100)
    }
}
