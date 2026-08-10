package com.example.addon.modules;

import com.example.addon.Main_Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.Sneak;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HorseScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.AbstractDonkeyEntity;
import net.minecraft.entity.vehicle.MinecartEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public class DonkeyCartDupe extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> distanceThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("distance-threshold")
        .description("Distancia en bloques recorridos en el carrito antes de extraer los shulkers (por defecto 128).")
        .defaultValue(128)
        .min(10)
        .sliderMax(300)
        .build()
    );

    private final Setting<Boolean> autoMoveCart = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-move-cart")
        .description("Mueve el carrito automáticamente presionando la tecla de avanzar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> shulkersOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("shulkers-only")
        .description("Extrae solo cajas de Shulker del inventario del burro.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoDismount = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-dismount")
        .description("Se desmonta del carrito automáticamente al finalizar la extracción.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Muestra mensajes detallados en el chat.")
        .defaultValue(true)
        .build()
    );

    private enum State {
        SNEAK_INTERACT_DONKEY,
        MOUNT_MINECART,
        RIDING_CART,
        TAKE_SHULKERS,
        FINISHED
    }

    private State state = State.SNEAK_INTERACT_DONKEY;
    private Vec3d startPos = null;
    private int delayTimer = 0;

    public DonkeyCartDupe() {
        super(Main_Addon.CATEGORY, "DonkeyCartDupe", "Dupe de burro agachado + carrito con raíles al recorrer 128 bloques.");
    }

    @Override
    public void onActivate() {
        state = State.SNEAK_INTERACT_DONKEY;
        startPos = null;
        delayTimer = 0;
        if (debug.get()) ChatUtils.info("DonkeyCartDupe activado. Buscando burro con cofre cercano...");
    }

    @Override
    public void onDeactivate() {
        setPressed(mc.options.forwardKey, false);
        Sneak sneakModule = Modules.get().get(Sneak.class);
        if (sneakModule != null && sneakModule.isActive()) {
            sneakModule.toggle();
        }
        if (debug.get()) ChatUtils.info("DonkeyCartDupe desactivado.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Utils.canUpdate() || mc.player == null || mc.interactionManager == null) return;

        if (delayTimer > 0) {
            delayTimer--;
            return;
        }

        switch (state) {
            case SNEAK_INTERACT_DONKEY -> {
                Entity donkey = findChestedDonkey();
                if (donkey == null) {
                    if (debug.get()) ChatUtils.error("No se encontró ningún burro con cofre cerca (máx 6 bloques).");
                    toggle();
                    return;
                }

                // Agacharse
                Sneak sneakModule = Modules.get().get(Sneak.class);
                if (!mc.player.isSneaking()) {
                    if (sneakModule != null && !sneakModule.isActive()) sneakModule.toggle();
                    setPressed(mc.options.sneakKey, true);
                }

                // Click derecho al burro con inventario/cofre
                ActionResult result = mc.interactionManager.interactEntity(mc.player, donkey, Hand.MAIN_HAND);
                if (debug.get()) ChatUtils.info("Interacción con burro agachado: " + result);

                setPressed(mc.options.sneakKey, false);
                state = State.MOUNT_MINECART;
                delayTimer = 10;
            }

            case MOUNT_MINECART -> {
                MinecartEntity cart = findMinecart();
                if (cart == null) {
                    if (debug.get()) ChatUtils.error("No se encontró ningún carrito de raíles cercano (máx 6 bloques).");
                    toggle();
                    return;
                }

                // Subirse al carrito
                ActionResult mountResult = mc.interactionManager.interactEntity(mc.player, cart, Hand.MAIN_HAND);
                if (debug.get()) ChatUtils.info("Intentando subir al carrito: " + mountResult);

                if (mc.player.hasVehicle()) {
                    startPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
                    state = State.RIDING_CART;
                    if (debug.get()) ChatUtils.info("Subido al carrito. Recorriendo la distancia de " + distanceThreshold.get() + " bloques...");
                } else {
                    delayTimer = 5;
                }
            }

            case RIDING_CART -> {
                if (!mc.player.hasVehicle()) {
                    if (debug.get()) ChatUtils.error("Se perdió el vehículo (carrito). Cancelando...");
                    toggle();
                    return;
                }

                if (autoMoveCart.get()) {
                    setPressed(mc.options.forwardKey, true);
                }

                if (startPos == null) {
                    startPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
                }

                double dist = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()).distanceTo(startPos);
                if (dist >= distanceThreshold.get()) {
                    setPressed(mc.options.forwardKey, false);
                    if (debug.get()) ChatUtils.info("Se alcanzaron " + String.format("%.1f", dist) + " bloques. Extrayendo shulkers/ítems...");
                    state = State.TAKE_SHULKERS;
                    delayTimer = 5;
                }
            }

            case TAKE_SHULKERS -> {
                int syncId = mc.player.currentScreenHandler.syncId;

                // Extraer ítems de los slots del cofre del burro (slots 2 a 16 aproximadamente)
                boolean tookItems = false;
                for (int slotId = 2; slotId < mc.player.currentScreenHandler.slots.size(); slotId++) {
                    ItemStack stack = mc.player.currentScreenHandler.getSlot(slotId).getStack();
                    if (stack.isEmpty()) continue;

                    boolean isShulker = stack.getItem() instanceof BlockItem blockItem && 
                                        blockItem.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock;

                    if (!shulkersOnly.get() || isShulker) {
                        mc.interactionManager.clickSlot(syncId, slotId, 0, SlotActionType.QUICK_MOVE, mc.player);
                        tookItems = true;
                    }
                }

                if (tookItems && debug.get()) {
                    ChatUtils.info("¡Ítems / Shulkers extraídos exitosamente!");
                }

                if (autoDismount.get() && mc.player.hasVehicle()) {
                    mc.player.dismountVehicle();
                }

                state = State.FINISHED;
                if (debug.get()) ChatUtils.info("Dupe completado.");
                toggle();
            }

            case FINISHED -> toggle();
        }
    }

    private Entity findChestedDonkey() {
        return mc.world.getOtherEntities(
            mc.player,
            new Box(mc.player.getBlockPos()).expand(6.0),
            e -> e instanceof AbstractDonkeyEntity donkey && donkey.hasChest()
        ).stream().min((a, b) -> Float.compare(a.distanceTo(mc.player), b.distanceTo(mc.player))).orElse(null);
    }

    private MinecartEntity findMinecart() {
        return (MinecartEntity) mc.world.getOtherEntities(
            mc.player,
            new Box(mc.player.getBlockPos()).expand(6.0),
            e -> e instanceof MinecartEntity
        ).stream().min((a, b) -> Float.compare(a.distanceTo(mc.player), b.distanceTo(mc.player))).orElse(null);
    }

    private void setPressed(KeyBinding key, boolean pressed) {
        key.setPressed(pressed);
        Input.setKeyState(key, pressed);
    }

    @Override
    public String getInfoString() {
        return state.name();
    }
}
