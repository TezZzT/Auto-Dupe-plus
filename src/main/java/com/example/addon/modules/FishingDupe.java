package com.example.addon.modules;

import com.example.addon.Main_Addon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class FishingDupe extends Module {
    private BufferedWriter logFile;
    private long tick;
    private long lastSnapshot;
    private int lastHookId = Integer.MIN_VALUE;

    public FishingDupe() {
        super(Main_Addon.CATEGORY, "fishing-dupe",
            "Passive telemetry for authorized fishing-plugin testing.");
    }

    @Override
    public void onActivate() {
        tick = 0;
        lastSnapshot = 0;
        lastHookId = Integer.MIN_VALUE;

        try {
            Path dir = mc.runDirectory.toPath().resolve("fishingdupe");
            Files.createDirectories(dir);

            String name = "session-" +
                Instant.now().toString().replace(':', '-') + ".log";

            logFile = Files.newBufferedWriter(
                dir.resolve(name),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
            );

            write("=== FishingDupe START ===");
            write("gameVersion=" + MinecraftClient.getInstance().getGameVersion());

            if (mc.getCurrentServer() != null)
                write("server=" + clean(mc.getCurrentServer().address));
            else
                write("server=singleplayer");
        } catch (IOException e) {
            error("Could not create FishingDupe log: " + e.getMessage());
        }
    }

    @Override
    public void onDeactivate() {
        write("=== FishingDupe STOP ===");

        if (logFile != null) {
            try {
                logFile.close();
            } catch (IOException ignored) {}
            logFile = null;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        tick++;
        Entity hook = mc.player.fishHook;

        int hookId = hook == null ? Integer.MIN_VALUE : hook.getId();

        if (hookId != lastHookId) {
            lastHookId = hookId;
            snapshot(hook, "HOOK_CHANGE");
            return;
        }

        long now = System.nanoTime();
        if (now - lastSnapshot >= 1_000_000_000L) {
            lastSnapshot = now;
            snapshot(hook, "PERIODIC");
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet == null) return;

        String name = event.packet.getClass().getSimpleName();
        String lower = name.toLowerCase();

        if (lower.contains("fishing")
            || lower.contains("inventory")
            || lower.contains("slot")
            || lower.contains("entity")
            || lower.contains("sound")
            || lower.contains("particle")
            || lower.contains("game")) {

            write("PACKET_RX class=" + event.packet.getClass().getName()
                + " data=" + clean(event.packet.toString()));
        }
    }

    private void snapshot(Entity hook, String reason) {
        write("SNAPSHOT reason=" + reason
            + " tick=" + tick
            + " nano=" + System.nanoTime());

        write("PLAYER pos=" + mc.player.getX() + ","
            + mc.player.getY() + "," + mc.player.getZ()
            + " block=" + mc.player.getBlockPos()
            + " dimension=" + mc.world.getRegistryKey().getValue());

        ItemStack main = mc.player.getMainHandStack();
        ItemStack off = mc.player.getOffHandStack();

        write("ITEMS main=" + item(main)
            + " offhand=" + item(off)
            + " selectedSlot=" + mc.player.getInventory().getSelectedSlot());

        if (hook == null) {
            write("HOOK none");
            return;
        }

        BlockPos pos = hook.getBlockPos();

        Identifier biome = mc.world.getBiome(pos)
            .getKey()
            .map(key -> key.getValue())
            .orElse(null);

        write("HOOK id=" + hook.getId()
            + " type=" + hook.getType()
            + " pos=" + hook.getX() + "," + hook.getY() + "," + hook.getZ()
            + " block=" + pos
            + " biome=" + biome
            + " removed=" + hook.isRemoved());
    }

    private String item(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";

        return stack.getItem()
            + " x" + stack.getCount()
            + " components=" + clean(stack.getComponents().toString());
    }

    private String clean(String s) {
        if (s == null) return "null";
        return s.replace('\n', ' ').replace('\r', ' ');
    }

    private synchronized void write(String message) {
        String line = "[" + Instant.now() + "] " + message;
        LOG.info(line);

        if (logFile == null) return;

        try {
            logFile.write(line);
            logFile.newLine();
            logFile.flush();
        } catch (IOException e) {
            LOG.error("FishingDupe log error", e);
        }
    }
}
