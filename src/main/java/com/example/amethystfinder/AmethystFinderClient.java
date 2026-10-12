package com.example.amethystfinder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Client-side highlighter for amethyst buds and clusters.
 *
 * You choose the minimum growth stage to detect (small bud, medium bud, large bud, or fully grown cluster)
 * and a range. Everything at that stage or larger within range is outlined, optionally through walls. It only looks at blocks your
 * client already has loaded, and nothing is sent to the server.
 *
 * G toggles, J opens the settings screen. It starts OFF every launch.
 */
public class AmethystFinderClient implements ClientModInitializer {
    public static final String MOD_ID = "amethystfinder";

    public static final String[] STAGE_NAMES = {
            "Small bud", "Medium bud", "Large bud", "Cluster (fully grown)"
    };
    private static final int[] STAGE_COLORS = {
            0xFF55AAFF, 0xFF8866FF, 0xFFFF55FF, 0xFFFF3030
    };

    public static final int MIN_RANGE = 4;
    public static final int MAX_RANGE = 64;
    private static final int DEFAULT_RANGE = 24;
    private static final int RESCAN_TICKS = 10;
    private static final int MAX_RESULTS = 2000;
    private static final float LINE_WIDTH = 3.0f;

    // saved settings
    private static int minStage = 3;          // 0 = small bud ... 3 = fully grown cluster
    private static int range = DEFAULT_RANGE; // blocks
    private static boolean seeThrough = false; // draw outlines through solid blocks

    private static boolean enabled = false;
    private static KeyMapping toggleKey;
    private static KeyMapping settingsKey;
    /**
     * Same as the vanilla line pipeline, but with the depth test removed so outlines show through walls.
     * Minecraft 26.3 changed these render classes a lot, so the parts that changed (depth state and the
     * color target) are set through reflection. If anything does not fit, this is null and the mod simply
     * falls back to normal (depth-tested) outlines instead of crashing.
     */
    private static final RenderPipeline LINES_THROUGH_WALLS = buildThroughWallsPipeline();

