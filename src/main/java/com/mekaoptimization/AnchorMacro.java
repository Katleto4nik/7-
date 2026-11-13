package com.mekaoptimization;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * When you place a respawn anchor:
 *   1) swap to glowstone (hotbar)
 *   2) right-click the anchor ONCE
 *   3) swap back to the slot the anchor was in
 *   4) right-click the anchor ONCE
 * Every step is preceded by a random 6-14 ms delay.
 *
 * An anchor that you place yourself exactly 1 block directly in front of you is ignored.
 */
public final class AnchorMacro {
    private static final int MIN_DELAY_MS = 6;
    private static final int MAX_DELAY_MS = 14; // inclusive
    private static final int MAX_WAIT_FOR_ANCHOR_STEPS = 30;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private static final ScheduledExecutorService TIMER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "MekaOptimization-Timer");
                t.setDaemon(true);
                return t;
            });

    private AnchorMacro() {
    }

    public static void init() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            // client side only, main hand only, ignore our own clicks while the macro runs
            if (!world.isClient() || hand != Hand.MAIN_HAND) return ActionResult.PASS;
            if (RUNNING.get()) return ActionResult.PASS;
            if (!player.getStackInHand(hand).isOf(Items.RESPAWN_ANCHOR)) return ActionResult.PASS;

            // with Shift a right-click places blocks instead of using them, so the macro can't work
            if (player.isSneaking()) return ActionResult.PASS;

            BlockPos clicked = hit.getBlockPos();
            var clickedState = world.getBlockState(clicked);

            // clicking an existing anchor is not a placement
            if (clickedState.isOf(Blocks.RESPAWN_ANCHOR)) return ActionResult.PASS;

            BlockPos placePos = clickedState.isReplaceable() ? clicked : clicked.offset(hit.getSide());

            MinecraftClient mc = MinecraftClient.getInstance();
            ClientPlayerEntity me = mc.player;
            ClientWorld cw = mc.world;
            if (me == null || cw == null || mc.interactionManager == null) return ActionResult.PASS;

            // anchor placed by you 1 block directly in front of you -> do nothing
            if (isDirectlyInFront(me, placePos)) return ActionResult.PASS;

            if (!RUNNING.compareAndSet(false, true)) return ActionResult.PASS;
            new Sequence(mc, cw, placePos, me.getInventory().getSelectedSlot()).start();
            return ActionResult.PASS;
        });
    }

    /** The block right in front of the player (feet level or head level). */
    private static boolean isDirectlyInFront(ClientPlayerEntity player, BlockPos pos) {
        BlockPos front = player.getBlockPos().offset(player.getHorizontalFacing());
        return pos.equals(front) || pos.equals(front.up());
    }

    private static final class Sequence {
        private final MinecraftClient mc;
        private final ClientWorld world;
        private final BlockPos anchorPos;
        private final int anchorSlot;
        private int waitSteps = 0;

        Sequence(MinecraftClient mc, ClientWorld world, BlockPos anchorPos, int anchorSlot) {
            this.mc = mc;
            this.world = world;
            this.anchorPos = anchorPos;
            this.anchorSlot = anchorSlot;
        }

        void start() {
            next(this::swapToGlowstone);
        }

        /** Runs a step on the game thread after a random 6-14 ms delay. */
        private void next(Runnable step) {
            long delay = ThreadLocalRandom.current().nextInt(MIN_DELAY_MS, MAX_DELAY_MS + 1);
            TIMER.schedule(() -> mc.execute(() -> {
                try {
                    if (!stillValid()) {
                        finish();
                        return;
                    }
                    step.run();
                } catch (Throwable t) {
                    MekaOptimizationClient.LOGGER.error("Anchor macro failed", t);
                    finish();
                }
            }), delay, TimeUnit.MILLISECONDS);
        }

        private boolean stillValid() {
            return mc.player != null && mc.world == world && mc.interactionManager != null;
        }

        private boolean anchorPresent() {
            return world.getBlockState(anchorPos).isOf(Blocks.RESPAWN_ANCHOR);
        }

        private void finish() {
            RUNNING.set(false);
        }

        // ---- steps ----

        private void swapToGlowstone() {
            // the anchor must really be placed (client prediction / server confirmation)
            if (!anchorPresent()) {
                if (++waitSteps > MAX_WAIT_FOR_ANCHOR_STEPS) {
                    finish();
                } else {
                    next(this::swapToGlowstone);
                }
                return;
            }

            PlayerInventory inv = mc.player.getInventory();
            int glowSlot = -1;
            for (int i = 0; i < 9; i++) {
                if (inv.getStack(i).isOf(Items.GLOWSTONE)) {
                    glowSlot = i;
                    break;
                }
            }
            if (glowSlot == -1) {
                finish();
                return;
            }

            inv.setSelectedSlot(glowSlot);
            next(this::chargeAnchor);
        }

        private void chargeAnchor() {
            if (!anchorPresent()) {
                finish();
                return;
            }
            useAnchor(); // ONE right-click with glowstone
            next(this::swapBack);
        }

        private void swapBack() {
            mc.player.getInventory().setSelectedSlot(anchorSlot);
            next(this::explodeAnchor);
        }

        private void explodeAnchor() {
            if (anchorPresent()) {
                useAnchor(); // ONE right-click with the anchor slot selected
            }
            finish();
        }

        private void useAnchor() {
            ClientPlayerEntity player = mc.player;
            mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, aimAtAnchor(player));
            player.swingHand(Hand.MAIN_HAND);
        }

        /** Use the real crosshair hit if it is on the anchor, otherwise the face turned towards the player. */
        private BlockHitResult aimAtAnchor(ClientPlayerEntity player) {
            if (mc.crosshairTarget instanceof BlockHitResult bhr
                    && bhr.getType() == HitResult.Type.BLOCK
                    && bhr.getBlockPos().equals(anchorPos)) {
                return bhr;
            }
            Vec3d eye = player.getEyePos();
            Vec3d center = Vec3d.ofCenter(anchorPos);
            Direction side = Direction.getFacing(eye.x - center.x, eye.y - center.y, eye.z - center.z);
            Vec3d hitPos = center.add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
            return new BlockHitResult(hitPos, side, anchorPos, false);
        }
    }
}
