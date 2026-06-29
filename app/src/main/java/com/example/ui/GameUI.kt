package com.example.ui

import android.annotation.SuppressLint
import android.opengl.GLSurfaceView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ar.CameraRenderer
import com.example.sync.GameMode
import com.example.sync.GameState
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

// Neon theme colors
val NeonGreen = Color(0xFF00FFCC)
val NeonRed = Color(0xFFFF2A6D)
val NeonBlue = Color(0xFF05D9E8)
val DarkSlate = Color(0xFF01012B)
val GlassWhite = Color(0x1AFFFFFF)

@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun GameUI(viewModel: ARDuelViewModel, onExit: () -> Unit) {
    val context = LocalContext.current

    // Game stats
    val gameState by viewModel.syncManager.gameState.collectAsState()
    val gameMode by viewModel.syncManager.gameMode.collectAsState()
    val roomId by viewModel.syncManager.roomId.collectAsState()
    val localPlayer by viewModel.syncManager.localPlayer.collectAsState()
    val opponentPlayer by viewModel.syncManager.opponentPlayer.collectAsState()

    // HUD feedbacks
    val isShooting by viewModel.isShootingLocal.collectAsState()
    val hitMarker by viewModel.showHitMarker.collectAsState()
    val occlusionMsg by viewModel.occlusionStatusMessage.collectAsState()

    // Projected coordinate of the opponent
    var opponentScreenX by remember { mutableStateOf<Float?>(null) }
    var opponentScreenY by remember { mutableStateOf<Float?>(null) }
    var distanceToOpponent by remember { mutableStateOf(0f) }

    // Dynamic scale for local shoot recoil
    val shootScale by animateFloatAsState(
        targetValue = if (isShooting) 1.25f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
    )

    // Damage flash trigger (when local player gets hit)
    var flashActive by remember { mutableStateOf(false) }
    LaunchedEffect(key1 = localPlayer.health) {
        if (localPlayer.health < 100 && localPlayer.health > 0) {
            flashActive = true
            kotlinx.coroutines.delay(150)
            flashActive = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkSlate)
    ) {
        if (gameState == GameState.LOBBY) {
            // LOBBY CONTROL PANEL
            LobbyUI(viewModel)
        } else {
            // FULL SCREEN CAMERA + REAL-TIME AR VIEW
            AndroidView(
                factory = { ctx ->
                    GLSurfaceView(ctx).apply {
                        setEGLContextClientVersion(2)
                        
                        // Setup Camera Renderer and AR Session
                        val cameraRenderer = CameraRenderer()
                        
                        setRenderer(object : GLSurfaceView.Renderer {
                            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                                cameraRenderer.init()
                                viewModel.arSessionManager.initializeSession()
                                viewModel.arSessionManager.resumeSession()
                            }

                            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                                viewModel.updateViewport(width, height)
                                viewModel.arSessionManager.session?.setDisplayGeometry(0, width, height)
                            }

                            override fun onDrawFrame(gl: GL10?) {
                                val frame = viewModel.arSessionManager.updateFrame(cameraRenderer.textureId) ?: return
                                if (frame.camera.trackingState == TrackingState.TRACKING) {
                                    cameraRenderer.draw(viewModel.arSessionManager.getTransformedTexCoords())
                                    
                                    // Periodic coordinate projection and update
                                    viewModel.updateLocalPose(frame)

                                    // Obtain matrices
                                    val projMatrix = FloatArray(16)
                                    val viewMatrix = FloatArray(16)
                                    frame.camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)
                                    frame.camera.getViewMatrix(viewMatrix, 0)

                                    // Project Opponent position
                                    val oppLocalPos = viewModel.getOpponentLocalPosition()
                                    val camPose = frame.camera.pose
                                    val dist = sqrt(
                                        (oppLocalPos[0] - camPose.tx()) * (oppLocalPos[0] - camPose.tx()) +
                                        (oppLocalPos[1] - camPose.ty()) * (oppLocalPos[1] - camPose.ty()) +
                                        (oppLocalPos[2] - camPose.tz()) * (oppLocalPos[2] - camPose.tz())
                                    )
                                    distanceToOpponent = dist

                                    val projected = project3DTo2D(
                                        oppLocalPos,
                                        viewMatrix,
                                        projMatrix,
                                        viewModel.viewWidth,
                                        viewModel.viewHeight
                                    )

                                    if (projected != null) {
                                        opponentScreenX = projected.first
                                        opponentScreenY = projected.second
                                    } else {
                                        opponentScreenX = null
                                        opponentScreenY = null
                                    }

                                    // Host cloud anchor auto-generation on host play start
                                    if (viewModel.syncManager.isHost && 
                                        viewModel.syncManager.gameMode.value == GameMode.ONLINE_MULTIPLAYER && 
                                        viewModel.syncManager.cloudAnchorId.value.isNullOrEmpty() &&
                                        viewModel.localAnchor == null) {
                                        
                                        // Place an anchor 1.2 meters in front of host camera to sync coordinate origins
                                        val hostPose = camPose.compose(Pose.makeTranslation(0f, 0f, -1.2f))
                                        val anchor = viewModel.arSessionManager.session?.createAnchor(hostPose)
                                        if (anchor != null) {
                                            viewModel.localAnchor = anchor
                                            viewModel.arSessionManager.hostCloudAnchor(anchor) { cid, success ->
                                                if (success && cid != null) {
                                                    viewModel.syncManager.setRoomCloudAnchor(cid)
                                                }
                                            }
                                        }
                                    }

                                    // Client cloud anchor auto-resolving
                                    if (!viewModel.syncManager.isHost && 
                                        viewModel.syncManager.gameMode.value == GameMode.ONLINE_MULTIPLAYER && 
                                        !viewModel.syncManager.cloudAnchorId.value.isNullOrEmpty() &&
                                        viewModel.localAnchor == null) {
                                        
                                        val cid = viewModel.syncManager.cloudAnchorId.value!!
                                        viewModel.arSessionManager.resolveCloudAnchor(cid) { resolvedAnchor, success ->
                                            if (success && resolvedAnchor != null) {
                                                viewModel.localAnchor = resolvedAnchor
                                                viewModel.syncManager.updateRoomState(GameState.PLAYING)
                                            }
                                        }
                                    }
                                }
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // DYNAMIC GLASS HUD OVERLAY
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(WindowInsets.safeDrawing.asPaddingValues())
            ) {
                // Exit Game Button (Top Left)
                IconButton(
                    onClick = {
                        viewModel.syncManager.resetGame()
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        .testTag("exit_game_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "الرجوع للرئيسية",
                        tint = Color.White
                    )
                }

                // Local Health Bar (Top Right)
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .width(180.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = localPlayer.name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { localPlayer.health / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        color = if (localPlayer.health > 40) NeonGreen else NeonRed,
                        trackColor = Color.DarkGray
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "صحتك: %${localPlayer.health}",
                        color = if (localPlayer.health > 40) NeonGreen else NeonRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Room Info (Top Center)
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (gameMode == GameMode.LOCAL_AI_SIMULATION) "طور التدريب المنفرد" else "رمز الغرفة: ${roomId ?: ""}",
                        color = NeonBlue,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                // 3D FLOATING TARGET CARD FOLLOWS OPPONENT SCREEN COORDS
                val opX = opponentScreenX
                val opY = opponentScreenY
                if (opX != null && opY != null) {
                    Box(
                        modifier = Modifier
                            .absoluteOffset(
                                x = (opX / context.resources.displayMetrics.density).dp - 80.dp,
                                y = (opY / context.resources.displayMetrics.density).dp - 60.dp
                            )
                            .size(160.dp, 120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Futuristic Target HUD Corner Brackets
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val bracketLength = 20f
                            val strokeWidth = 5f
                            val color = if (opponentPlayer.health > 30) NeonGreen else NeonRed

                            // Top-Left
                            drawLine(color, Offset(0f, 0f), Offset(bracketLength, 0f), strokeWidth)
                            drawLine(color, Offset(0f, 0f), Offset(0f, bracketLength), strokeWidth)

                            // Top-Right
                            drawLine(color, Offset(w, 0f), Offset(w - bracketLength, 0f), strokeWidth)
                            drawLine(color, Offset(w, 0f), Offset(w, bracketLength), strokeWidth)

                            // Bottom-Left
                            drawLine(color, Offset(0f, h), Offset(bracketLength, h), strokeWidth)
                            drawLine(color, Offset(0f, h), Offset(0f, h - bracketLength), strokeWidth)

                            // Bottom-Right
                            drawLine(color, Offset(w, h), Offset(w - bracketLength, h), strokeWidth)
                            drawLine(color, Offset(w, h), Offset(w, h - bracketLength), strokeWidth)
                        }

                        // Info details
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                        ) {
                            Text(
                                text = opponentPlayer.name,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                style = TextStyle(
                                    shadow = Shadow(Color.Black, Offset(2f, 2f), 4f)
                                )
                            )
                            Text(
                                text = String.format("%.1f م", distanceToOpponent),
                                color = NeonBlue,
                                fontWeight = FontWeight.Medium,
                                fontSize = 11.sp,
                                style = TextStyle(
                                    shadow = Shadow(Color.Black, Offset(2f, 2f), 4f)
                                )
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            // Opponent Mini Health Bar
                            LinearProgressIndicator(
                                progress = { opponentPlayer.health / 100f },
                                modifier = Modifier
                                    .width(80.dp)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = if (opponentPlayer.health > 30) NeonGreen else NeonRed,
                                trackColor = Color.DarkGray
                            )
                        }
                    }
                }

                // FIXED RETICLE / CROSSHAIR AT CENTER OF SCREEN
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = 3f
                        // Glowing target ring
                        drawCircle(
                            color = NeonBlue.copy(alpha = 0.8f),
                            radius = size.minDimension / 3f,
                            style = Stroke(width = stroke)
                        )
                        // Precise center dot
                        drawCircle(
                            color = NeonRed,
                            radius = 6f
                        )
                        // Firing lines
                        drawLine(NeonBlue.copy(alpha = 0.8f), Offset(size.width / 2f, 0f), Offset(size.width / 2f, 15f), stroke)
                        drawLine(NeonBlue.copy(alpha = 0.8f), Offset(size.width / 2f, size.height), Offset(size.width / 2f, size.height - 15f), stroke)
                        drawLine(NeonBlue.copy(alpha = 0.8f), Offset(0f, size.height / 2f), Offset(15f, size.height / 2f), stroke)
                        drawLine(NeonBlue.copy(alpha = 0.8f), Offset(size.width, size.height / 2f), Offset(size.width - 15f, size.height / 2f), stroke)
                    }
                }

                // HIT MARKER ANIMATED INDICATOR
                val marker = hitMarker
                if (marker != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(bottom = 120.dp)
                    ) {
                        Text(
                            text = marker.text,
                            color = NeonRed,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 24.sp,
                            style = TextStyle(
                                shadow = Shadow(Color.Black, Offset(2f, 2f), 8f)
                            )
                        )
                    }
                }

                // WALL OCCLUSION NOTIFICATION
                val occlusionMsgText = occlusionMsg
                if (!occlusionMsgText.isNullOrEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(top = 160.dp)
                            .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                            .border(1.dp, NeonRed, RoundedCornerShape(8.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = occlusionMsgText,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }

                // LARGE CIRCULAR RED SHOOT BUTTON WITH DYNAMIC SCALE ON CLICK
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 48.dp, end = 32.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.arSessionManager.session?.update()?.let { f ->
                                viewModel.fireWeapon(f)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonRed),
                        shape = CircleShape,
                        modifier = Modifier
                            .size(85.dp)
                            .scale(shootScale)
                            .testTag("shoot_button")
                    ) {
                        // Custom vector icon shape inside button
                        Canvas(modifier = Modifier.size(36.dp)) {
                            drawCircle(
                                color = Color.White,
                                radius = size.minDimension / 2f,
                                style = Stroke(width = 4f)
                            )
                            drawCircle(
                                color = Color.White,
                                radius = size.minDimension / 6f
                            )
                        }
                    }
                }
            }
        }

        // RED DAMAGE FLASH EDGE EFFECT
        if (flashActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(12.dp, NeonRed, RoundedCornerShape(0.dp))
            )
        }

        // GAME OVER STATE OVERLAY
        val currentLocalPlayer = localPlayer
        val currentOpponentPlayer = opponentPlayer
        if (gameState == GameState.GAME_OVER || currentLocalPlayer.health <= 0 || currentOpponentPlayer.health <= 0) {
            val isVictory = currentLocalPlayer.health > currentOpponentPlayer.health
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = if (isVictory) "نصر مؤزر!" else "لقد هُزمت!",
                        color = if (isVictory) NeonGreen else NeonRed,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (isVictory) "تم القضاء على الخصم بنجاح!" else "الخصم كان أسرع وقضى عليك.",
                        color = Color.LightGray,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(40.dp))
                    Button(
                        onClick = {
                            viewModel.syncManager.resetGame()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .width(200.dp)
                            .height(50.dp)
                            .testTag("restart_button")
                    ) {
                        Text(
                            text = "العودة للرئيسية",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = DarkSlate
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LobbyUI(viewModel: ARDuelViewModel) {
    var customRoomCode by remember { mutableStateOf("") }
    var onlineStatusMessage by remember { mutableStateOf<String?>(null) }
    var progressActive by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(DarkSlate, Color(0xFF0C094E))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                .border(1.dp, NeonBlue.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App Title Logo
            Text(
                text = "الاشتباك المعزز",
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                color = NeonBlue,
                style = TextStyle(
                    shadow = Shadow(NeonBlue, Offset(0f, 0f), 10f)
                )
            )
            Text(
                text = "1v1 REALTIME AR FIELD",
                fontSize = 12.sp,
                color = Color.LightGray.copy(alpha = 0.6f),
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(32.dp))

            // SOLO TRAINING MODE (Local AI Simulation)
            Button(
                onClick = {
                    viewModel.syncManager.startLocalAISimulation()
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("solo_ai_button"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = DarkSlate)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "بدء تدريب منفرد (AI Bot)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkSlate
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            HorizontalDivider(color = Color.White.copy(alpha = 0.15f), thickness = 1.dp)

            Spacer(modifier = Modifier.height(16.dp))

            // ONLINE MULTIPLAYER MODE (Firebase Sync)
            Text(
                text = "اللعب المتعدد (أونلاين عبر Firebase)",
                color = Color.LightGray,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            OutlinedTextField(
                value = customRoomCode,
                onValueChange = { customRoomCode = it },
                label = { Text("رمز الغرفة (رقمي)", color = Color.Gray) },
                placeholder = { Text("مثال: 4892", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = NeonBlue,
                    unfocusedBorderColor = Color.DarkGray,
                    focusedContainerColor = GlassWhite,
                    unfocusedContainerColor = GlassWhite
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("room_code_input")
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        progressActive = true
                        viewModel.syncManager.createRoom { code, err ->
                            progressActive = false
                            if (err != null) {
                                onlineStatusMessage = err
                            } else {
                                onlineStatusMessage = "تم إنشاء الغرفة $code. بانتظار الخصم للمزامنة..."
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("create_room_button")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("إنشاء غرفة", fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = {
                        if (customRoomCode.isEmpty()) {
                            onlineStatusMessage = "يرجى إدخال رمز الغرفة أولاً!"
                            return@Button
                        }
                        progressActive = true
                        viewModel.syncManager.joinRoom(customRoomCode) { success, err ->
                            progressActive = false
                            if (success) {
                                onlineStatusMessage = "تم الاتصال بالغرفة. جاري معايرة مساحة AR..."
                            } else {
                                onlineStatusMessage = err ?: "فشل الانضمام للغرفة"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("join_room_button")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("انضمام للغرفة", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // LOADING PROGRESS OR ERROR MESSAGES
            if (progressActive) {
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator(color = NeonBlue)
            }

            val msg = onlineStatusMessage
            if (!msg.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = msg,
                    color = if (msg.contains("فشل") || msg.contains("خطأ") || msg.contains("يرجى")) NeonRed else NeonGreen,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
}

/**
 * Math implementation to project a 3D coordinate [x, y, z] to 2D Screen Space (pixel coordinates).
 */
fun project3DTo2D(
    pos: FloatArray,
    viewMatrix: FloatArray,
    projectionMatrix: FloatArray,
    width: Int,
    height: Int
): Pair<Float, Float>? {
    // 1. Transform World Pos to Camera Space: View * World
    val cx = viewMatrix[0] * pos[0] + viewMatrix[4] * pos[1] + viewMatrix[8] * pos[2] + viewMatrix[12]
    val cy = viewMatrix[1] * pos[0] + viewMatrix[5] * pos[1] + viewMatrix[9] * pos[2] + viewMatrix[13]
    val cz = viewMatrix[2] * pos[0] + viewMatrix[6] * pos[1] + viewMatrix[10] * pos[2] + viewMatrix[14]
    val cw = viewMatrix[3] * pos[0] + viewMatrix[7] * pos[1] + viewMatrix[11] * pos[2] + viewMatrix[15]

    // Point must be in front of the camera (negative z-axis in OpenGL eye-space coords)
    if (cz >= 0f) return null

    // 2. Transform Camera Space to Clip Space: Projection * Camera
    val px = projectionMatrix[0] * cx + projectionMatrix[4] * cy + projectionMatrix[8] * cz + projectionMatrix[12] * cw
    val py = projectionMatrix[1] * cx + projectionMatrix[5] * cy + projectionMatrix[13] * cz + projectionMatrix[13] * cw
    val pz = projectionMatrix[2] * cx + projectionMatrix[6] * cy + projectionMatrix[10] * cz + projectionMatrix[14] * cw
    val pw = projectionMatrix[3] * cx + projectionMatrix[7] * cy + projectionMatrix[11] * cz + projectionMatrix[15] * cw

    if (pw == 0f) return null

    // 3. Convert to NDC (Normalized Device Coordinates) [-1, 1]
    val ndcX = px / pw
    val ndcY = py / pw

    // 4. Convert NDC to Pixel Screen Space
    val screenX = (ndcX + 1f) * 0.5f * width
    val screenY = (1f - ndcY) * 0.5f * height

    return Pair(screenX, screenY)
}
