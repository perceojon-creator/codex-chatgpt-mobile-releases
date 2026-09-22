package com.codex.chat.ui.voice

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renderer OpenGL ES 2.0 para el orbe volumétrico holográfico de voz (OpenAI Horizon Orb).
 * Modula la rotación y distorsión geométrica en función de la amplitud de audio recibida.
 */
class HorizonOrbRenderer(private val context: Context) : GLSurfaceView.Renderer {

    @Volatile
    private var audioLevel: Float = 0f

    private var programId: Int = 0
    private var positionHandle: Int = 0
    private var timeHandle: Int = 0
    private var audioLevelHandle: Int = 0

    private var startTime: Long = System.currentTimeMillis()
    private var vertexBuffer: FloatBuffer

    companion object {
        // Quad de pantalla completa para fragment shader procedural
        private val QUAD_COORDINATES = floatArrayOf(
            -1.0f,  1.0f,
            -1.0f, -1.0f,
             1.0f,  1.0f,
             1.0f, -1.0f
        )
    }

    init {
        val bb = ByteBuffer.allocateDirect(QUAD_COORDINATES.size * 4)
        bb.order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer()
        vertexBuffer.put(QUAD_COORDINATES)
        vertexBuffer.position(0)
    }

    fun setAudioLevel(level: Float) {
        this.audioLevel = level.coerceIn(0.0f, 1.0f)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.05f, 0.05f, 1.0f)

        // Shaders estándar compatibles con GL ES 2.0
        val vShaderCode = """
            attribute vec4 a_Position;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = (a_Position.xy + 1.0) * 0.5;
            }
        """.trimIndent()

        val fShaderCode = """
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform float u_time;
            uniform float u_audio_level;

            void main() {
                vec2 uv = v_TexCoord - 0.5;
                float dist = length(uv);
                
                // Pulsación basada en tiempo y amplitud de voz
                float pulse = 0.28 + 0.08 * sin(u_time * 2.5) + (u_audio_level * 0.12);
                float glow = smoothstep(pulse + 0.05, pulse - 0.08, dist);
                
                // Color gradiente esmeralda OpenAI a cian etéreo
                vec3 colorInner = vec3(0.063, 0.639, 0.498); // #10A37F
                vec3 colorOuter = vec3(0.22, 0.74, 0.97);    // #38BDF8
                vec3 finalColor = mix(colorOuter, colorInner, dist / pulse) * glow;
                
                gl_FragColor = vec4(finalColor, glow);
            }
        """.trimIndent()

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fShaderCode)

        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)

        positionHandle = GLES20.glGetAttribLocation(programId, "a_Position")
        timeHandle = GLES20.glGetUniformLocation(programId, "u_time")
        audioLevelHandle = GLES20.glGetUniformLocation(programId, "u_audio_level")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (programId == 0) return

        GLES20.glUseProgram(programId)

        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0f
        GLES20.glUniform1f(timeHandle, elapsed)
        GLES20.glUniform1f(audioLevelHandle, audioLevel)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }
}
