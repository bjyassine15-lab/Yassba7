package com.example.ar

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class CameraRenderer {

    private val vertexShaderCode = """
        attribute vec4 position;
        attribute vec2 texCoords;
        varying vec2 vTexCoords;
        void main() {
            gl_Position = position;
            vTexCoords = texCoords;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoords;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTexCoords);
        }
    """.trimIndent()

    private var program: Int = 0
    private var positionHandle: Int = -1
    private var texCoordsHandle: Int = -1
    private var textureHandle: Int = -1

    var textureId: Int = -1
        private set

    private val quadVertices: FloatBuffer
    private val quadTexCoords: FloatBuffer

    init {
        // Simple full screen quad [-1, -1] to [1, 1]
        val vertices = floatArrayOf(
            -1.0f, -1.0f, 0.0f,
            -1.0f,  1.0f, 0.0f,
             1.0f, -1.0f, 0.0f,
             1.0f,  1.0f, 0.0f
        )
        quadVertices = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply {
                put(vertices)
                position(0)
            }

        // Texture coordinates
        val texCoords = floatArrayOf(
            0.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 1.0f,
            1.0f, 0.0f
        )
        quadTexCoords = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply {
                put(texCoords)
                position(0)
            }
    }

    fun init() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        program = GLES20.glCreateProgram().apply {
            GLES20.glAttachShader(this, vertexShader)
            GLES20.glAttachShader(this, fragmentShader)
            GLES20.glLinkProgram(this)
        }

        positionHandle = GLES20.glGetAttribLocation(program, "position")
        texCoordsHandle = GLES20.glGetAttribLocation(program, "texCoords")
        textureHandle = GLES20.glGetUniformLocation(program, "sTexture")
    }

    fun draw(transformedTexCoords: FloatBuffer) {
        GLES20.glUseProgram(program)

        // Set attributes
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, quadVertices)

        GLES20.glEnableVertexAttribArray(texCoordsHandle)
        GLES20.glVertexAttribPointer(texCoordsHandle, 2, GLES20.GL_FLOAT, false, 0, transformedTexCoords)

        // Set uniform
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureHandle, 0)

        // Clear and draw
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordsHandle)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }
}