    private static RenderPipeline buildThroughWallsPipeline() {
        try {
            Object b = RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/lines_through_walls"));

            // 1) No depth test: withDepthStencilState(Optional.empty()) or (null), whichever this version has.
            Method depth = null;
            boolean optionalParam = false;
            for (Method m : b.getClass().getMethods()) {
                if (!m.getName().equals("withDepthStencilState") || m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] == Optional.class) { depth = m; optionalParam = true; break; }
                depth = m;
            }
            if (depth == null) throw new IllegalStateException("withDepthStencilState not found");
            depth.setAccessible(true);
            Object r = depth.invoke(b, optionalParam ? Optional.empty() : null);
            if (r != null && r.getClass() == b.getClass()) b = r;

            // 2) A color target with normal translucent blending (the vanilla line pipeline adds this itself).
            Method color = null;
            for (Method m : b.getClass().getMethods()) {
                if (m.getName().equals("withColorTargetState") && m.getParameterCount() == 1) { color = m; break; }
            }
            if (color != null) {
                Class<?> ctsClass = color.getParameterTypes()[0];
                Object cts = null;
                for (Constructor<?> c : ctsClass.getConstructors()) {
                    if (c.getParameterCount() != 1) continue;
                    Class<?> blendClass = c.getParameterTypes()[0];
                    Object translucent = blendClass.getField("TRANSLUCENT").get(null);
                    cts = c.newInstance(translucent);
                    break;
                }
                if (cts == null) throw new IllegalStateException("could not create ColorTargetState");
                color.setAccessible(true);
                r = color.invoke(b, cts);
                if (r != null && r.getClass() == b.getClass()) b = r;
            }

            Method build = b.getClass().getMethod("build");
            build.setAccessible(true);
            return RenderPipelines.register((RenderPipeline) build.invoke(b));
        } catch (Throwable t) {
            System.err.println("[Amethyst Finder] See-through pipeline unavailable, using normal outlines: " + t);
            return null;
        }
    }

    private static RenderType linesType;
    private static RenderType linesThroughWallsType;

    private static int tickCounter = 0;
    private static volatile List<Found> results = new ArrayList<>();

    private record Found(BlockPos pos, int stage) { }

    public static boolean isEnabled() { return enabled; }
    public static int getMinStage() { return minStage; }
    public static int getRange() { return range; }
    public static boolean isSeeThrough() { return seeThrough; }
    public static void setSeeThrough(boolean v) { seeThrough = v; }

    public static void setMinStage(int stage) {
        minStage = Mth.clamp(stage, 0, 3);
        tickCounter = 0; // rescan right away
    }

    public static void setRange(int r) {
        range = Mth.clamp(r, MIN_RANGE, MAX_RANGE);
        tickCounter = 0;
    }

    public static void toggle(Minecraft mc) {
        enabled = !enabled;
        tickCounter = 0;
        if (!enabled) results = new ArrayList<>();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("[Amethyst Finder] " + (enabled ? "ON" : "OFF")));
        }
    }

    @Override
    public void onInitializeClient() {
        loadConfig();

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        toggleKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.amethystfinder.toggle", InputConstants.KEY_G, category));
        settingsKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.amethystfinder.settings", InputConstants.KEY_J, category));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (toggleKey.consumeClick()) toggle(mc);
            while (settingsKey.consumeClick()) {
                if (mc.level != null) mc.gui.setScreen(new AmethystFinderSettingsScreen());
            }

            if (!enabled) return;
            if (mc.player == null || mc.level == null) {
                results = new ArrayList<>();
                return;
            }
            if (tickCounter-- <= 0) {
                tickCounter = RESCAN_TICKS;
                results = scan(mc.level, mc.player);
            }
        });

        LevelRenderEvents.AFTER_SOLID_FEATURES.register(ctx ->
                render(ctx.poseStack(), ctx.submitNodeCollector()));
    }

    // ---------------------------------------------------------------- scanning

    /** 0..3 for small bud..cluster, or -1 for anything else. */
    private static int stageOf(BlockState state) {
        Block b = state.getBlock();
        if (b == Blocks.SMALL_AMETHYST_BUD) return 0;
        if (b == Blocks.MEDIUM_AMETHYST_BUD) return 1;
        if (b == Blocks.LARGE_AMETHYST_BUD) return 2;
        if (b == Blocks.AMETHYST_CLUSTER) return 3;
        return -1;
    }

    private static List<Found> scan(ClientLevel level, LocalPlayer player) {
        List<Found> out = new ArrayList<>();
        final int min = minStage;
        final int r = range;
        final double rSq = (double) r * r;

        BlockPos origin = player.blockPosition();
        int minChunkX = (origin.getX() - r) >> 4;
        int maxChunkX = (origin.getX() + r) >> 4;
        int minChunkZ = (origin.getZ() - r) >> 4;
        int maxChunkZ = (origin.getZ() + r) >> 4;

        int worldMinY = level.getMinY();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;

                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection section = sections[i];
                    if (section == null || section.hasOnlyAir()) continue;
                    // Quick skip: does this 16x16x16 section contain any amethyst bud/cluster at all?
                    if (!section.maybeHas(s -> stageOf(s) >= min)) continue;

                    int baseY = worldMinY + i * 16;
                    // Skip sections completely out of vertical range.
                    if (baseY + 15 < origin.getY() - r || baseY > origin.getY() + r) continue;

                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                BlockState state = section.getBlockState(x, y, z);
                                int stage = stageOf(state);
                                if (stage < min) continue;

                                pos.set((cx << 4) + x, baseY + y, (cz << 4) + z);
                                if (pos.distSqr(origin) > rSq) continue;

                                out.add(new Found(pos.immutable(), stage));
                                if (out.size() >= MAX_RESULTS) return out;
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- rendering

    private static void render(PoseStack pose, SubmitNodeCollector collector) {
        if (!enabled || pose == null || collector == null) return;
        List<Found> list = results;
        if (list.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        if (linesType == null) {
            linesType = RenderType.create("amethystfinder_lines",
                    RenderSetup.builder(RenderPipelines.LINES).createRenderSetup());
            if (LINES_THROUGH_WALLS != null) {
                linesThroughWallsType = RenderType.create("amethystfinder_lines_through_walls",
                        RenderSetup.builder(LINES_THROUGH_WALLS).createRenderSetup());
            }
        }
        RenderType type = (seeThrough && linesThroughWallsType != null) ? linesThroughWallsType : linesType;

        Vec3 cam = mc.gameRenderer.mainCamera().position();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        for (Found f : list) {
            AABB box = new AABB(f.pos()).inflate(0.002);
            collector.submitShapeOutline(pose, Shapes.create(box), type,
                    STAGE_COLORS[f.stage()], LINE_WIDTH, false);
        }
        pose.popPose();
    }

    // ---------------------------------------------------------------- config

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("amethystfinder.properties");
    }

    private static void loadConfig() {
        Properties props = new Properties();
        Path path = configPath();
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
            } catch (IOException ignored) {
            }
        }
        try {
            minStage = Mth.clamp(Integer.parseInt(props.getProperty("minStage", "3")), 0, 3);
        } catch (NumberFormatException e) {
            minStage = 3;
        }
        try {
            range = Mth.clamp(Integer.parseInt(props.getProperty("range", Integer.toString(DEFAULT_RANGE))),
                    MIN_RANGE, MAX_RANGE);
        } catch (NumberFormatException e) {
            range = DEFAULT_RANGE;
        }
        seeThrough = Boolean.parseBoolean(props.getProperty("seeThroughOutline", "false"));
        if (!Files.exists(path)) saveConfig();
    }

    public static void saveConfig() {
        Properties props = new Properties();
        props.setProperty("minStage", Integer.toString(minStage));
        props.setProperty("range", Integer.toString(range));
        props.setProperty("seeThroughOutline", Boolean.toString(seeThrough));
        try (OutputStream out = Files.newOutputStream(configPath())) {
            props.store(out, "Amethyst Finder. minStage: 0=small bud, 1=medium bud, 2=large bud, 3=cluster. "
                    + "range: " + MIN_RANGE + "-" + MAX_RANGE + " blocks. seeThroughOutline: true/false.");
        } catch (IOException ignored) {
        }
    }
}
