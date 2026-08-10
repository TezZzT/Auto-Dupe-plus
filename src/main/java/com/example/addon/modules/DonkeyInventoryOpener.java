package com.example.addon.modules;

import com.example.addon.Main_Addon;
import com.example.addon.mixin.IPlayerInventoryMixin;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IClientPlayerInteractionManager;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HorseScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.passive.DonkeyEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;

public class DonkeyInventoryOpener extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum distance to search for a chested donkey.")
        .defaultValue(5.0)
        .min(1.0)
        .sliderMax(10.0)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate-player")
        .description("Rotates toward the donkey before interacting.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> openTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("open-timeout")
        .description("Ticks to wait for the donkey inventory to open before giving up.")
        .defaultValue(20)
        .min(1)
        .sliderMax(60)
        .build()
    );

    private final Setting<Integer> sneakDelay = sgGeneral.add(new IntSetting.Builder()
        .name("sneak-delay")
        .description("Ticks to wait after sneaking before interacting.")
        .defaultValue(3)
        .min(0)
        .sliderMax(10)
        .build()
    );

    private enum Phase {
        PREPARE,
        WAIT_SNEAK,
        INTERACT,
        WAIT_OPEN,
        DONE
    }

    private Phase phase;
    private int timer;
    private int previousSlot = -1;
    private DonkeyEntity targetDonkey;

    public DonkeyInventoryOpener() {
        super(Main_Addon.CATEGORY, "donkey-inventory-opener", "Opens the inventory of the nearest chested donkey.");
    }

    @Override
    public void onActivate() {
        phase = Phase.PREPARE;
        timer = 0;
        previousSlot = -1;
        targetDonkey = null;
    }

    @Override
    public void onDeactivate() {
        releaseSneak();
        restoreSlot();
        targetDonkey = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Utils.canUpdate() || mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (timer > 0) {
            timer--;
            return;
        }

        switch (phase) {
            case PREPARE -> {
                targetDonkey = findDonkey();
                if (targetDonkey == null) {
                    ChatUtils.error("No chested donkey found nearby.");
                    finish(false);
                    return;
                }

                if (mc.player.getMainHandStack().isEmpty() == false && previousSlot == -1) {
                    previousSlot = ((IPlayerInventoryMixin) mc.player.getInventory()).getSelectedSlot();
                    int emptySlot = findEmptyHotbarSlot();
                    if (emptySlot != -1) {
                        ((IPlayerInventoryMixin) mc.player.getInventory()).setSelectedSlot(emptySlot);
                        syncSelectedSlot();
                    }
                }

                // First sneak, then click. This matches the manual interaction order.
                pressSneak();
                if (rotate.get()) rotateTowards(targetDonkey);
                phase = Phase.WAIT_SNEAK;
                timer = sneakDelay.get();
            }

            case WAIT_SNEAK -> {
                if (targetDonkey == null || !targetDonkey.isAlive()) {
                    ChatUtils.error("The donkey disappeared.");
                    finish(false);
                    return;
                }

                phase = Phase.INTERACT;
            }

            case INTERACT -> {
                if (targetDonkey == null || !targetDonkey.isAlive()) {
                    ChatUtils.error("The donkey disappeared.");
                    finish(false);
                    return;
                }

                // Click the donkey only after sneak has already been sent.
                EntityHitResult hitResult = new EntityHitResult(targetDonkey, targetDonkey.getBoundingBox().getCenter());
                ActionResult result = mc.interactionManager.interactEntityAtLocation(mc.player, targetDonkey, hitResult, Hand.MAIN_HAND);
                ChatUtils.info("Donkey interaction result: " + result);
                phase = Phase.WAIT_OPEN;
                timer = openTimeout.get();
            }

            case WAIT_OPEN -> {
                if (mc.currentScreen instanceof HorseScreen) {
                    ChatUtils.info("Donkey inventory opened.");
                    finish(true);
                    return;
                }

                if (timer <= 0) {
                    ChatUtils.error("The donkey inventory did not open.");
                    finish(false);
                }
            }

            case DONE -> {
            }
        }
    }

    private DonkeyEntity findDonkey() {
        return mc.world.getEntitiesByClass(
            DonkeyEntity.class,
            mc.player.getBoundingBox().expand(range.get()),
            d -> d.hasChest()
        ).stream().min((a, b) -> Float.compare(a.distanceTo(mc.player), b.distanceTo(mc.player))).orElse(null);
    }

    private int findEmptyHotbarSlot() {
        for (int slot = 0; slot < 9; slot++) {
            if (mc.player.getInventory().getStack(slot).isEmpty()) return slot;
        }
        return -1;
    }

    private void pressSneak() {
        setPressed(mc.options.sneakKey, true);
        mc.player.setSneaking(true);
    }

    private void releaseSneak() {
        if (mc.player == null) return;
        setPressed(mc.options.sneakKey, false);
        mc.player.setSneaking(false);
    }

    private void restoreSlot() {
        if (previousSlot == -1 || mc.player == null) return;
        ((IPlayerInventoryMixin) mc.player.getInventory()).setSelectedSlot(previousSlot);
        syncSelectedSlot();
        previousSlot = -1;
    }

    private void syncSelectedSlot() {
        if (mc.interactionManager != null) {
            ((IClientPlayerInteractionManager) mc.interactionManager).meteor$syncSelected();
        }
    }

    private void rotateTowards(DonkeyEntity donkey) {
        Vec3d from = mc.player.getEyePos();
        Vec3d to = donkey.getBoundingBox().getCenter();
        double diffX = to.x - from.x;
        double diffY = to.y - from.y;
        double diffZ = to.z - from.z;
        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float yaw = (float) (MathHelper.atan2(diffZ, diffX) * 57.2957763671875D) - 90.0F;
        float pitch = (float) (-(MathHelper.atan2(diffY, diffXZ) * 57.2957763671875D));

        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
    }

    private void finish(boolean opened) {
        releaseSneak();
        restoreSlot();
        targetDonkey = null;
        phase = Phase.DONE;
        timer = 0;
        toggle();
    }

    private void setPressed(KeyBinding key, boolean pressed) {
        key.setPressed(pressed);
        Input.setKeyState(key, pressed);
    }
}
