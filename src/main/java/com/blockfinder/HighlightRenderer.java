package com.blockfinder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Draws a glowing outline around every found block; colour shifts from cyan (near) to purple (far). */
public class HighlightRenderer {
    public static void render(WorldRenderContext ctx) {
        if (Scanner.INSTANCE.target() == null) return;
        List<BlockPos> list;
        synchronized (Scanner.INSTANCE.results()) { list = new ArrayList<>(Scanner.INSTANCE.results()); }
        if (list.isEmpty()) return;
        MultiBufferSource src = ctx.consumers();
        if (src == null) return;
        PoseStack ps = ctx.matrixStack();
        Vec3 cam = ctx.camera().getPosition();
        VertexConsumer vc = src.getBuffer(RenderType.lines());
        float pulse = Theme.pulse ? 0.7f + 0.3f * (float) Math.sin(System.currentTimeMillis() / 250.0) : 1f;
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        PoseStack.Pose pose = ps.last();
        for (BlockPos p : list) {
            float t = (float) Math.min(1.0, Math.sqrt(p.distToCenterSqr(cam)) / Scanner.RADIUS);
            int c = Theme.lerp(Theme.outlineNear, Theme.outlineFar, t);
            float r = (c >> 16 & 0xFF) / 255f, gr = (c >> 8 & 0xFF) / 255f, b = (c & 0xFF) / 255f;
            box(vc, pose, p.getX() - 0.002, p.getY() - 0.002, p.getZ() - 0.002,
                p.getX() + 1.002, p.getY() + 1.002, p.getZ() + 1.002, r, gr, b, pulse);
        }
        ps.popPose();
    }

    private static void box(VertexConsumer vc, PoseStack.Pose pose, double x0, double y0, double z0,
                            double x1, double y1, double z1, float r, float g, float b, float a) {
        double[][] e = {
            {x0,y0,z0,x1,y0,z0},{x0,y1,z0,x1,y1,z0},{x0,y0,z1,x1,y0,z1},{x0,y1,z1,x1,y1,z1},
            {x0,y0,z0,x0,y1,z0},{x1,y0,z0,x1,y1,z0},{x0,y0,z1,x0,y1,z1},{x1,y0,z1,x1,y1,z1},
            {x0,y0,z0,x0,y0,z1},{x1,y0,z0,x1,y0,z1},{x0,y1,z0,x0,y1,z1},{x1,y1,z0,x1,y1,z1}};
        for (double[] l : e) {
            float nx = (float)(l[3]-l[0]), ny = (float)(l[4]-l[1]), nz = (float)(l[5]-l[2]);
            vc.addVertex(pose, (float)l[0], (float)l[1], (float)l[2]).setColor(r, g, b, a).setNormal(pose, nx, ny, nz).setLineWidth(Theme.outlineWidth);
            vc.addVertex(pose, (float)l[3], (float)l[4], (float)l[5]).setColor(r, g, b, a).setNormal(pose, nx, ny, nz).setLineWidth(Theme.outlineWidth);
        }
    }
}
