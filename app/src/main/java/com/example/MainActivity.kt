package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.example.ui.DarkSlate
import com.example.ui.GameUI
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.NeonBlue
import com.example.ui.NeonRed
import com.example.ui.ARDuelViewModel
import com.example.sync.GameState

class MainActivity : ComponentActivity() {

    private val viewModel: ARDuelViewModel by viewModels()

    // State to track if Camera permission is granted
    private val hasCameraPermission = mutableStateOf(false)

    // Camera permission request launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasCameraPermission.value = isGranted
        if (!isGranted) {
            Toast.makeText(this, "مطلوب إذن الكاميرا لتشغيل الواقع المعزز AR!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)
        enableEdgeToEdge()

        // Verify initial permission status
        checkCameraPermission()

        setContent {
            MyApplicationTheme {
                val gameState by viewModel.syncManager.gameState.collectAsState()
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = if (gameState == GameState.LOBBY) MaterialTheme.colorScheme.background else Color.Transparent,
                    contentWindowInsets = WindowInsets.navigationBars
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (hasCameraPermission.value) {
                            // Launch AR Shooting duel game overlay
                            GameUI(viewModel = viewModel, onExit = { finish() })
                        } else {
                            // Permission Request Landing Page
                            PermissionDeniedScreen(
                                onRequestPermission = {
                                    requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkCameraPermission() {
        hasCameraPermission.value = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        // If permission is already granted, safely resume the ARCore Session
        if (hasCameraPermission.value) {
            viewModel.arSessionManager.resumeSession()
        }
    }

    override fun onPause() {
        super.onPause()
        // Always pause ARCore session to conserve battery and free the camera
        viewModel.arSessionManager.pauseSession()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.arSessionManager.onDestroy()
    }
}

@Composable
fun PermissionDeniedScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkSlate),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = NeonRed,
                modifier = Modifier.size(72.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "مطلوب إذن الكاميرا",
                fontSize = 22.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "يستخدم هذا التطبيق كاميرا الهاتف لعرض ساحة المعركة الافتراضية بالواقع المعزز (AR) ومزامنة حركات اللاعبين. يرجى تفعيل الإذن للمتابعة.",
                fontSize = 14.sp,
                color = Color.LightGray,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(50.dp)
                    .testTag("request_permission_button")
            ) {
                Text(
                    text = "منح الإذن الآن",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkSlate
                )
            }
        }
    }
}
