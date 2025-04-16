package com.zicob.sleeppoll;

import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class MessageHandler {


    public static void broadcastPollInitiatorLeft(MinecraftServer server) {
        broadcastMessage(server, "The poll initiator has left the bed. Ending poll.", Formatting.YELLOW);
    }

    public static void broadcastSleepingThresholdReached(MinecraftServer server) {
        broadcastMessage(server, "The amount of sleeping players reached the sleeping goal. Ending poll.", Formatting.GREEN);
    }

    public static void broadcastNightSkipped(MinecraftServer server) {
        broadcastMessage(server, "The night has been skipped!", Formatting.GREEN);
    }

    public static void broadcastNightNotSkipped(MinecraftServer server) {
        broadcastMessage(server, "The night will not be skipped.", Formatting.DARK_RED);
    }

    private static void broadcastMessage(MinecraftServer server, String message, Formatting color) {
        server.getPlayerManager().broadcast(Text.literal(message).styled(style -> style.withColor(color)), false);
    }
}
