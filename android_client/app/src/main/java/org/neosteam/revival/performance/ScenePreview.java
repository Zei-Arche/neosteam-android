package org.neosteam.revival.performance;

import android.content.Context;
import android.content.res.AssetManager;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import org.neosteam.revival.BuildConfig;
import org.neosteam.revival.diagnostics.Diagnostics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** A bounded, on-demand GLES scene for exercising the mobile render path. */
public final class ScenePreview extends GLSurfaceView implements GLSurfaceView.Renderer {
    private static final int ASTC_6X6 = 0x93b4;
    private static final int ASTC_SRGB_6X6 = 0x93d4;
    private static final int MAX_ATLAS_SIZE = 2048;
    private static final float[] STATS_CAMERA = {0, 6, 11};
    private static final String VERTEX = "#version 300 es\n"
        + "layout(location=0) in vec3 p; layout(location=1) in vec2 uv;"
        + "uniform mat4 clip; uniform vec3 offset; uniform vec4 tile;"
        + "out vec2 v; out float shade;"
        + "void main(){gl_Position=clip*vec4(p+offset,1.0);"
        + "v=mix(tile.xy,tile.zw,uv);shade=0.78+p.y*0.19;}";
    private static final String FRAGMENT = "#version 300 es\n"
        + "precision mediump float; uniform sampler2D atlas; in vec2 v; in float shade;"
        + "out vec4 outColor; void main(){vec4 sampleColor=texture(atlas,v);"
        + "outColor=vec4(mix(vec3(0.30,0.46,0.50),sampleColor.rgb,sampleColor.a)*shade,1.0);}";

    private final AssetManager assets;
    private final ExecutorService streams;
    private final Consumer<String> feedback;
    private final float[] projection = new float[16];
    private final float[] view = new float[16];
    private final float[] clip = new float[16];
    private final int[] geometry = new int[3];
    private final int[] vertexArrays = new int[3];
    private volatile boolean foreground;
    private final AtomicInteger assetGeneration = new AtomicInteger();
    private volatile boolean trim;
    private volatile boolean reload;
    private volatile boolean suppressAtlas;
    private volatile float yaw;
    private int program;
    private int clipLocation;
    private int offsetLocation;
    private int tileLocation;
    private int texture;
    private int contextGeneration;
    private int atlasBytes;
    private String textureFormat = "Checker";
    private boolean hasAstc;
    private final float[] tile = {0, 0, 1, 1};

