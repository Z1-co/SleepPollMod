package com.zicob.sleeppoll;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class SleepPoll implements ModInitializer {
	public static final String MOD_ID = "sleeppollmod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static SleepPoll instance; // Static instance for access
	private final Map<UUID, Boolean> votes = new HashMap<>();
	private boolean pollActive = false;
	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
	private ScheduledFuture<?> pollTask; // Store the scheduled task
	private ScheduledFuture<?> gradualTask; // Store the gradual task

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing SleepPoll mod");
		instance = this; // Set the static instance

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, dedicated) -> {
			dispatcher.register(CommandManager.literal("vote")
					.then(CommandManager.literal("yes").executes(context -> {
						ServerPlayerEntity player = context.getSource().getPlayer();
						SleepPoll.getInstance().castVote(player, true);
						return 1;
					}))
					.then(CommandManager.literal("no").executes(context -> {
						ServerPlayerEntity player = context.getSource().getPlayer();
						SleepPoll.getInstance().castVote(player, false);
						return 1;
					}))
			);
		});

		// Shut down the scheduler when the server stops
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			scheduler.shutdown();
		});
	}

	public static SleepPoll getInstance() {
		return instance; // Provide access to the instance
	}

	private UUID pollInitiator; // Store the UUID of the player who initiated the poll

	public UUID getPollInitiator() {
		return pollInitiator;
	}

	public boolean isPollActive() {
		return pollActive;
	}

	public void createPoll(MinecraftServer server, ServerPlayerEntity player, BlockPos pos) {
		if (pollActive) {
			return;
		}

		int requiredPercentage = server.getGameRules().getInt(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
		int currentSleepingPercentage = calculateSleepingPercentage(server.getOverworld());

		// If the sleeping percentage is already met, skip the poll creation
		if (currentSleepingPercentage >= requiredPercentage) {
			server.getOverworld().setTimeOfDay(0);
			return;
		}

		pollActive = true;
		pollInitiator = player.getUuid();
		votes.clear();

		// Send the poll in the chat
		createPollMessage(server);

		// Add all players to the boss bar
		server.getPlayerManager().getPlayerList().forEach(bossBar::addPlayer);

		// Schedule the task and store the future
		pollTask = scheduler.scheduleAtFixedRate(() -> {
			server.execute(() -> updateSleepingProgress(server));
		}, 0, 1, TimeUnit.SECONDS);
	}
	public void castVote(ServerPlayerEntity player, boolean vote) {
		UUID playerId = player.getUuid();

		if (playerId.equals(pollInitiator)) {
			player.sendMessage(Text.literal("You cannot vote on a poll you initiated!")
					.styled(style -> style.withColor(0xFF0000)), false);
			return;
		}

		if (votes.containsKey(playerId)) {
			player.sendMessage(Text.literal("You have already voted!")
					.styled(style -> style.withColor(0xFF0000)), false);
			return;
		}

		votes.put(playerId, vote);
		player.sendMessage(Text.literal("Your vote has been recorded: " + (vote ? "Yes" : "No"))
				.styled(style -> style.withColor(0x00FF00)), false);

		MinecraftServer server = player.getServer();
		if (server != null) {
			updateSleepingProgress(server);

			// Check if all players have voted
			if (votes.size() == server.getPlayerManager().getPlayerList().size()) {
				server.execute(() -> endPoll(server)); // End the poll and handle the outcome
			}
		}
	}

	public void endPoll(MinecraftServer server) {
		if (!pollActive) return; // Prevent duplicate calls to endPoll

		pollActive = false;

		// Cancel the scheduled task
		if (pollTask != null && !pollTask.isCancelled()) {
			pollTask.cancel(false);
			pollTask = null;
		}

		// Remove all players from the boss bar
		bossBar.clearPlayers();

		// Handle the outcome of the poll
		handlePollOutcome(server);
	}

	private void createPollMessage(MinecraftServer server) {
		// Create clickable messages for voting
		Text voteYes = Text.literal("[Vote YES]")
				.styled(style -> style.withColor(0x00FF00)
						.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/vote yes"))
						.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to vote YES"))));

		Text voteNo = Text.literal("[Vote NO]")
				.styled(style -> style.withColor(0xFF0000)
						.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/vote no"))
						.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to vote NO"))));

		Text pollMessage = Text.literal("A poll to skip the night has started! ")
				.append(voteYes)
				.append(" ")
				.append(voteNo);

		// Broadcast the poll message
		server.getPlayerManager().broadcast(pollMessage, false);
	}

	public int calculateSleepingPercentage(ServerWorld world) {
		// Count players who are physically sleeping
		long sleepingPlayers = world.getPlayers().stream()
				.filter(ServerPlayerEntity::isSleeping)
				.count();

		// Count players who voted "yes" but are not physically sleeping
		long yesVotes = votes.entrySet().stream()
				.filter(Map.Entry::getValue) // Only count "yes" votes
				.filter(entry -> {
					ServerPlayerEntity player = (ServerPlayerEntity) world.getPlayerByUuid(entry.getKey());
					return player != null && !player.isSleeping(); // Exclude players already counted as sleeping
				})
				.count();

		// Combine sleeping players and "yes" votes
		long totalContributors = sleepingPlayers + yesVotes;

		// Get the total number of players
		long totalPlayers = world.getPlayers().size();

		if (totalPlayers == 0) {
			return 0; // Avoid division by zero
		}

		// Calculate the percentage
		return (int) ((totalContributors * 100) / totalPlayers);
	}

	private void updateSleepingProgress(MinecraftServer server) {
		// Calculate the updated sleeping percentage
		int updatedSleepingPercentage = calculateSleepingPercentage(server.getOverworld());

		// Update the boss bar progress
		bossBar.setPercent(updatedSleepingPercentage / 100.0f);

		// Update the boss bar title to include the percentage
		bossBar.setName(Text.literal("Skipping Night Progress: " + updatedSleepingPercentage + "%")
				.styled(style -> style.withColor(Formatting.BLUE)));

		// Check if the required percentage is reached
		int requiredPercentage = server.getGameRules().getInt(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
		if (updatedSleepingPercentage >= requiredPercentage) {
			endPoll(server); // End the poll if the threshold is met
		}
	}

	private void handlePollOutcome(MinecraftServer server) {
		ServerWorld overworld = server.getOverworld();
		int requiredPercentage = server.getGameRules().getInt(GameRules.PLAYERS_SLEEPING_PERCENTAGE);

		// Count physically sleeping players
		long sleepingPlayers = overworld.getPlayers().stream()
				.filter(ServerPlayerEntity::isSleeping)
				.count();

		// Calculate the percentage of physically sleeping players
		int sleepingPercentage = (int) ((sleepingPlayers * 100) / overworld.getPlayers().size());

		// Check if physically sleeping players alone meet the required percentage
		if (sleepingPercentage >= requiredPercentage) {
			graduallySkipNight(server);
			MessageHandler.broadcastSleepingThresholdReached(server); // Message for physical sleepers
			return;
		}

		// Check combined percentage of sleeping players and votes
		int combinedPercentage = calculateSleepingPercentage(overworld);
		if (combinedPercentage >= requiredPercentage) {
			graduallySkipNight(server);
			MessageHandler.broadcastNightSkipped(server); // Message for combined sleepers and voters
		} else {
			MessageHandler.broadcastNightNotSkipped(server); // Message for not meeting the requirement
		}
	}

	private void graduallySkipNight(MinecraftServer server) {
		ServerWorld overworld = server.getOverworld();
		final long[] currentTime = {overworld.getTimeOfDay()};
		long targetTime = 24000; // Morning time (adjust as needed)

		// Schedule a task to gradually advance the time
		gradualTask = scheduler.scheduleAtFixedRate(() -> {
			server.execute(() -> {
				if (currentTime[0] >= targetTime) {
					gradualTask.cancel(false);
					return;
				}

				currentTime[0] += 500; // Increment time in small steps (adjust for slower/faster skipping)
				overworld.setTimeOfDay(currentTime[0]);
			});
		}, 0, 200, TimeUnit.MILLISECONDS); // Adjust delay for smoother or slower transitions
	}
	private ServerBossBar bossBar = new ServerBossBar(
			Text.literal("Skipping Night Progress"), // Title
			BossBar.Color.BLUE,                      // Color
			BossBar.Style.PROGRESS                   // Style
	);
}