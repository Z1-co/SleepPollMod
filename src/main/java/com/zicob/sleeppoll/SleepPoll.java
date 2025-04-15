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
			LOGGER.info("Scheduler shut down.");
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
			LOGGER.info("A poll is already active. Ignoring new poll request.");
			return;
		}

		pollInitiator = player.getUuid();

		int requiredPercentage = server.getGameRules().getInt(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
		int currentSleepingPercentage = calculateSleepingPercentage(server.getOverworld());

		LOGGER.info("Current sleeping percentage: " + currentSleepingPercentage + "%");
		LOGGER.info("Required sleeping percentage: " + requiredPercentage + "%");

		if (currentSleepingPercentage >= requiredPercentage) {
			LOGGER.info("Sleeping threshold already met. Skipping the night.");
			server.getOverworld().setTimeOfDay(0);
			server.getPlayerManager().broadcast(Text.literal("The night has been skipped!"), false);
			return;
		}

		pollActive = true;
		votes.clear();
		LOGGER.info("Creating a poll for skipping the night...");

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

		// Add all players to the boss bar
		server.getPlayerManager().getPlayerList().forEach(bossBar::addPlayer);

		// Schedule the task and store the future
		pollTask = scheduler.scheduleAtFixedRate(() -> {
			server.execute(() -> {
				int updatedSleepingPercentage = calculateSleepingPercentage(server.getOverworld());

				// Check if no players are sleeping
				if (updatedSleepingPercentage == 0) {
					LOGGER.info("No players are sleeping. Ending poll.");
					endPoll(server);
					return;
				}

				bossBar.setPercent(updatedSleepingPercentage / 100.0f); // Update boss bar progress
				LOGGER.info("Updated sleeping percentage: " + updatedSleepingPercentage + "%");

				if (updatedSleepingPercentage >= requiredPercentage) {
					LOGGER.info("Sleeping threshold reached during poll. Ending poll and skipping the night.");
					endPoll(server);
					server.getOverworld().setTimeOfDay(0);
					server.getPlayerManager().broadcast(Text.literal("The night has been skipped!"), false);
				}
			});
		}, 0, 1, TimeUnit.SECONDS);
	}

	public void endPoll(MinecraftServer server) {
		if (!pollActive) return; // Prevent duplicate calls to endPoll

		pollActive = false;

		// Cancel the scheduled task
		if (pollTask != null && !pollTask.isCancelled()) {
			pollTask.cancel(false);
			pollTask = null;
		}

		long yesVotes = votes.values().stream().filter(v -> v).count();
		long noVotes = votes.size() - yesVotes;

		if (yesVotes > noVotes) {
			server.getOverworld().setTimeOfDay(0);
			server.getPlayerManager().broadcast(Text.literal("The night has been skipped!"), false);
		} else {
			server.getPlayerManager().broadcast(Text.literal("The night will not be skipped."), false);
		}

		// Remove all players from the boss bar
		bossBar.clearPlayers();
	}

	public void castVote(ServerPlayerEntity player, boolean vote) {
		UUID playerId = player.getUuid();

		if (votes.containsKey(playerId)) {
			player.sendMessage(Text.literal("You have already voted!").styled(style -> style.withColor(0xFF0000)), false);
			return;
		}

		votes.put(playerId, vote);
		LOGGER.info("Player " + player.getName().getString() + " voted " + (vote ? "Yes" : "No"));

		player.sendMessage(Text.literal("Your vote has been recorded: " + (vote ? "Yes" : "No"))
				.styled(style -> style.withColor(0x00FF00)), false);

		// Update boss bar progress based on votes
		long yesVotes = votes.values().stream().filter(v -> v).count();
		long totalPlayers = player.getServer().getPlayerManager().getPlayerList().size();

		float votePercentage = (float) yesVotes / totalPlayers;
		bossBar.setPercent(votePercentage);

		// Check if all online players have voted
		MinecraftServer server = player.getServer();
		if (server != null && votes.size() == totalPlayers) {
			LOGGER.info("All players have voted. Ending poll early.");
			server.execute(() -> endPoll(server)); // Ensure the task runs on the server thread
		}
	}

	private int calculateSleepingPercentage(ServerWorld world) {
		long sleepingPlayers = world.getPlayers().stream()
				.filter(ServerPlayerEntity::isSleeping)
				.count();

		// Exclude the initiator's vote if they are already sleeping
		if (pollInitiator != null) {
			ServerPlayerEntity initiator = (ServerPlayerEntity) world.getPlayerByUuid(pollInitiator);
			if (initiator != null && initiator.isSleeping() && votes.getOrDefault(pollInitiator, false)) {
				sleepingPlayers -= 1; // Avoid double-counting the initiator
			}
		}

		long totalPlayers = world.getPlayers().size();

		if (totalPlayers == 0) {
			return 0; // Avoid division by zero
		}

		return (int) ((sleepingPlayers * 100) / totalPlayers);
	}

	private ServerBossBar bossBar = new ServerBossBar(
			Text.literal("Skipping Night Progress"), // Title
			BossBar.Color.BLUE,                      // Color
			BossBar.Style.PROGRESS                   // Style
	);
}