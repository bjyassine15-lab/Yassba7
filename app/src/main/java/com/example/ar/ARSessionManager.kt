package com.example.ar

import android.content.Context
import android.media.Image
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ARSessionManager(private val context: Context) {

    var session: Session? = null
        private set

    var isSessionActive = AtomicBoolean(false)
        private set

    var isDepthSupported = false
        private set

    private var transformedTexCoords: FloatBuffer

    init {
        val uv = floatArrayOf(
            0.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 1.0f,
            1.0f, 0.0f
        )
        transformedTexCoords = ByteBuffer.allocateDirect(uv.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply {
                put(uv)
                position(0)
            }
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Attempts to initialize the ARCore Session.
     */
    fun initializeSession(): Session? {
        if (session != null) return session

        val availability = com.google.ar.core.ArCoreApk.getInstance().checkAvailability(context)
        Log.d("ARSessionManager", "ARCore Availability: $availability")
        if (availability == com.google.ar.core.ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE) {
            showToast("هذا الجهاز غير متوافق مع خدمات الواقع المعزز ARCore!")
            return null
        }

        try {
            val arSession = Session(context)
            val config = arSession.config

            // 1. Enable Depth API (for obstacle occlusion check)
            if (arSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                config.depthMode = Config.DepthMode.AUTOMATIC
                isDepthSupported = true
                Log.d("ARSessionManager", "Depth Mode AUTOMATIC supported and enabled.")
            } else {
                config.depthMode = Config.DepthMode.DISABLED
                isDepthSupported = false
                Log.w("ARSessionManager", "Depth Mode not supported on this device.")
            }

            // 2. Enable Cloud Anchors (for shared multiplayer coordinate space)
            config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED

            // 3. Set Autofocus
            config.focusMode = Config.FocusMode.AUTO

            arSession.configure(config)
            this.session = arSession
            return arSession
        } catch (e: UnavailableArcoreNotInstalledException) {
            showToast("فشل تهيئة ARCore: لم يتم تثبيت خدمات ARCore!")
            Log.e("ARSessionManager", "ARCore services not installed", e)
            return null
        } catch (e: UnavailableApkTooOldException) {
            showToast("فشل تهيئة ARCore: نسخة خدمات ARCore قديمة جداً!")
            Log.e("ARSessionManager", "ARCore APK too old", e)
            return null
        } catch (e: UnavailableSdkTooOldException) {
            showToast("فشل تهيئة ARCore: نسخة الـ SDK قديمة جداً!")
            Log.e("ARSessionManager", "ARCore SDK too old", e)
            return null
        } catch (e: UnavailableDeviceNotCompatibleException) {
            showToast("فشل تهيئة ARCore: هذا الجهاز غير مدعوم للواقع المعزز!")
            Log.e("ARSessionManager", "Device not compatible with ARCore", e)
            return null
        } catch (e: UnavailableUserDeclinedInstallationException) {
            showToast("فشل تهيئة ARCore: تم رفض تثبيت أو تحديث خدمات ARCore!")
            Log.e("ARSessionManager", "User declined ARCore installation", e)
            return null
        } catch (e: Exception) {
            showToast("خطأ غير متوقع في تهيئة AR: ${e.localizedMessage}")
            Log.e("ARSessionManager", "Failed to initialize ARCore Session", e)
            return null
        }
    }

    fun resumeSession() {
        session?.let {
            if (!isSessionActive.get()) {
                try {
                    it.resume()
                    isSessionActive.set(true)
                } catch (e: UnavailableException) {
                    showToast("فشل تشغيل كاميرا ARCore!")
                    Log.e("ARSessionManager", "Playback failed", e)
                } catch (e: Exception) {
                    showToast("خطأ أثناء استئناف جلسة AR: ${e.localizedMessage}")
                    Log.e("ARSessionManager", "Error resuming ARCore session", e)
                }
            }
        }
    }

    fun pauseSession() {
        session?.let {
            if (isSessionActive.get()) {
                try {
                    it.pause()
                    isSessionActive.set(false)
                } catch (e: Exception) {
                    Log.e("ARSessionManager", "Error pausing ARCore session", e)
                }
            }
        }
    }

    fun onDestroy() {
        session?.close()
        session = null
        isSessionActive.set(false)
    }

    /**
     * Update the texture coordinates and returns the current frame.
     */
    fun updateFrame(textureId: Int): Frame? {
        val arSession = session ?: return null
        if (!isSessionActive.get()) return null

        try {
            arSession.setCameraTextureName(textureId)
            val frame = arSession.update()

            // Update UV coordinates to match viewport size / aspect ratio
            frame.transformDisplayUvCoords(
                transformedTexCoords,
                transformedTexCoords
            )
            return frame
        } catch (e: Exception) {
            Log.e("ARSessionManager", "Failed to update AR frame", e)
            return null
        }
    }

    fun getTransformedTexCoords(): FloatBuffer {
        transformedTexCoords.position(0)
        return transformedTexCoords
    }

    /**
     * Checks depth at screen center to check for wall occlusion.
     * Returns depth in meters, or negative if invalid or depth is not available.
     */
    fun getDepthAtCenter(frame: Frame): Float {
        if (!isDepthSupported) return -1f

        var depthImage: Image? = null
        try {
            depthImage = frame.acquireDepthImage16Bits()
            val width = depthImage.width
            val height = depthImage.height

            // Screen center corresponds to pixel center in depth map
            val centerX = width / 2
            val centerY = height / 2

            // Depth image format: DEPTH16 (plane 0)
            val plane = depthImage.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            // Position at (centerX, centerY)
            val offset = centerY * rowStride + centerX * pixelStride
            buffer.position(offset)

            // Read 16-bit short value
            val byte1 = buffer.get().toInt() and 0xFF
            val byte2 = buffer.get().toInt() and 0xFF
            val depthMillimeters = (byte2 shl 8) or byte1

            // Convert to meters
            return depthMillimeters / 1000.0f
        } catch (e: Exception) {
            Log.e("ARSessionManager", "Failed to sample depth image", e)
            return -1f
        } finally {
            depthImage?.close()
        }
    }

    /**
     * Hosts an anchor to ARCore Cloud Anchors.
     */
    fun hostCloudAnchor(anchor: Anchor, onCompleted: (String?, Boolean) -> Unit) {
        val arSession = session ?: return onCompleted(null, false)
        try {
            // Initiate hosting
            val cloudAnchor = arSession.hostCloudAnchor(anchor)
            
            // Monitor in a separate thread/coroutine or simple callback poll
            // For a robust implementation, we monitor on frame update, but we can do a simple checker
            Thread {
                var finished = false
                var attempts = 0
                while (!finished && attempts < 100) {
                    Thread.sleep(500)
                    attempts++
                    val state = cloudAnchor.cloudAnchorState
                    if (state.isError) {
                        Log.e("ARSessionManager", "Cloud anchor hosting failed: $state")
                        onCompleted(null, false)
                        finished = true
                    } else if (state == Anchor.CloudAnchorState.SUCCESS) {
                        Log.i("ARSessionManager", "Cloud anchor hosted successfully! ID: ${cloudAnchor.cloudAnchorId}")
                        onCompleted(cloudAnchor.cloudAnchorId, true)
                        finished = true
                    }
                }
                if (!finished) {
                    onCompleted(null, false)
                }
            }.start()
        } catch (e: Exception) {
            Log.e("ARSessionManager", "Exception hosting cloud anchor", e)
            onCompleted(null, false)
        }
    }

    /**
     * Resolves a Cloud Anchor ID.
     */
    fun resolveCloudAnchor(cloudAnchorId: String, onCompleted: (Anchor?, Boolean) -> Unit) {
        val arSession = session ?: return onCompleted(null, false)
        try {
            val cloudAnchor = arSession.resolveCloudAnchor(cloudAnchorId)
            Thread {
                var finished = false
                var attempts = 0
                while (!finished && attempts < 120) {
                    Thread.sleep(500)
                    attempts++
                    val state = cloudAnchor.cloudAnchorState
                    if (state.isError) {
                        Log.e("ARSessionManager", "Cloud anchor resolving failed: $state")
                        onCompleted(null, false)
                        finished = true
                    } else if (state == Anchor.CloudAnchorState.SUCCESS) {
                        Log.i("ARSessionManager", "Cloud anchor resolved successfully!")
                        onCompleted(cloudAnchor, true)
                        finished = true
                    }
                }
                if (!finished) {
                    onCompleted(null, false)
                }
            }.start()
        } catch (e: Exception) {
            Log.e("ARSessionManager", "Exception resolving cloud anchor", e)
            onCompleted(null, false)
        }
    }
}
