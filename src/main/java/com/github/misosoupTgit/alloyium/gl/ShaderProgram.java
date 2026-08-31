package com.github.misosoupTgit.alloyium.gl;

import com.github.misosoupTgit.alloyium.Alloyium;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL43C;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Minimal compute / graphics program loader. */
public final class ShaderProgram implements AutoCloseable {
    private final int programId;

    private ShaderProgram(int programId) {
        this.programId = programId;
    }

    public int id() {
        return programId;
    }

    public void use() {
        GL20C.glUseProgram(programId);
    }

    public int uniformLocation(String name) {
        return GL20C.glGetUniformLocation(programId, name);
    }

    public static ShaderProgram compileCompute(String classpathResource) {
        String src = readResource(classpathResource);
        int shader = GL20C.glCreateShader(GL43C.GL_COMPUTE_SHADER);
        GL20C.glShaderSource(shader, src);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL20C.GL_FALSE) {
            String log = GL20C.glGetShaderInfoLog(shader);
            GL20C.glDeleteShader(shader);
            throw new IllegalStateException("Compute shader compile failed (" + classpathResource + "):\n" + log);
        }

        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, shader);
        GL20C.glLinkProgram(program);
        GL20C.glDetachShader(program, shader);
        GL20C.glDeleteShader(shader);

        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL20C.GL_FALSE) {
            String log = GL20C.glGetProgramInfoLog(program);
            GL20C.glDeleteProgram(program);
            throw new IllegalStateException("Compute program link failed (" + classpathResource + "):\n" + log);
        }
        return new ShaderProgram(program);
    }

    private static String readResource(String path) {
        String full = path.startsWith("/") ? path : "/" + path;
        try (InputStream in = ShaderProgram.class.getResourceAsStream(full)) {
            if (in == null) {
                throw new IllegalStateException("Missing shader resource: " + full);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed reading shader " + full, e);
        }
    }

    @Override
    public void close() {
        if (programId != 0) {
            GL20C.glDeleteProgram(programId);
        }
    }

    public static void logActive(String label) {
        Alloyium.LOGGER.debug("Shader active [{}]: program={}", label, GL20C.glGetInteger(GL20C.GL_CURRENT_PROGRAM));
    }
}
