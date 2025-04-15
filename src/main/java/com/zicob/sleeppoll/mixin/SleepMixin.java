package com.zicob.sleeppoll.mixin;

import com.zicob.sleeppoll.SleepPoll;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
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
			SleepPoll.LOGGER.info("Player: " + player.getName().getString() + " started sleeping at " + pos);
			// Call the createPoll method
			SleepPoll.getInstance().createPoll(player.getServer(), player, pos);
		}
	}

	@Inject(method = "wakeUp", at = @At("HEAD"))
	private void onPlayerWakeUp(boolean bl, boolean updateSleepingPlayers, CallbackInfo ci) {
		if ((Object) this instanceof ServerPlayerEntity player) {
			SleepPoll.LOGGER.info("Player: " + player.getName().getString() + " woke up.");
			SleepPoll sleepPoll = SleepPoll.getInstance();

			// Check if the poll is active and the player leaving the bed initiated it
			if (sleepPoll.isPollActive() && sleepPoll.getPollInitiator().equals(player.getUuid())) {
				sleepPoll.endPoll(player.getServer());
			}
		}
	}
}