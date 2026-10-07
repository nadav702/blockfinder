package com.yourname.blockatlas.highlight;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.yourname.blockatlas.BlockAtlasClient;
import com.yourname.blockatlas.config.BlockAtlasConfig;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Draws ESP-style highlight boxes in the world.
 *
 * <p>Follows the 26.x two-phase model: during <i>extraction</i> we grab the immutable
 * {@link HighlightManager.RenderList}; during <i>drawing</i> we build one vertex buffer and
 * submit it with a custom pipeline. Two pipelines exist: one with the depth test removed
 * (through walls) and one with normal depth testing.</p>
 *
 * <p>Vertices are written camera-relative (block position minus camera position, in double
 * precision) so boxes stay rock-steady even millions of blocks from the origin.</p>
 */
public final class BoxRenderer {
    private BoxRenderer() {}

    /** Filled translucent quads, no depth test: visible through walls. */
    private static final RenderPipeline THROUGH_WALLS = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(BlockAtlasClient.id("pipeline/highlight_through_walls"))
                    .withDepthStencilState(Optional.empty())
                    .build());

    /** Same quads with the snippet's default depth testing: only visible where in sight. */
    private static final RenderPipeline WITH_DEPTH = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(BlockAtlasClient.id("pipeline/highlight_depth"))
                    .build());

    private static final int BUFFER_BYTES = 8 * 1024 * 1024;
    private static final int BYTES_PER_VERTEX = 16;            // POSITION_COLOR
    private static final int FACE_BYTES = 24 * BYTES_PER_VERTEX;
    private static final int EDGE_BYTES = 12 * 4 * 4 * BYTES_PER_VERTEX; // 12 edges × 4 quads
    private static final int MAX_OUTLINED = 600;
    private static final float EDGE_HALF = 0.018f;
    private static final float BEAM_HALF = 0.09f;
    private static final float BEAM_HEIGHT = 48f;

    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static final StagedVertexBuffer BUFFER =
            new StagedVertexBuffer(() -> "BlockAtlas highlights", BUFFER_BYTES);

    private static HighlightManager.RenderList frame = HighlightManager.RenderList.EMPTY;
    private static boolean frameThroughWalls;
    private static boolean frameOutlines;
    private static boolean frameBeams;

    public static void register() {
        LevelExtractionEvents.END_EXTRACTION.register(BoxRenderer::extract);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(BoxRenderer::render);
    }

    public static void close() {
        BUFFER.close();
    }

    // ---- extraction phase -------------------------------------------------------------------

    private static void extract(LevelExtractionContext context) {
        BlockAtlasConfig cfg = BlockAtlasConfig.get();
        frame = cfg.enabled ? HighlightManager.get().renderList() : HighlightManager.RenderList.EMPTY;
        frameThroughWalls = cfg.seeThroughWalls;
        frameOutlines = cfg.outlines;
        frameBeams = cfg.nearestBeam;
    }

    // ---- drawing phase ----------------------------------------------------------------------

    private static void render(LevelRenderContext context) {
        HighlightManager.RenderList list = frame;
        if (list.count() == 0 && list.beams().length == 0) return;

        RenderPipeline pipeline = frameThroughWalls ? THROUGH_WALLS : WITH_DEPTH;
        VertexFormat format = pipeline.getVertexFormatBinding(0);
        if (format == null) return;

        PrimitiveTopology topology = pipeline.getPrimitiveTopology();
        StagedVertexBuffer.Draw draw = BUFFER.appendDraw(format, topology,
                topology == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null);
        VertexConsumer vc = BUFFER.getVertexBuilder(draw);

        Matrix4fc pose = context.poseStack().last().pose();
        Vec3 cam = context.levelState().cameraRenderState.pos;
        float grow = frameThroughWalls ? 0.001f : 0.003f; // avoid z-fighting with the block itself

        int budget = BUFFER_BYTES - list.beams().length * FACE_BYTES;
        int outlined = frameOutlines ? Math.min(list.count(), MAX_OUTLINED) : 0;
        budget -= outlined * EDGE_BYTES;
        int faces = Math.min(list.count(), Math.max(0, budget / FACE_BYTES));

        long[] pos = list.positions();
        int[] colors = list.colors();
        for (int i = 0; i < faces; i++) {
            long p = pos[i];
            float x = (float) (BlockPos.getX(p) - cam.x);
            float y = (float) (BlockPos.getY(p) - cam.y);
            float z = (float) (BlockPos.getZ(p) - cam.z);
            int c = colors[i];
            float a = ((c >>> 24) & 255) / 255f;
            float r = ((c >> 16) & 255) / 255f, g = ((c >> 8) & 255) / 255f, b = (c & 255) / 255f;

            filledBox(vc, pose, x - grow, y - grow, z - grow, x + 1 + grow, y + 1 + grow, z + 1 + grow, r, g, b, a);
            if (i < outlined) {
                edges(vc, pose, x - grow, y - grow, z - grow, x + 1 + grow, y + 1 + grow, z + 1 + grow,
                        r, g, b, Math.min(1f, a + 0.5f));
            }
        }

        if (frameBeams) {
            long[] beams = list.beams();
            int[] beamColors = list.beamColors();
            for (int i = 0; i < beams.length; i++) {
                long p = beams[i];
                float cx = (float) (BlockPos.getX(p) + 0.5 - cam.x);
                float by = (float) (BlockPos.getY(p) + 1.0 - cam.y);
                float cz = (float) (BlockPos.getZ(p) + 0.5 - cam.z);
                int c = beamColors[i];
                filledBox(vc, pose, cx - BEAM_HALF, by, cz - BEAM_HALF, cx + BEAM_HALF, by + BEAM_HEIGHT, cz + BEAM_HALF,
                        ((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f, 0.45f);
            }
        }

        BUFFER.upload();
        StagedVertexBuffer.ExecuteInfo info = BUFFER.getExecuteInfo(draw);
        if (info != null) {
            submit(Minecraft.getInstance(), info, pipeline);
        }
        BUFFER.endFrame();
    }

    private static void submit(Minecraft client, StagedVertexBuffer.ExecuteInfo info, RenderPipeline pipeline) {
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

        RenderTarget mainTarget = client.gameRenderer.mainRenderTarget();
        GpuTextureView colorTexture = mainTarget.getColorTextureView();
        if (colorTexture == null) return;

        try (RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "BlockAtlas highlights", colorTexture, Optional.empty(),
                        mainTarget.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dynamicTransforms);
            pass.setVertexBuffer(0, info.vertexBuffer().slice());
            pass.setIndexBuffer(info.indexBuffer(), info.indexType());
            pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        }
    }

    // ---- geometry ---------------------------------------------------------------------------

    private static void filledBox(VertexConsumer b, Matrix4fc m, float x0, float y0, float z0,
                                  float x1, float y1, float z1, float r, float g, float bl, float a) {
        // front (+Z)
        b.addVertex(m, x0, y0, z1).setColor(r, g, bl, a);
        b.addVertex(m, x1, y0, z1).setColor(r, g, bl, a);
        b.addVertex(m, x1, y1, z1).setColor(r, g, bl, a);
        b.addVertex(m, x0, y1, z1).setColor(r, g, bl, a);
        // back (-Z)
        b.addVertex(m, x1, y0, z0).setColor(r, g, bl, a);
        b.addVertex(m, x0, y0, z0).setColor(r, g, bl, a);
        b.addVertex(m, x0, y1, z0).setColor(r, g, bl, a);
        b.addVertex(m, x1, y1, z0).setColor(r, g, bl, a);
        // left (-X)
        b.addVertex(m, x0, y0, z0).setColor(r, g, bl, a);
        b.addVertex(m, x0, y0, z1).setColor(r, g, bl, a);
        b.addVertex(m, x0, y1, z1).setColor(r, g, bl, a);
        b.addVertex(m, x0, y1, z0).setColor(r, g, bl, a);
        // right (+X)
        b.addVertex(m, x1, y0, z1).setColor(r, g, bl, a);
        b.addVertex(m, x1, y0, z0).setColor(r, g, bl, a);
        b.addVertex(m, x1, y1, z0).setColor(r, g, bl, a);
        b.addVertex(m, x1, y1, z1).setColor(r, g, bl, a);
        // top (+Y)
        b.addVertex(m, x0, y1, z1).setColor(r, g, bl, a);
        b.addVertex(m, x1, y1, z1).setColor(r, g, bl, a);
        b.addVertex(m, x1, y1, z0).setColor(r, g, bl, a);
        b.addVertex(m, x0, y1, z0).setColor(r, g, bl, a);
        // bottom (-Y)
        b.addVertex(m, x0, y0, z0).setColor(r, g, bl, a);
        b.addVertex(m, x1, y0, z0).setColor(r, g, bl, a);
        b.addVertex(m, x1, y0, z1).setColor(r, g, bl, a);
        b.addVertex(m, x0, y0, z1).setColor(r, g, bl, a);
    }

    /** The 12 box edges, each as two crossed thin quads (double-sided, so culling never hides them). */
    private static void edges(VertexConsumer b, Matrix4fc m, float x0, float y0, float z0,
                              float x1, float y1, float z1, float r, float g, float bl, float a) {
        float t = EDGE_HALF;
        float[] ys = {y0, y1}, zs = {z0, z1}, xs = {x0, x1};
        for (float y : ys) for (float z : zs) { // edges along X
            quad2(b, m, x0, y - t, z, x1, y - t, z, x1, y + t, z, x0, y + t, z, r, g, bl, a);
            quad2(b, m, x0, y, z - t, x1, y, z - t, x1, y, z + t, x0, y, z + t, r, g, bl, a);
        }
        for (float x : xs) for (float z : zs) { // edges along Y
            quad2(b, m, x - t, y0, z, x + t, y0, z, x + t, y1, z, x - t, y1, z, r, g, bl, a);
            quad2(b, m, x, y0, z - t, x, y0, z + t, x, y1, z + t, x, y1, z - t, r, g, bl, a);
        }
        for (float x : xs) for (float y : ys) { // edges along Z
            quad2(b, m, x - t, y, z0, x + t, y, z0, x + t, y, z1, x - t, y, z1, r, g, bl, a);
            quad2(b, m, x, y - t, z0, x, y + t, z0, x, y + t, z1, x, y - t, z1, r, g, bl, a);
        }
    }

    /** One quad emitted with both windings. */
    private static void quad2(VertexConsumer b, Matrix4fc m,
                              float ax, float ay, float az, float bx, float by, float bz,
                              float cx, float cy, float cz, float dx, float dy, float dz,
                              float r, float g, float bl, float a) {
        b.addVertex(m, ax, ay, az).setColor(r, g, bl, a);
        b.addVertex(m, bx, by, bz).setColor(r, g, bl, a);
        b.addVertex(m, cx, cy, cz).setColor(r, g, bl, a);
        b.addVertex(m, dx, dy, dz).setColor(r, g, bl, a);
        b.addVertex(m, dx, dy, dz).setColor(r, g, bl, a);
        b.addVertex(m, cx, cy, cz).setColor(r, g, bl, a);
        b.addVertex(m, bx, by, bz).setColor(r, g, bl, a);
        b.addVertex(m, ax, ay, az).setColor(r, g, bl, a);
    }
}
