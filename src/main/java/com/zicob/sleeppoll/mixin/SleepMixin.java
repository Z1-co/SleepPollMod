package com.zicob.sleeppoll.mixin;

import com.zicob.sleeppoll.MessageHandler;
import com.zicob.sleeppoll.SleepPoll;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntity.class)
public abstract class SleepMixin {

	@Inject(method = "trySleep", at = @At("HEAD"))
	private void onPlayerSleep(BlockPos pos, CallbackInfoReturnable<ActionResult> cir) {
		if ((Object) this instanceof ServerPlayerEntity player) {
			SleepPoll sleepPoll = SleepPoll.getInstance();
			MinecraftServer server = player.getServer();

			if (server != null) {
				int requiredPercentage = server.getGameRules().getInt(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
				int currentSleepingPercentage = sleepPoll.calculateSleepingPercentage(server.getOverworld());

				// Check if the sleeping percentage is already met
				if (currentSleepingPercentage >= requiredPercentage) {
					server.getOverworld().setTimeOfDay(0);
					MessageHandler.broadcastSleepingThresholdReached(server);
					return; // Do not create a poll
				}

				// Create a poll if the threshold is not met
				sleepPoll.createPoll(server, player, pos);
			}
		}
	}

	@Inject(method = "wakeUp", at = @At("HEAD"))
	private void onPlayerWakeUp(boolean bl, boolean updateSleepingPlayers, CallbackInfo ci) {
		if ((Object) this instanceof ServerPlayerEntity player) {
			SleepPoll sleepPoll = SleepPoll.getInstance();

			// Check if the poll is active and the player leaving the bed initiated it
			if (sleepPoll.isPollActive() && sleepPoll.getPollInitiator().equals(player.getUuid())) {
				MessageHandler.broadcastPollInitiatorLeft(player.getServer());
				sleepPoll.endPoll(player.getServer());
			}
		}
	}
}