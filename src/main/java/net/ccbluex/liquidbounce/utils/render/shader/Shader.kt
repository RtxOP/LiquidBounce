/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.utils.render.shader

import net.ccbluex.liquidbounce.utils.client.ClientUtils.LOGGER
import net.ccbluex.liquidbounce.utils.client.MinecraftInstance
import org.apache.commons.io.IOUtils
import org.lwjgl.opengl.*
import org.lwjgl.opengl.ARBShaderObjects.*
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL20.glGetUniformLocation
import org.lwjgl.opengl.GL20.glUseProgram
import java.io.File
import java.io.IOException
import java.nio.file.Files

abstract class Shader : MinecraftInstance {
    var programId = 0
        private set

    val loaded: Boolean
        get() = programId != 0
    
    private val uniformsMap = mutableMapOf<String, Int>()

    constructor(fragmentShader: String) {
        val vertexShaderID: Int
        val fragmentShaderID: Int
        
        try {
            val vertexStream = javaClass.getResourceAsStream("/assets/minecraft/liquidbounce/shader/vertex.vert")
            vertexShaderID = createShader(IOUtils.toString(vertexStream), ARBVertexShader.GL_VERTEX_SHADER_ARB)
            IOUtils.closeQuietly(vertexStream)
            
            val fragmentStream = javaClass.getResourceAsStream("/assets/minecraft/liquidbounce/shader/fragment/$fragmentShader")
            fragmentShaderID = createShader(IOUtils.toString(fragmentStream), ARBFragmentShader.GL_FRAGMENT_SHADER_ARB)
            IOUtils.closeQuietly(fragmentStream)
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }
        
        if (vertexShaderID == 0 || fragmentShaderID == 0)
            return
        
        programId = linkProgram(fragmentShader, vertexShaderID, fragmentShaderID)
    }

    @Throws(IOException::class)
    constructor(fragmentShader: File) {
        val vertexShaderID: Int
        val fragmentShaderID: Int
        
        val vertexStream = javaClass.getResourceAsStream("/assets/minecraft/liquidbounce/shader/vertex.vert")
        vertexShaderID = createShader(IOUtils.toString(vertexStream), ARBVertexShader.GL_VERTEX_SHADER_ARB)
        IOUtils.closeQuietly(vertexStream)
        
        val fragmentStream = Files.newInputStream(fragmentShader.toPath())
        fragmentShaderID = createShader(IOUtils.toString(fragmentStream), ARBFragmentShader.GL_FRAGMENT_SHADER_ARB)
        IOUtils.closeQuietly(fragmentStream)
        
        if (vertexShaderID == 0 || fragmentShaderID == 0)
            return
        
        programId = linkProgram(fragmentShader.name, vertexShaderID, fragmentShaderID)
    }

    open fun startShader() {
        glPushMatrix()
        glUseProgram(programId)

        if (uniformsMap.isEmpty())
            setupUniforms()

        updateUniforms()
    }

    open fun stopShader() {
        glUseProgram(0)
        glPopMatrix()
    }

    abstract fun setupUniforms()
    abstract fun updateUniforms()

    private fun linkProgram(shaderName: String, vertexShaderID: Int, fragmentShaderID: Int): Int {
        val shaderProgram = glCreateProgramObjectARB()

        if (shaderProgram == 0)
            return 0

        glAttachObjectARB(shaderProgram, vertexShaderID)
        glAttachObjectARB(shaderProgram, fragmentShaderID)

        glLinkProgramARB(shaderProgram)

        if (glGetObjectParameteriARB(shaderProgram, GL_OBJECT_LINK_STATUS_ARB) == GL_FALSE) {
            LOGGER.error("[Shader] Failed to link $shaderName: ${getLogInfo(shaderProgram)}")
            glDeleteObjectARB(shaderProgram)
            return 0
        }

        glValidateProgramARB(shaderProgram)

        if (glGetObjectParameteriARB(shaderProgram, GL_OBJECT_VALIDATE_STATUS_ARB) == GL_FALSE) {
            LOGGER.warn("[Shader] Validation warning for $shaderName: ${getLogInfo(shaderProgram)}")
        }

        LOGGER.info("[Shader] Successfully loaded: $shaderName")
        return shaderProgram
    }

    private fun createShader(shaderSource: String, shaderType: Int): Int {
        var shader = 0

        return try {
            shader = glCreateShaderObjectARB(shaderType)

            if (shader == 0)
                return 0

            glShaderSourceARB(shader, shaderSource)
            glCompileShaderARB(shader)

            if (glGetObjectParameteriARB(shader, GL_OBJECT_COMPILE_STATUS_ARB) == GL_FALSE)
                throw RuntimeException("Error creating shader: " + getLogInfo(shader))

            shader
        } catch (e: Exception) {
            glDeleteObjectARB(shader)
            throw e
        }
    }

    private fun getLogInfo(i: Int) = glGetInfoLogARB(i, glGetObjectParameteriARB(i, GL_OBJECT_INFO_LOG_LENGTH_ARB))

    fun setUniform(uniformName: String, location: Int) {
        uniformsMap[uniformName] = location
    }

    fun setupUniform(uniformName: String) = setUniform(uniformName, glGetUniformLocation(programId, uniformName))

    fun getUniform(uniformName: String) = uniformsMap[uniformName]!!
}
