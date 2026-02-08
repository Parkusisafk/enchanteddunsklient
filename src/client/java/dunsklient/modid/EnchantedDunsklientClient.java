package dunsklient.modid;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import java.util.concurrent.atomic.AtomicInteger;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.Formatting;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;

import java.util.Comparator;
import java.util.Optional;

public class EnchantedDunsklientClient implements ClientModInitializer {

	// --- State Variables ---
	private boolean isActive = false;
	private EntityType<?> targetType = null;
	private LivingEntity currentTarget = null;
	private BotState currentState = BotState.IDLE;

	private boolean dropEnabled = false;
	private long lastDropTime = 0;
	private static final long DROP_INTERVAL_MS = 215000; // 215 seconds in milliseconds
	private int tickelapsed_kill = 0;

	private boolean ggCounterEnabled = false;
	private final AtomicInteger ggCount = new AtomicInteger(0);
	private static final int GG_THRESHOLD = 10;
	private long lastGGSentTime = 0;
	private static final long GG_COOLDOWN_MS = 60000;

	// NEW: For the 10-second reset
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
	private ScheduledFuture<?> resetTask = null;

	// Pattern that ONLY looks for "GG" and ignores everything else (including symbols)
	private static final Pattern GG_STRICT_PATTERN = Pattern.compile("GG", Pattern.CASE_INSENSITIVE);
	private enum BotState {
		IDLE,
		SCANNING,
		ROTATING,
		MOVING,
		ATTACKING,
		WAITING_FOR_DEATH
	}

	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			// --- The /auto command ---
			dispatcher.register(ClientCommandManager.literal("auto")
					.then(ClientCommandManager.argument("mobtype", IdentifierArgumentType.identifier()) // Use the built-in type!
							.suggests((context, builder) -> CommandSource.suggestIdentifiers(Registries.ENTITY_TYPE.getIds(), builder))
							.executes(context -> {
								// Use getIdentifier instead of getString
								Identifier id = IdentifierArgumentType.getIdentifier((com.mojang.brigadier.context.CommandContext) context, "mobtype");								startAutoBot(id, context.getSource().getClient());
								return 1;
							}))
			);

			// --- The /autostop command ---
			dispatcher.register(ClientCommandManager.literal("autostop")
					.executes(context -> {
						stopAutoBot(context.getSource().getClient());
						return 1;
					})
			);

			dispatcher.register(ClientCommandManager.literal("luckyblockability")
					.executes(context -> {
						this.dropEnabled = true;
						this.lastDropTime = 0; //i want it to drop immediately
						context.getSource().getClient().player.sendMessage(Text.of("§6Lucky Block Ability enabled! Dropping slot 1 every 215s."), false);
						return 1;
					})
			);

			dispatcher.register(ClientCommandManager.literal("ggtoggle")
					.executes(context -> {
						this.ggCounterEnabled = !this.ggCounterEnabled;
						this.ggCount.set(0); // Reset count on toggle
						String status = ggCounterEnabled ? "§aEnabled" : "§cDisabled";
						context.getSource().getClient().player.sendMessage(Text.of("§dGG Auto-Responder: " + status), false);
						return 1;
					})
			);