    public ScenePreview(Context context, ExecutorService streams, Consumer<String> feedback) {
        super(context);
        this.assets = context.getAssets();
        this.streams = streams;
        this.feedback = feedback;
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    public void setForeground(boolean active) {
        if (active == foreground) return;
        foreground = active;
        if (active) {
            suppressAtlas = false;
            reload = true;
            onResume();
            requestRender();
        } else {
            assetGeneration.incrementAndGet();
            onPause();
        }
    }

    public void rotate(float degrees) {
        yaw = (yaw + degrees) % 360;
        if (foreground) requestRender();
    }

    public void trimResources() {
        assetGeneration.incrementAndGet();
        suppressAtlas = true;
        trim = true;
        if (foreground) requestRender();
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        contextGeneration++;
        program = 0;
        texture = 0;
        atlasBytes = 0;
        trim = false;
        try {
            program = program();
            clipLocation = GLES30.glGetUniformLocation(program, "clip");
            offsetLocation = GLES30.glGetUniformLocation(program, "offset");
            tileLocation = GLES30.glGetUniformLocation(program, "tile");
            GLES30.glGenVertexArrays(3, vertexArrays, 0);
            GLES30.glGenBuffers(3, geometry, 0);
            uploadMesh(0, mesh(0));
            uploadMesh(1, mesh(1));
            uploadMesh(2, mesh(2));
            GLES30.glEnable(GLES30.GL_DEPTH_TEST);
            GLES30.glClearColor(0.04f, 0.10f, 0.17f, 1);
            hasAstc = supportsAstc();
            installChecker();
            reload = foreground && !suppressAtlas;
        } catch (IllegalStateException error) {
            program = 0;
            post(() -> feedback.accept("GLES preview could not initialize"));
        }
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        GLES30.glViewport(0, 0, width, height);
        Matrix.perspectiveM(projection, 0, 51, width / (float) Math.max(1, height), 0.5f, 65);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        if (!foreground || program == 0) return;
        if (trim) {
            trim = false;
            installChecker();
        }
        if (reload) {
            reload = false;
            if (!suppressAtlas && atlasBytes <= 16) requestAtlas(hasAstc);
        }
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        float radians = (float) Math.toRadians(yaw);
        float focusX = (float) Math.sin(radians) * 12;
        float focusZ = STATS_CAMERA[2] - (float) Math.cos(radians) * 23;
        Matrix.setLookAtM(view, 0, STATS_CAMERA[0], STATS_CAMERA[1], STATS_CAMERA[2],
            focusX, 0, focusZ, 0, 1, 0);
        Matrix.multiplyMM(clip, 0, projection, 0, view, 0);
        int[] plan = planFrame(clip, STATS_CAMERA[0], STATS_CAMERA[1], STATS_CAMERA[2]);
        if (plan == null || plan.length < 5) return;
        GLES30.glUseProgram(program);
        GLES30.glUniformMatrix4fv(clipLocation, 1, false, clip, 0);
        GLES30.glUniform4fv(tileLocation, 1, tile, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
        int currentLod = -1;
        int[] lodCounts = new int[3];
        for (int entry = 5; entry + 1 < plan.length; entry += 2) {
            int index = plan[entry];
            int lod = plan[entry + 1];
            if (lod != currentLod) {
                GLES30.glBindVertexArray(vertexArrays[lod]);
                currentLod = lod;
            }
            GLES30.glUniform3f(offsetLocation, (index % 12 - 5.5f) * 2.5f, 0, (index / 12) * -3f);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, lod == 0 ? 36 : lod == 1 ? 12 : 6);
            lodCounts[lod]++;
        }
        String message = "Draws " + plan[0] + " · Triangles " + plan[1]
            + "\nCulled " + plan[2] + " frustum / " + plan[3] + " distance / " + plan[4] + " budget"
            + "\nLOD " + lodCounts[0] + "/" + lodCounts[1] + "/" + lodCounts[2]
            + " · " + textureFormat + " · " + atlasBytes + " B · EGL generation " + contextGeneration;
        post(() -> { if (foreground) feedback.accept(message); });
    }

    private void requestAtlas(boolean astc) {
        int request = assetGeneration.incrementAndGet();
        try {
            streams.execute(() -> {
                if (!foreground || request != assetGeneration.get()) return;
                byte[] data = loadAtlas(assets, astc);
                if (data == null || !foreground || request != assetGeneration.get()) return;
                queueEvent(() -> {
                    if (!foreground || request != assetGeneration.get() || suppressAtlas) return;
                    if (!uploadAtlas(data) && astc) requestAtlas(false);
                    requestRender();
                });
            });
        } catch (RejectedExecutionException ignored) {
            // The scene is closing.
        }
    }

    private boolean uploadAtlas(byte[] bytes) {
        if (bytes.length < 28) return false;
        ByteBuffer source = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int width = source.getInt();
        int height = source.getInt();
        int format = source.getInt();
        int x = source.getInt();
        int y = source.getInt();
        int tileWidth = source.getInt();
        int tileHeight = source.getInt();
        if (width < 1 || height < 1 || width > MAX_ATLAS_SIZE || height > MAX_ATLAS_SIZE
                || x < 0 || y < 0 || tileWidth < 1 || tileHeight < 1
                || tileWidth > width - x || tileHeight > height - y) return false;
        boolean compressed = format == ASTC_6X6 || format == ASTC_SRGB_6X6;
        if (!compressed && format != GLES30.GL_RGBA8 && format != GLES30.GL_SRGB8_ALPHA8) return false;
        int expected = compressed ? ((width + 5) / 6) * ((height + 5) / 6) * 16 : width * height * 4;
        if (source.remaining() != expected) return false;
        ByteBuffer pixels = ByteBuffer.allocateDirect(expected);
        pixels.put(source).flip();
        if (!replaceTexture(width, height, format, pixels, compressed)) return false;
        tile[0] = (x + 0.5f) / width;
        tile[1] = (y + 0.5f) / height;
        tile[2] = (x + tileWidth - 0.5f) / width;
        tile[3] = (y + tileHeight - 0.5f) / height;
        textureFormat = compressed ? "ASTC 6x6" : "RGBA8 atlas";
        atlasBytes = expected;
        return true;
    }

    private void installChecker() {
        ByteBuffer pixels = ByteBuffer.allocateDirect(16);
        pixels.put(new byte[]{45, 81, 96, -1, -18, -77, 108, -1,
            -18, -77, 108, -1, 45, 81, 96, -1}).flip();
        if (replaceTexture(2, 2, GLES30.GL_RGBA8, pixels, false)) {
            tile[0] = tile[1] = 0;
            tile[2] = tile[3] = 1;
            textureFormat = "Checker";
            atlasBytes = 16;
        }
    }

    private boolean replaceTexture(int width, int height, int format, ByteBuffer pixels, boolean compressed) {
        int[] next = new int[1];
        GLES30.glGenTextures(1, next, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, next[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        if (compressed) {
            GLES30.glCompressedTexImage2D(GLES30.GL_TEXTURE_2D, 0, format,
                width, height, 0, pixels.remaining(), pixels);
        } else {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, format, width, height, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels);
        }
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            GLES30.glDeleteTextures(1, next, 0);
            return false;
        }
        if (texture != 0) GLES30.glDeleteTextures(1, new int[]{texture}, 0);
        texture = next[0];
        return true;
    }

    private void uploadMesh(int lod, float[] vertices) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(vertices.length * Float.BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(vertices).flip();
        GLES30.glBindVertexArray(vertexArrays[lod]);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, geometry[lod]);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.length * Float.BYTES, buffer, GLES30.GL_STATIC_DRAW);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, 0);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, 12);
    }

    private static float[] mesh(int lod) {
        float n = -0.65f, p = 0.65f;
        float[][] points = {{n,n,n},{p,n,n},{p,p,n},{n,p,n},{n,n,p},{p,n,p},{p,p,p},{n,p,p}};
        int[][] faces = lod == 0 ? new int[][]{{0,1,2,3},{4,5,6,7},{0,4,7,3},
            {1,5,6,2},{0,1,5,4},{3,2,6,7}} : lod == 1 ? new int[][]{{4,5,6,7},{0,1,2,3}}
            : new int[][]{{4,5,6,7}};
        float[] result = new float[faces.length * 30];
        int at = 0;
        for (int[] face : faces) {
            for (int corner : new int[]{0,1,2,0,2,3}) {
                for (float axis : points[face[corner]]) result[at++] = axis;
                result[at++] = corner == 1 || corner == 2 ? 1 : 0;
                result[at++] = corner < 2 ? 1 : 0;
            }
        }
        return result;
    }

    private static boolean supportsAstc() {
        int[] count = new int[1];
        GLES30.glGetIntegerv(GLES30.GL_NUM_EXTENSIONS, count, 0);
        for (int i = 0; i < count[0]; i++) {
            String extension = GLES30.glGetStringi(GLES30.GL_EXTENSIONS, i);
            if ("GL_KHR_texture_compression_astc_ldr".equals(extension)
                    || "GL_KHR_texture_compression_astc_hdr".equals(extension)) return true;
        }
        return false;
    }

    private static int shader(int type, String source) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);
        int[] status = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            if (BuildConfig.DEBUG) {
                Diagnostics.shaderCompileFailed(type == GLES30.GL_VERTEX_SHADER, GLES30.glGetShaderInfoLog(shader));
            }
            GLES30.glDeleteShader(shader);
            throw new IllegalStateException("Preview shader compilation failed");
        }
        return shader;
    }

    private static int program() {
        int vertex = shader(GLES30.GL_VERTEX_SHADER, VERTEX);
        int fragment;
        try {
            fragment = shader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT);
        } catch (IllegalStateException error) {
            GLES30.glDeleteShader(vertex);
            throw error;
        }
        int result = GLES30.glCreateProgram();
        GLES30.glAttachShader(result, vertex);
        GLES30.glAttachShader(result, fragment);
        GLES30.glLinkProgram(result);
        GLES30.glDeleteShader(vertex);
        GLES30.glDeleteShader(fragment);
        int[] status = new int[1];
        GLES30.glGetProgramiv(result, GLES30.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            if (BuildConfig.DEBUG) Diagnostics.shaderLinkFailed(GLES30.glGetProgramInfoLog(result));
            GLES30.glDeleteProgram(result);
            throw new IllegalStateException("Preview shader linking failed");
        }
        if (BuildConfig.DEBUG) {
            Diagnostics.shaderCompiled(GLES30.glGetString(GLES30.GL_VENDOR), GLES30.glGetString(GLES30.GL_RENDERER),
                GLES30.glGetString(GLES30.GL_VERSION), GLES30.glGetString(GLES30.GL_SHADING_LANGUAGE_VERSION));
        }
        return result;
    }

    private static native int[] planFrame(float[] columnMajorClipFromWorld, float cameraX, float cameraY, float cameraZ);
    private static native byte[] loadAtlas(AssetManager assets, boolean supportsAstc);
}
