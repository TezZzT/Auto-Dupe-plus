package com.example.addon.modules;

import com.example.addon.Main_Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.Sneak;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.AbstractDonkeyEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class DonkeyBoatTpDupe extends Module {

    // ──────────────────────────────────────────────────────────────────────────
    // Setting Groups
    // ──────────────────────────────────────────────────────────────────────────
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBoat = settings.createGroup("Boat");
    private final SettingGroup sgMarker = settings.createGroup("Marker");
    private final SettingGroup sgDrop = settings.createGroup("Auto-Drop");

    // ── General ───────────────────────────────────────────────────────────────
    private final Setting<String> homeName = sgGeneral.add(new StringSetting.Builder()
            .name("home-name")
            .description("Name of the /home to teleport to after the boat bug.")
            .defaultValue("dup")
            .build());

    private final Setting<Integer> mountDelay = sgGeneral.add(new IntSetting.Builder()
            .name("mount-delay")
            .description("Ticks to wait after clicking the donkey before trying to mount the boat.")
            .defaultValue(2).min(0).sliderMax(20)
            .build());

    private final Setting<Integer> teleportDelay = sgGeneral.add(new IntSetting.Builder()
            .name("teleport-delay")
            .description("Ticks to wait after exiting the boat before sending /home.")
            .defaultValue(15).min(0).sliderMax(60)
            .build());

    private final Setting<Integer> serverKickTimeout = sgGeneral.add(new IntSetting.Builder()
            .name("server-kick-timeout")
            .description("Max ticks to wait for the server to kick you from the boat (0 = infinite).")
            .defaultValue(300).min(0).sliderMax(600)
            .build());

    private final Setting<Boolean> continuous = sgGeneral.add(new BoolSetting.Builder()
            .name("continuous")
            .description("Keep duping automatically (AFK mode).")
            .defaultValue(false)
            .build());

    private final Setting<Integer> restartDelay = sgGeneral.add(new IntSetting.Builder()
            .name("restart-delay")
            .description("Ticks to wait before restarting in continuous mode.")
            .defaultValue(40).min(0).sliderMax(200)
            .visible(continuous::get)
            .build());

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
            .name("debug")
            .description("Show step-by-step status messages.")
            .defaultValue(true)
            .build());

    // ── Boat ──────────────────────────────────────────────────────────────────
    private final Setting<Double> boatRange = sgBoat.add(new DoubleSetting.Builder()
            .name("boat-range")
            .description("Search radius for nearby boats.")
            .defaultValue(6.0).min(2.0).sliderMax(12.0)
            .build());

    public enum BoatMode {
        None, BoatFly, BoatTp
    }

    private final Setting<BoatMode> boatMode = sgBoat.add(new EnumSetting.Builder<BoatMode>()
            .name("boat-mode")
            .description("How to reach the marker while in the boat. Requires Marker-Guidance enabled.")
            .defaultValue(BoatMode.BoatFly)
            .build());

    private final Setting<Double> boatFlySpeed = sgBoat.add(new DoubleSetting.Builder()
            .name("boat-fly-speed")
            .description("Blocks/tick speed for BoatFly mode.")
            .defaultValue(3.0).min(0.1).sliderMax(20.0)
            .visible(() -> boatMode.get() == BoatMode.BoatFly)
            .build());

    // ── Marker ────────────────────────────────────────────────────────────────
    private final Setting<Boolean> markerGuidance = sgMarker.add(new BoolSetting.Builder()
            .name("marker-guidance")
            .description("Use the boat to move to a target position before the server kicks you.")
            .defaultValue(false)
            .build());

    private final Setting<BlockPos> markerPos = sgMarker.add(new BlockPosSetting.Builder()
            .name("marker-pos")
            .description("Target position to reach while in the boat.")
            .defaultValue(BlockPos.ORIGIN)
            .visible(markerGuidance::get)
            .build());

    private final Setting<Integer> markerRadius = sgMarker.add(new IntSetting.Builder()
            .name("marker-radius")
            .description("Blocks of tolerance to consider the marker reached.")
            .defaultValue(1).min(1).sliderMax(5)
            .visible(markerGuidance::get)
            .build());

    private final Setting<Boolean> renderMarker = sgMarker.add(new BoolSetting.Builder()
            .name("render-marker")
            .description("Render the target marker in the world.")
            .defaultValue(true)
            .visible(markerGuidance::get)
            .build());

    private final Setting<ShapeMode> markerShapeMode = sgMarker.add(new EnumSetting.Builder<ShapeMode>()
            .name("marker-shape-mode")
            .description("Render style for the marker box.")
            .defaultValue(ShapeMode.Both)
            .visible(markerGuidance::get)
            .build());

    private final Setting<SettingColor> markerSideColor = sgMarker.add(new ColorSetting.Builder()
            .name("marker-side-color")
            .defaultValue(new SettingColor(0, 200, 255, 40))
            .visible(markerGuidance::get)
            .build());

    private final Setting<SettingColor> markerLineColor = sgMarker.add(new ColorSetting.Builder()
            .name("marker-line-color")
            .defaultValue(new SettingColor(0, 200, 255, 255))
            .visible(markerGuidance::get)
            .build());

    // ── Auto-Drop ─────────────────────────────────────────────────────────────
    private final Setting<Boolean> autoDrop = sgDrop.add(new BoolSetting.Builder()
            .name("auto-drop")
            .description("Automatically drop shulkers from the donkey's inventory after teleporting.")
            .defaultValue(true)
            .build());

    private final Setting<Integer> tpWaitTime = sgDrop.add(new IntSetting.Builder()
            .name("tp-wait-time")
            .description("Ticks to wait after /home dup before trying to drop items.")
            .defaultValue(40).min(0).sliderMax(100)
            .visible(autoDrop::get)
            .build());

    private final Setting<String> doneHomeName = sgDrop.add(new StringSetting.Builder()
            .name("done-home-name")
            .description("/home to send after dropping all shulkers.")
            .defaultValue("don")
            .visible(autoDrop::get)
            .build());

    // ──────────────────────────────────────────────────────────────────────────
    // State machine
    // ──────────────────────────────────────────────────────────────────────────
    private enum State {
        SNEAK_DONKEY, // 1. Activate sneak + send server PRESS_SHIFT_KEY packet
        CLICK_DONKEY, // 2. Right-click donkey to open its inventory server-side
        MOUNT_BOAT, // 3. Mount the nearby boat
        RIDE_WAIT_EXIT, // 4. Ride toward marker (optional) and wait for server kick
        SEND_HOME, // 5. Send /home dup
        WAIT_FOR_TP, // 6. Wait for teleport
        DROP_SHULKERS, // 7. Pull shulkers from donkey inventory
        SEND_HOME_DON, // 8. Send /home don
        WAIT_RESTART, // 9. Pause before next cycle
        DONE
    }

    private State state;
    private int timer;
    private int mountAttempts;
    private int rideWaitTicks;
    private Entity targetDonkey;

    public DonkeyBoatTpDupe() {
        super(Main_Addon.CATEGORY, "DonkeyBoatTpDupe",
                "Donkey inventory bug → boat → server kick → /home dup → shulkers.");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Module lifecycle
    // ──────────────────────────────────────────────────────────────────────────
    @Override
    public void onActivate() {
        state = State.SNEAK_DONKEY;
        timer = 0;
        mountAttempts = 0;
        rideWaitTicks = 0;
        targetDonkey = null;
        setPressed(mc.options.forwardKey, false);
        if (debug.get())
            info("§a[DonkeyBoatTpDupe] §eStarted. Looking for chested donkey...");
    }

    @Override
    public void onDeactivate() {
        setPressed(mc.options.sneakKey, false);
        setPressed(mc.options.forwardKey, false);
        Sneak sm = Modules.get().get(Sneak.class);
        if (sm != null && sm.isActive())
            sm.toggle();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Tick handler
    // ──────────────────────────────────────────────────────────────────────────
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Utils.canUpdate() || mc.player == null || mc.world == null || mc.interactionManager == null)
            return;
        if (timer > 0) {
            timer--;
            return;
        }

        switch (state) {

            // ── 1. Activate sneak ─────────────────────────────────────────────
            case SNEAK_DONKEY -> {
                targetDonkey = findChestedDonkey();
                if (targetDonkey == null) {
                    error("No chested donkey found within 4.5 blocks.");
                    toggle();
                    return;
                }
                // Enable sneak on client AND notify server so right-click opens chest, not mounts
                Sneak sm = Modules.get().get(Sneak.class);
                if (sm != null && !sm.isActive())
                    sm.toggle();
                setPressed(mc.options.sneakKey, true);
                mc.player.setSneaking(true);
                mc.player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket(
                        new net.minecraft.util.PlayerInput(false, false, false, false, false, true, false)));

                if (debug.get())
                    info("[1] Sneaking. Clicking donkey in 2 ticks...");
                state = State.CLICK_DONKEY;
                timer = 2;
            }

            // ── 2. Click donkey to open inventory ────────────────────────────
            case CLICK_DONKEY -> {
                if (targetDonkey == null || !targetDonkey.isAlive()) {
                    error("Donkey disappeared.");
                    toggle();
                    return;
                }
                EntityHitResult hit = new EntityHitResult(targetDonkey, targetDonkey.getBoundingBox().getCenter());
                ActionResult r = mc.interactionManager.interactEntityAtLocation(mc.player, targetDonkey, hit,
                        Hand.MAIN_HAND);
                mc.player.swingHand(Hand.MAIN_HAND);
                if (debug.get())
                    info("[2] Donkey click: " + r);

                // Release sneak immediately after
                setPressed(mc.options.sneakKey, false);
                mc.player.setSneaking(false);
                mc.player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket(
                        new net.minecraft.util.PlayerInput(false, false, false, false, false, false, false)));
                Sneak sm = Modules.get().get(Sneak.class);
                if (sm != null && sm.isActive())
                    sm.toggle();

                mountAttempts = 0;
                state = State.MOUNT_BOAT;
                timer = mountDelay.get();
            }

            // ── 3. Mount the boat ─────────────────────────────────────────────
            case MOUNT_BOAT -> {
                if (mc.player.hasVehicle()) {
                    rideWaitTicks = 0;
                    if (debug.get())
                        info("[3] Mounted on boat. Waiting for server kick...");
                    state = State.RIDE_WAIT_EXIT;
                    return;
                }
                Entity boat = findBoat();
                if (boat == null) {
                    error("No boat found within " + boatRange.get() + " blocks.");
                    toggle();
                    return;
                }
                rotateTowards(new Vec3d(boat.getX(), boat.getY(), boat.getZ()));
                ActionResult r = mc.interactionManager.interactEntity(mc.player, boat, Hand.MAIN_HAND);
                if (r != ActionResult.SUCCESS) {
                    EntityHitResult hit = new EntityHitResult(boat, boat.getBoundingBox().getCenter());
                    r = mc.interactionManager.interactEntityAtLocation(mc.player, boat, hit, Hand.MAIN_HAND);
                }
                if (debug.get())
                    info("[3] Mount attempt " + (mountAttempts + 1) + ": " + r);
                mountAttempts++;
                if (mountAttempts >= 15) {
                    error("Could not mount boat after 15 attempts.");
                    toggle();
                    return;
                }
                timer = 3;
            }

            // ── 4. Ride in boat, optionally move to marker, wait for server kick
            case RIDE_WAIT_EXIT -> {
                if (!mc.player.hasVehicle()) {
                    // Server kicked us — proceed
                    if (debug.get())
                        info("[4] Server kicked us from boat (tick " + rideWaitTicks + "). Sending /home...");
                    state = State.SEND_HOME;
                    timer = teleportDelay.get();
                    return;
                }

                rideWaitTicks++;

                // Safety timeout
                int maxWait = serverKickTimeout.get();
                if (maxWait > 0 && rideWaitTicks >= maxWait) {
                    error("Server never kicked us from boat (" + maxWait + " ticks). Aborting.");
                    toggle();
                    return;
                }

                // Optional: move to marker while riding
                if (markerGuidance.get()) {
                    moveTowardMarker(markerPos.get());
                }
            }

            // ── 5. Send /home dup ─────────────────────────────────────────────
            case SEND_HOME -> {
                ChatUtils.sendPlayerMsg("/home " + homeName.get());
                if (debug.get())
                    info("[5] Sent: /home " + homeName.get());
                if (autoDrop.get()) {
                    state = State.WAIT_FOR_TP;
                    timer = tpWaitTime.get();
                } else {
                    nextOrStop();
                }
            }

            // ── 6. Wait for teleport ──────────────────────────────────────────
            case WAIT_FOR_TP -> {
                if (debug.get())
                    info("[6] Teleport done. Checking donkey inventory...");
                state = State.DROP_SHULKERS;
            }

            // ── 7. Drop shulkers from donkey's inventory ──────────────────────
            case DROP_SHULKERS -> {
                var handler = mc.player.currentScreenHandler;
                if (handler == null || handler.slots.size() <= 36) {
                    // Inventory isn't the donkey's — skip drop
                    if (debug.get())
                        info("[7] Donkey inventory not open, skipping drop.");
                    state = State.SEND_HOME_DON;
                    timer = 5;
                    return;
                }
                int playerStart = handler.slots.size() - 36;
                for (int i = 0; i < playerStart; i++) {
                    ItemStack stack = handler.getSlot(i).getStack();
                    if (!stack.isEmpty() && stack.getItem() instanceof BlockItem bi
                            && bi.getBlock() instanceof ShulkerBoxBlock) {
                        mc.interactionManager.clickSlot(handler.syncId, i, 1, SlotActionType.THROW, mc.player);
                        if (debug.get())
                            info("[7] Dropped shulker at slot " + i + ".");
                        timer = 2;
                        return;
                    }
                }
                if (debug.get())
                    info("[7] All shulkers dropped.");
                state = State.SEND_HOME_DON;
                timer = 10;
            }

            // ── 8. Send /home don ─────────────────────────────────────────────
            case SEND_HOME_DON -> {
                ChatUtils.sendPlayerMsg("/home " + doneHomeName.get());
                if (debug.get())
                    info("[8] Sent: /home " + doneHomeName.get());
                nextOrStop();
            }

            // ── 9. Wait before next cycle ─────────────────────────────────────
            case WAIT_RESTART -> {
                if (debug.get())
                    info("§aRestarting cycle...");
                onActivate();
            }

            case DONE -> toggle();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Render
    // ──────────────────────────────────────────────────────────────────────────
    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!markerGuidance.get() || !renderMarker.get())
            return;
        event.renderer.box(markerPos.get(), markerSideColor.get(), markerLineColor.get(), markerShapeMode.get(), 0);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helper methods
    // ──────────────────────────────────────────────────────────────────────────

    /** Move toward marker using BoatTp, BoatFly, or standard movement. */
    private void moveTowardMarker(BlockPos marker) {
        if (marker == null)
            return;

        Entity vehicle = mc.player.getVehicle();
        Vec3d target = Vec3d.ofCenter(marker);
        Vec3d pos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        double dist = pos.distanceTo(target);
        double radius = markerRadius.get();

        // Already there — stop
        if (dist <= radius) {
            setPressed(mc.options.forwardKey, false);
            if (vehicle != null)
                vehicle.setVelocity(0, 0, 0);
            return;
        }

        if (vehicle == null) {
            // Not in a vehicle — just walk
            setPressed(mc.options.forwardKey, true);
            rotateTowards(target);
            return;
        }

        switch (boatMode.get()) {
            case BoatTp -> {
                // Teleport boat + player + notify server
                vehicle.setPosition(target.x, target.y, target.z);
                mc.player.setPosition(target.x, target.y, target.z);
                mc.player.networkHandler.sendPacket(new VehicleMoveC2SPacket(
                        target, vehicle.getYaw(), vehicle.getPitch(), vehicle.isOnGround()));
            }
            case BoatFly -> {
                // Set velocity toward marker and sync to server
                rotateTowards(target);
                Vec3d vehiclePos = new Vec3d(vehicle.getX(), vehicle.getY(), vehicle.getZ());
                Vec3d diff = target.subtract(vehiclePos);
                double speed = Math.min(diff.length(), boatFlySpeed.get());
                Vec3d velocity = diff.normalize().multiply(speed);
                vehicle.setVelocity(velocity.x, velocity.y, velocity.z);
                // Notify server of new vehicle position each tick
                mc.player.networkHandler.sendPacket(new VehicleMoveC2SPacket(
                        vehiclePos.add(velocity), vehicle.getYaw(), vehicle.getPitch(), false));
            }
            case None -> {
                setPressed(mc.options.forwardKey, true);
                rotateTowards(target);
            }
        }
    }

    private Entity findChestedDonkey() {
        return mc.world.getOtherEntities(mc.player,
                new Box(mc.player.getBlockPos()).expand(4.5),
                e -> e instanceof AbstractDonkeyEntity d && d.hasChest())
                .stream().min((a, b) -> Float.compare(a.distanceTo(mc.player), b.distanceTo(mc.player)))
                .orElse(null);
    }

    private Entity findBoat() {
        return mc.world.getOtherEntities(mc.player,
                new Box(mc.player.getBlockPos()).expand(boatRange.get()),
                e -> e instanceof BoatEntity)
                .stream().min((a, b) -> Float.compare(a.distanceTo(mc.player), b.distanceTo(mc.player)))
                .orElse(null);
    }

    private void rotateTowards(Vec3d target) {
        Vec3d from = mc.player.getEyePos();
        double dx = target.x - from.x;
        double dy = target.y - from.y;
        double dz = target.z - from.z;
        double dxz = Math.sqrt(dx * dx + dz * dz);
        mc.player.setYaw((float) (MathHelper.atan2(dz, dx) * 57.2957763671875) - 90f);
        mc.player.setPitch((float) (-(MathHelper.atan2(dy, dxz) * 57.2957763671875)));
    }

    private void setPressed(KeyBinding key, boolean pressed) {
        key.setPressed(pressed);
        Input.setKeyState(key, pressed);
    }

    private void nextOrStop() {
        if (continuous.get()) {
            state = State.WAIT_RESTART;
            timer = restartDelay.get();
        } else {
            state = State.DONE;
            toggle();
        }
    }

    private void info(String msg) {
        if (debug.get())
            ChatUtils.info(msg);
    }

    private void error(String msg) {
        ChatUtils.error("[DonkeyBoatTpDupe] " + msg);
    }

    
    @Override
    public String getInfoString() {
        return state != null ? state.name() : "";
    }
}