			dispatcher.register(ClientCommandManager.literal("everything")
					.executes(context -> {
						this.ggCounterEnabled = !this.ggCounterEnabled;
						this.ggCount.set(0); // Reset count on toggle
						String status = ggCounterEnabled ? "§aEnabled" : "§cDisabled";
						context.getSource().getClient().player.sendMessage(Text.of("§dGG Auto-Responder: " + status), false);
						this.dropEnabled = true;
						this.lastDropTime = 0; //i want it to drop immediately
						context.getSource().getClient().player.sendMessage(Text.of("§6Lucky Block Ability enabled! Dropping slot 1 every 215s."), false);

						return 1;
					})
			);
		});

		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
			if (!ggCounterEnabled) return;

			// 1. Strip ALL color codes and formatting (fixes the "symbols" fear)
			String cleanContent = Formatting.strip(message.getString());

			// 2. Look for GG
			if (cleanContent.toLowerCase().contains("gg")) {
				int current = ggCount.incrementAndGet();

				// 3. Handle the 10-second reset timer
				if (resetTask != null && !resetTask.isDone()) {
					resetTask.cancel(false); // Cancel previous timer if another GG arrived
				}
				resetTask = scheduler.schedule(() -> {
					if (ggCount.get() > 0) {
						ggCount.set(0);
						MinecraftClient.getInstance().execute(() ->
								MinecraftClient.getInstance().player.sendMessage(Text.of("§8[Bot] GG count reset (10s idle)."), true)
						);
					}
				}, 10, TimeUnit.SECONDS);

				// 4. Send GG logic
				MinecraftClient.getInstance().player.sendMessage(Text.of("§d[GG Counter] " + current + "/" + GG_THRESHOLD), true);

				if (current >= GG_THRESHOLD) {
					long currentTime = System.currentTimeMillis();
					if (currentTime - lastGGSentTime >= GG_COOLDOWN_MS) {
						MinecraftClient.getInstance().player.networkHandler.sendChatMessage("GG");
						lastGGSentTime = currentTime;
						ggCount.set(0);
						if (resetTask != null) resetTask.cancel(false);
					}
				}
			}
		});


		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void stopAutoBot(MinecraftClient client) {
		this.isActive = false;
		this.currentTarget = null;
		this.currentState = BotState.IDLE;
		this.dropEnabled = false;
		this.ggCounterEnabled = false;
		this.ggCount.set(0);
		// Release the keys so you stop moving immediately
		if (client.player != null) {
			client.options.forwardKey.setPressed(false);
			client.player.sendMessage(Text.of("§cAuto-Bot deactivated."), false);
		}
	}

	private void startAutoBot(Identifier id, MinecraftClient client) {
		if (Registries.ENTITY_TYPE.containsId(id)) {
			this.targetType = Registries.ENTITY_TYPE.get(id);
			this.isActive = true;
			this.currentState = BotState.SCANNING;
			client.player.sendMessage(Text.of("§aAuto-Bot activated for: " + id), false);
		} else {
			client.player.sendMessage(Text.of("§cInvalid mob type: " + id), false);
			stopAutoBot(client);
		}
	}

	private void onTick(MinecraftClient client) {
		if (dropEnabled && client.player != null) {
			long currentTime = System.currentTimeMillis();
			if (currentTime - lastDropTime >= DROP_INTERVAL_MS) {
				// Drop the item in Slot 1 (index 0)
				client.player.getInventory().selectedSlot = 0;
				client.player.dropSelectedItem(false); // true = drops the whole stack

				lastDropTime = currentTime;
				client.player.sendMessage(Text.of("§6[LuckyBlock] Item dropped! Next drop in 215s."), true);
			}
		}

		if (!isActive || client.player == null || client.world == null) return;

		switch (currentState) {
			case SCANNING:
				scanForTarget(client);
				break;
			case ROTATING:
				faceTarget(client);
				break;
			case MOVING:
				moveToTarget(client);
				break;
			case ATTACKING:
				performAttack(client);
				break;
			case WAITING_FOR_DEATH:
				checkTargetStatus(client);
				break;
			case IDLE:
				break;
		}
	}

	// --- Logic Implementation ---

	private void scanForTarget(MinecraftClient client) {
		Box searchBox = client.player.getBoundingBox().expand(30);

		// Find closest entity of the specific type
		Optional<LivingEntity> closest = client.world.getEntitiesByClass(LivingEntity.class, searchBox,
						entity -> entity.getType() == this.targetType && entity.isAlive() && entity != client.player)
				.stream()
				.min(Comparator.comparingDouble(e -> client.player.distanceTo(e)));

		if (closest.isPresent()) {
			this.currentTarget = closest.get();
			this.currentState = BotState.ROTATING;
			client.player.sendMessage(Text.of("§7Target found. Engaging."), true);
		} else {
			client.player.sendMessage(Text.of("§eNo mob found within 30 blocks."), false);
			this.isActive = false; // Stop the bot
			this.currentState = BotState.IDLE;
		}
	}

	private void faceTarget(MinecraftClient client) {
		if (currentTarget == null || !currentTarget.isAlive()) {
			currentState = BotState.SCANNING;
			return;
		}

		// Calculate angles
		Vec3d targetPos = currentTarget.getPos().add(0, currentTarget.getHeight() / 2, 0);
		Vec3d playerPos = client.player.getEyePos();
		Vec3d diff = targetPos.subtract(playerPos);

		double requiredYaw = Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90;
		double requiredPitch = Math.toDegrees(-Math.atan2(diff.y, Math.sqrt(diff.x * diff.x + diff.z * diff.z)));

		// Smooth rotation logic (Simple Linear Interpolation)
		float speed = 15.0f; // Rotation speed
		client.player.setYaw(updateAngle(client.player.getYaw(), (float) requiredYaw, speed));
		client.player.setPitch(updateAngle(client.player.getPitch(), (float) requiredPitch, speed));

		// Check if we are looking roughly at the target (within 5 degrees)
		float yawDiff = MathHelper.abs(MathHelper.wrapDegrees(client.player.getYaw() - (float)requiredYaw));
		if (yawDiff < 5.0f) {
			currentState = BotState.MOVING;
		}
	}

	private void moveToTarget(MinecraftClient client) {
		if (currentTarget == null || !currentTarget.isAlive()) {
			client.options.forwardKey.setPressed(false);
			currentState = BotState.SCANNING;
			return;
		}

		// Keep facing target while moving
		faceTarget(client);

		double distance = client.player.distanceTo(currentTarget);
		if (distance > 2.5) { // Stop slightly before 1 block to account for reach/lag
			client.options.forwardKey.setPressed(true);
			if (client.player.horizontalCollision && client.player.isOnGround()) {
				client.player.jump(); // Auto jump if stuck
			}
		} else {
			client.options.forwardKey.setPressed(false);
			currentState = BotState.ATTACKING;
		}
	}

	private void performAttack(MinecraftClient client) {
		// Select Slot 1 (Index 0)
		client.player.getInventory().selectedSlot = 0;

		// Attack
		client.interactionManager.attackEntity(client.player, currentTarget);
		client.player.swingHand(client.player.getActiveHand());

		currentState = BotState.WAITING_FOR_DEATH;
	}

	private void checkTargetStatus(MinecraftClient client) {
		// If target is dead or removed, scan again
		tickelapsed_kill++;
		if (currentTarget == null || !currentTarget.isAlive() || currentTarget.isRemoved() || tickelapsed_kill >= 6000) {
			tickelapsed_kill = 0;
			currentState = BotState.SCANNING;
		}
		// Note: If you want to attack AGAIN because it didn't die in one hit,
		// you would check `if (currentTarget.isAlive())` here and switch back to ATTACKING
		// possibly with a cooldown check.
		// Currently this waits until it dies (manual kill or bleed out) or despawns.
	}

	// Helper for smooth rotation wrapping
	private float updateAngle(float oldAngle, float newAngle, float limit) {
		float f = MathHelper.wrapDegrees(newAngle - oldAngle);
		if (f > limit) f = limit;
		if (f < -limit) f = -limit;
		return oldAngle + f;
	}
}