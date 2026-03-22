package dunsklient.modid;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class EnchantedDunsklientClient implements ClientModInitializer {

	// --- Core State Variables ---
	private boolean isActive = false;
	private Block targetBlockType = null;
	private BotState currentState = BotState.IDLE;

	// --- Block Breaking Logic Variables ---
	private BlockPos currentTargetBlockPos = null;
	private int failedBlockCount = 0;
	private int turnCount = 0;
	private float targetTurnYaw = 0f;
	private long breakStartTime = 0;
	private float baseTravelYaw = 0f; // Stores the parallel direction we are traveling
	private double lastDistanceSq = Double.MAX_VALUE;

	// --- Coordinate / Anti-TP Safety ---
	private Vec3d lastCheckedPos = null;
	private long lastPosCheckTime = 0;
	// Add these fields at the top
	private float currentAimYaw = 0f;
	private float currentAimPitch = 0f;
	private static final float AIM_SPEED = 0.25f; // 0.0-1.0, lower = slower/smoother

	// --- GG & Chat Variables ---
	private boolean ggCounterEnabled = false;
	private final AtomicInteger ggCount = new AtomicInteger(0);
	private static final int GG_THRESHOLD = 10;
	private long lastGGSentTime = 0;
	private static final long GG_COOLDOWN_MS = 60000;
	private boolean nightModeEnabled = false;
	private boolean breakInitiated = false;



	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
	private ScheduledFuture<?> resetTask = null;
	private static final Pattern GG_STRICT_PATTERN = Pattern.compile("GG", Pattern.CASE_INSENSITIVE);

	private enum BotState {
		IDLE,
		SEARCHING_BLOCK,
		TURNING_90,
		ALIGNING_CAMERA,
		BREAKING_BLOCK,
		MOVING_FORWARD,
		STARTING_BREAK
	}

	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {

			// --- Commands ---
			dispatcher.register(ClientCommandManager.literal("auto")
					.then(ClientCommandManager.argument("blockid", IdentifierArgumentType.identifier())
							.suggests((context, builder) -> CommandSource.suggestIdentifiers(Registries.BLOCK.getIds(), builder))
							.executes(context -> {
								Identifier id = IdentifierArgumentType.getIdentifier((com.mojang.brigadier.context.CommandContext) context, "blockid");
								startAutoBot(id, context.getSource().getClient());

								this.ggCounterEnabled = !this.ggCounterEnabled;
								this.ggCount.set(0);
								String status = ggCounterEnabled ? "§aEnabled" : "§cDisabled";
								context.getSource().getClient().player.sendMessage(Text.of("§dGG Auto-Responder: " + status), false);
								return 1;
							}))
			);

			dispatcher.register(ClientCommandManager.literal("autostop")
					.executes(context -> {
						stopAutoBot(context.getSource().getClient(), "Manual stop.");
						return 1;
					})
			);

			dispatcher.register(ClientCommandManager.literal("ggtoggle")
					.executes(context -> {
						this.ggCounterEnabled = !this.ggCounterEnabled;
						this.ggCount.set(0);
						String status = ggCounterEnabled ? "§aEnabled" : "§cDisabled";
						context.getSource().getClient().player.sendMessage(Text.of("§dGG Auto-Responder: " + status), false);
						return 1;
					})
			);

			dispatcher.register(ClientCommandManager.literal("nightmode")
					.executes(context -> {
						this.nightModeEnabled = !this.nightModeEnabled;
						String status = nightModeEnabled ? "§aENABLED (PC will shutdown on Captcha)" : "§cDISABLED";
						context.getSource().getClient().player.sendMessage(Text.of("§9[System] Night Mode: " + status), false);
						return 1;
					})
			);
		});

		// --- Chat Event Listeners ---
		ClientReceiveMessageEvents.GAME.register((message, isOverlay) -> {
			if (isOverlay) return;
			String cleanContent = Formatting.strip(message.getString());
			if (cleanContent == null) return;

			MinecraftClient client = MinecraftClient.getInstance();

			// Staff / Captcha Checks
			String lowerContent = cleanContent.toLowerCase();
			if (lowerContent.contains("enter the captcha") ||
					lowerContent.contains("are you here") ||
					lowerContent.contains("afk check") ||
					lowerContent.contains("macro check")) {

				client.player.sendMessage(Text.of("§c§lCAPTCHA/STAFF DETECTED! §7Disconnecting..."), false);
				stopAutoBot(client, "Captcha/Staff Check");

				scheduler.schedule(() -> {
					client.execute(() -> {
						disconnectFromServer(client, "Logged off automatically because a §eCaptcha/Staff check §fwas detected.\nContent: " + cleanContent);
					});
				}, 1, TimeUnit.SECONDS);
				return;
			}

			// GG Counter Logic
			if (ggCounterEnabled && lowerContent.contains("gg") && !cleanContent.contains("[GG Counter]")) {
				int current = ggCount.incrementAndGet();

				if (resetTask != null && !resetTask.isDone()) resetTask.cancel(false);
				resetTask = scheduler.schedule(() -> {
					if (ggCount.get() > 0) {
						ggCount.set(0);
						client.execute(() -> client.player.sendMessage(Text.of("§8[Bot] GG count reset (10s idle)."), true));
					}
				}, 10, TimeUnit.SECONDS);

				client.player.sendMessage(Text.of("§d[GG Counter] " + current + "/" + GG_THRESHOLD), true);

				if (current >= GG_THRESHOLD) {
					long currentTime = System.currentTimeMillis();
					if (currentTime - lastGGSentTime >= GG_COOLDOWN_MS) {
						client.player.networkHandler.sendChatMessage("GG");
						lastGGSentTime = currentTime;
						ggCount.set(0);
						if (resetTask != null) resetTask.cancel(false);
					}
				}
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	// --- Primary Tick Loop ---
	private void onTick(MinecraftClient client) {
		if (!isActive || client.player == null || client.world == null) return;

		if (currentState != BotState.MOVING_FORWARD) {
			client.options.forwardKey.setPressed(false);
		}
		// Anti-TP Coordinate Check
		long currentTime = System.currentTimeMillis();
		if (currentTime - lastPosCheckTime >= 1000) { // Check every 1 second
			if (lastCheckedPos != null) {
				if (client.player.getPos().distanceTo(lastCheckedPos) > 10.0) {
					disconnectFromServer(client, "Drastic coordinate change detected (>10 blocks/sec).");
					return;
				}
			}
			lastCheckedPos = client.player.getPos();
			lastPosCheckTime = currentTime;
		}

		// State Machine
		switch (currentState) {
			case SEARCHING_BLOCK -> searchForNextBlock(client);
			case TURNING_90 -> performSmoothTurn(client);
			case ALIGNING_CAMERA -> faceBlockSurface(client);
			case BREAKING_BLOCK, STARTING_BREAK -> handleBlockBreaking(client);
            case MOVING_FORWARD -> moveForwardToBlock(client);
			case IDLE -> { /* Do nothing */ }
		}
	}

	// --- Core Methods ---

	private float lerpAngle(float current, float target, float speed) {
		float delta = MathHelper.wrapDegrees(target - current);
		return current + delta * speed;
	}

	private void startAutoBot(Identifier blockId, MinecraftClient client) {
		if (Registries.BLOCK.containsId(blockId)) {
			this.targetBlockType = Registries.BLOCK.get(blockId);
			this.isActive = true;
			this.currentState = BotState.SEARCHING_BLOCK;
			this.turnCount = 0;
			this.failedBlockCount = 0;
			this.lastCheckedPos = client.player.getPos();
			this.baseTravelYaw = snapToNearest90(client.player.getYaw());

			client.player.sendMessage(Text.of("§aAuto-Miner activated for: " + blockId), false);
		} else {
			client.player.sendMessage(Text.of("§cInvalid block type: " + blockId), false);
			stopAutoBot(client, "Invalid Block");
		}
	}

	private void stopAutoBot(MinecraftClient client, String reason) {
		this.isActive = false;
		this.currentState = BotState.IDLE;
		this.ggCounterEnabled = false;
		this.ggCount.set(0);

		if (client.player != null) {
			client.options.forwardKey.setPressed(false);
			if (client.interactionManager != null) {
				client.interactionManager.cancelBlockBreaking();
			}
			client.player.sendMessage(Text.of("§cAuto-Bot deactivated. Reason: " + reason), false);
		}
	}

	// Handles the generic disconnect class/screen + Nightmode logic
	private void disconnectFromServer(MinecraftClient client, String reasonText) {
		stopAutoBot(client, "Disconnecting");

		if (client.world != null) {
			client.world.disconnect();
			if (client.getServer() != null) client.getServer().stop(false);
			client.disconnect();

			if (this.nightModeEnabled) {
				try {
					String shutdownCommand = System.getProperty("os.name").toLowerCase().contains("win")
							? "shutdown /s /f /t 0" : "shutdown -h now";
					Runtime.getRuntime().exec(shutdownCommand);
				} catch (Exception e) {
					e.printStackTrace();
				}
			} else {
				client.setScreen(new DisconnectedScreen(
						new TitleScreen(),
						Text.literal("§c§lBot Protection"),
						Text.literal("§f" + reasonText)
				));
			}
		}
	}

	// --- State Logic Implementations ---

	private void searchForNextBlock(MinecraftClient client) {
		// Find direction we are currently facing snapped to 90 degrees
		Direction facing = Direction.fromHorizontalDegrees(this.baseTravelYaw);

		BlockPos playerBlockUnderFeet = client.player.getBlockPos().down();
		BlockPos checkPos = playerBlockUnderFeet.offset(facing);

		BlockState stateAtPos = client.world.getBlockState(checkPos);
		BlockState stateAbovePos = client.world.getBlockState(checkPos.up());

		// Check if it's our block AND the block above it is air/non-solid (top exposed)
		if (stateAtPos.getBlock() == targetBlockType && !stateAbovePos.isSolidBlock(client.world, checkPos.up())) {
			this.currentTargetBlockPos = checkPos;
			this.turnCount = 0; // Reset turn count
			this.currentState = BotState.ALIGNING_CAMERA;
		} else {
			// Not found, turn 90 degrees right
			this.turnCount++;
			if (this.turnCount >= 4) {
				disconnectFromServer(client, "Block not found in all 4 directions.");
			} else {
				this.targetTurnYaw = client.player.getYaw() + 90f;
				this.currentState = BotState.TURNING_90;
			}
		}
	}

	private void performSmoothTurn(MinecraftClient client) {
		float currentYaw = client.player.getYaw();
		client.player.setYaw(updateAngle(currentYaw, targetTurnYaw, 15.0f));

		if (MathHelper.abs(MathHelper.wrapDegrees(client.player.getYaw() - targetTurnYaw)) < 2.0f) {
			client.player.setYaw(targetTurnYaw); // Snap to perfect value
			this.baseTravelYaw = client.player.getYaw(); // Update base travel yaw
			this.currentState = BotState.SEARCHING_BLOCK;
		}
	}

	private void faceBlockSurface(MinecraftClient client) {
		// Aim for the top-center surface of the target block
		Vec3d targetSurfacePos = currentTargetBlockPos.toCenterPos().add(0, 0.5, 0);
		Vec3d playerEyePos = client.player.getEyePos();
		Vec3d diff = targetSurfacePos.subtract(playerEyePos);

		double requiredYaw = Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90;
		double requiredPitch = Math.toDegrees(-Math.atan2(diff.y, Math.sqrt(diff.x * diff.x + diff.z * diff.z)));

		client.player.setYaw(lerpAngle(client.player.getYaw(), (float) requiredYaw, 0.4f));
		client.player.setPitch(lerpAngle(client.player.getPitch(), (float) requiredPitch, 0.4f));

		float yawDiff = MathHelper.abs(MathHelper.wrapDegrees(client.player.getYaw() - (float) requiredYaw));
		float pitchDiff = MathHelper.abs(MathHelper.wrapDegrees(client.player.getPitch() - (float) requiredPitch));

		if (yawDiff < 3.0f && pitchDiff < 3.0f) {
			this.breakStartTime = System.currentTimeMillis();
			this.currentState = BotState.BREAKING_BLOCK;
			this.breakStartTime = System.currentTimeMillis();
			this.breakInitiated = false;  // reset flag on new block
		}
	}

	// New method — just rotates, no state change, no breakInitiated reset
	private void aimAtBlockSurface(MinecraftClient client) {
		Vec3d targetSurfacePos = currentTargetBlockPos.toCenterPos().add(0, 0.5, 0);
		Vec3d playerEyePos = client.player.getEyePos();
		Vec3d diff = targetSurfacePos.subtract(playerEyePos);

		double requiredYaw = Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90;
		double requiredPitch = Math.toDegrees(-Math.atan2(diff.y,
				Math.sqrt(diff.x * diff.x + diff.z * diff.z)));

		client.player.setYaw(lerpAngle(client.player.getYaw(), (float) requiredYaw, 0.5f));
		client.player.setPitch(lerpAngle(client.player.getPitch(), (float) requiredPitch, 0.5f));

	}

	private void handleBlockBreaking(MinecraftClient client) {
		BlockState currentStateAtPos = client.world.getBlockState(currentTargetBlockPos);

		if (currentStateAtPos.getBlock() != targetBlockType) {
			this.failedBlockCount = 0;
			client.player.setYaw(lerpAngle(client.player.getYaw(), (float) baseTravelYaw, 0.5f));
			this.currentState = BotState.MOVING_FORWARD;
			return;
		}

		if (System.currentTimeMillis() - breakStartTime > 5000) {
			client.interactionManager.cancelBlockBreaking();
			this.failedBlockCount++;
			if (this.failedBlockCount > 3) {
				disconnectFromServer(client, "Failed to break block too many times.");
				return;
			}
			client.player.setYaw(lerpAngle(client.player.getYaw(), (float) baseTravelYaw, 0.5f));
			this.currentState = BotState.MOVING_FORWARD;
			return;
		}

		Direction side = Direction.UP;
		if (client.crosshairTarget instanceof BlockHitResult bhr
				&& bhr.getBlockPos().equals(currentTargetBlockPos)) {
			side = bhr.getSide();
		}

		if (!breakInitiated) {
			//System.out.println("[DEBUG] attackBlock called, breakInitiated was false, tick=" + client.player.age);

			// Manually send START — bypasses the internal cancelBlockBreaking
			// that attackBlock triggers when switching targets
			client.interactionManager.attackBlock(currentTargetBlockPos, side);
			breakInitiated = true;
			client.player.swingHand(Hand.MAIN_HAND);
			return; // wait one tick before sending progress
		}

		client.interactionManager.updateBlockBreakingProgress(currentTargetBlockPos, side);
		client.player.swingHand(Hand.MAIN_HAND);
		// 4. Handle Particle Aiming (Critical Multipliers)
		Vec3d particlePos = findParticleNearBlock(currentTargetBlockPos);
		if (particlePos != null) {
			// Rotate smoothly to particle
			Vec3d playerEyePos = client.player.getEyePos();
			Vec3d diff = particlePos.subtract(playerEyePos);
			double requiredYaw = Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90;
			double requiredPitch = Math.toDegrees(-Math.atan2(diff.y, Math.sqrt(diff.x * diff.x + diff.z * diff.z)));

			client.player.setYaw(lerpAngle(client.player.getYaw(), (float) requiredYaw, 0.4f));
			client.player.setPitch(lerpAngle(client.player.getPitch(), (float) requiredPitch, 0.4f));
		} else {
			// No particle, ensure we remain locked on the block surface
			aimAtBlockSurface(client);
		}
	}
	private void moveForwardToBlock(MinecraftClient client) {
		client.options.forwardKey.setPressed(true);

		Vec3d playerPos = client.player.getPos();
		Vec3d targetCenter = currentTargetBlockPos.toCenterPos();

		// Distance on the X/Z plane
		double distanceSq = Math.pow(playerPos.x - targetCenter.x, 2) + Math.pow(playerPos.z - targetCenter.z, 2);

		// FIX: Stop if we are close enough OR if we started moving AWAY from the target (overshot)
		if (distanceSq < 0.15 || distanceSq > lastDistanceSq) {
			client.options.forwardKey.setPressed(false);
			this.lastDistanceSq = Double.MAX_VALUE; // Reset for next time
			this.currentState = BotState.SEARCHING_BLOCK;
		} else {
			this.lastDistanceSq = distanceSq;
		}
	}

	// --- Utilities ---

	private float updateAngle(float oldAngle, float newAngle, float maxStep) {
		float f = MathHelper.wrapDegrees(newAngle - oldAngle);
		if (f > maxStep) f = maxStep;
		if (f < -maxStep) f = -maxStep;
		return oldAngle + f;
	}

	private float snapToNearest90(float yaw) {
		return Math.round(yaw / 90.0f) * 90.0f;
	}

	/**
	 * Note: Fabric Client API does not store active world particles in a query-friendly way.
	 * To actually get the XYZ of spawned particles, you will need a Mixin into `ParticleManager`
	 * that intercepts `addParticle` and saves the Vec3d of particles matching your target type.
	 * This method is a stub where you'd retrieve that saved Vec3d.
	 */
	private Vec3d findParticleNearBlock(BlockPos pos) {
		Vec3d particle = ParticleTracker.getParticleForBlock(pos);

		if (particle != null) {
			// Only aim if the particle is actually within the block's boundaries
			// (Prevents snapping to random combat particles nearby)
			if (Math.abs(particle.x - (pos.getX() + 0.5)) < 0.7 &&
					Math.abs(particle.z - (pos.getZ() + 0.5)) < 0.7) {
				return particle;
			}
		}
		return null;
	}
}