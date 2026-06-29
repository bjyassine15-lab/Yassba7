package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ar.ARSessionManager
import com.example.sync.GameMode
import com.example.sync.GameSyncManager
import com.example.sync.GameState
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

data class HitMarker(val x: Float, val y: Float, val text: String, val timestamp: Long)

class ARDuelViewModel(application: Application) : AndroidViewModel(application) {

    val syncManager = GameSyncManager(application.applicationContext)
    val arSessionManager = ARSessionManager(application.applicationContext)

    // UI Feedback State
    val isShootingLocal = MutableStateFlow(false)
    val showHitMarker = MutableStateFlow<HitMarker?>(null)
    val occlusionStatusMessage = MutableStateFlow<String?>(null) // e.g., "أصبت الجدار!" (Hit the wall!)
    
    // Viewport Size
    var viewWidth = 1080
    var viewHeight = 2340

    // Anchor states
    var localAnchor: Anchor? = null

    init {
        // Observe hit events for visual flash
        viewModelScope.launch {
            syncManager.hitEvents.collect { playerId ->
                // Visual or haptic triggers can go here
                Log.d("ARDuelViewModel", "Player $playerId was hit!")
            }
        }
    }

    /**
     * Set viewport sizes dynamically on Surface resize
     */
    fun updateViewport(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
    }

    /**
     * Returns Opponent's 3D Position in LOCAL coordinates
     */
    fun getOpponentLocalPosition(): FloatArray {
        val opp = syncManager.opponentPlayer.value
        
        // In local training, AI moves in local space directly
        if (syncManager.gameMode.value == GameMode.LOCAL_AI_SIMULATION) {
            return floatArrayOf(opp.posX, opp.posY, opp.posZ)
        }

        // In Online Mode, transform opponent pose via Cloud Anchor
        val anchor = localAnchor
        if (anchor != null && anchor.trackingState == com.google.ar.core.TrackingState.TRACKING) {
            val anchorPose = anchor.pose
            val oppSharedPose = Pose(
                floatArrayOf(opp.posX, opp.posY, opp.posZ),
                floatArrayOf(opp.rotX, opp.rotY, opp.rotZ, opp.rotW)
            )
            val oppLocalPose = anchorPose.compose(oppSharedPose)
            return floatArrayOf(oppLocalPose.tx(), oppLocalPose.ty(), oppLocalPose.tz())
        }

        // Fallback to shared position directly if anchor not resolved
        return floatArrayOf(opp.posX, opp.posY, opp.posZ)
    }

    /**
     * Shoots a raycast from camera center and checks hit, accounting for depth occlusion
     */
    fun fireWeapon(currentFrame: Frame) {
        if (isShootingLocal.value) return // Fire rate lock
        isShootingLocal.value = true
        syncManager.triggerLocalShoot()

        viewModelScope.launch {
            delay(150) // recoil animation delay
            isShootingLocal.value = false
        }

        val camera = currentFrame.camera
        val cameraPose = camera.pose
        val camPos = floatArrayOf(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())

        // Extract camera direction (forward vector in OpenGL is negative Z of rotation matrix)
        val dir = cameraPose.zAxis // 3rd column of rotation matrix
        val camDir = floatArrayOf(-dir[0], -dir[1], -dir[2]) // pointing forward

        // Get Opponent's local position
        val oppPos = getOpponentLocalPosition()

        // Math: Raycasting collision check
        // Vector from camera to opponent
        val vecToOpp = floatArrayOf(
            oppPos[0] - camPos[0],
            oppPos[1] - camPos[1],
            oppPos[2] - camPos[2]
        )

        // Distance along camera ray
        val distToOpp = sqrt(
            vecToOpp[0] * vecToOpp[0] +
            vecToOpp[1] * vecToOpp[1] +
            vecToOpp[2] * vecToOpp[2]
        )

        // Dot product to project opponent onto camera forward direction
        val projection = vecToOpp[0] * camDir[0] + vecToOpp[1] * camDir[1] + vecToOpp[2] * camDir[2]

        if (projection < 0f) {
            // Opponent is behind us
            return
        }

        // Perpendicular distance (miss distance)
        val missDistance = sqrt(
            (vecToOpp[0] * vecToOpp[0] + vecToOpp[1] * vecToOpp[1] + vecToOpp[2] * vecToOpp[2]) -
            (projection * projection)
        )

        // Hit sphere radius (e.g., 0.4 meters for player chest / bounding volume)
        val opponentTargetRadius = 0.42f

        if (missDistance <= opponentTargetRadius) {
            // 2. CHECK ARCORE DEPTH OCCLUSION
            val depthMeters = arSessionManager.getDepthAtCenter(currentFrame)
            
            if (depthMeters > 0f && depthMeters < distToOpp - 0.3f) {
                // There is an obstacle (e.g. wall) between us and the opponent!
                occlusionStatusMessage.value = "الطلقة اصطدمت بالحاجز!" // Shot hit the wall!
                viewModelScope.launch {
                    delay(1200)
                    occlusionStatusMessage.value = null
                }
                Log.d("ARDuelViewModel", "Hit blocked by wall! Depth: $depthMeters, Dist to opponent: $distToOpp")
            } else {
                // Successful Hit!
                syncManager.hitOpponent(10)
                showHitMarker.value = HitMarker(
                    x = viewWidth / 2f,
                    y = viewHeight / 2f,
                    text = "-10 HP",
                    timestamp = System.currentTimeMillis()
                )
                viewModelScope.launch {
                    delay(800)
                    showHitMarker.value = null
                }
                Log.d("ARDuelViewModel", "Successful hit! Enemy takes damage. Dist: $distToOpp")
            }
        }
    }

    /**
     * Publish local pose to room
     */
    fun updateLocalPose(currentFrame: Frame) {
        val cameraPose = currentFrame.camera.pose
        val camPos = floatArrayOf(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())
        val camRot = floatArrayOf(cameraPose.qx(), cameraPose.qy(), cameraPose.qz(), cameraPose.qw())

        // In Online Mode, calculate local coordinates relative to the resolved Cloud Anchor
        val anchor = localAnchor
        if (syncManager.gameMode.value == GameMode.ONLINE_MULTIPLAYER && anchor != null && anchor.trackingState == com.google.ar.core.TrackingState.TRACKING) {
            // Camera position in Anchor coordinates: T_camera_anchor = T_anchor.inverse() * T_camera
            val anchorPose = anchor.pose
            val cameraRelativePose = anchorPose.inverse().compose(cameraPose)
            
            val relPos = floatArrayOf(cameraRelativePose.tx(), cameraRelativePose.ty(), cameraRelativePose.tz())
            val relRot = floatArrayOf(cameraRelativePose.qx(), cameraRelativePose.qy(), cameraRelativePose.qz(), cameraRelativePose.qw())
            syncManager.updateLocalPose(relPos, relRot)
        } else {
            // In simulation or unanchored, send local pose directly
            syncManager.updateLocalPose(camPos, camRot)
        }
    }

    override fun onCleared() {
        super.onCleared()
        arSessionManager.onDestroy()
        syncManager.resetGame()
    }

    private suspend fun delay(timeMs: Long) {
        kotlinx.coroutines.delay(timeMs)
    }
}
