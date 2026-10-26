package de.enderchestquick;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.LockableContainerBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Reine Client-Mod: Taste I (anpassbar) -> Item unter dem Mauszeiger bzw. in der Hand
 * wird ueber eine Ender Chest in Reichweite eingelagert. Auf dem Server wird nichts
 * Besonderes benoetigt, es werden nur ganz normale Vanilla-Aktionen ausgefuehrt
 * (Ender Chest oeffnen, Shift-Klick, schliessen).
 */
public class EnderChestQuickClient implements ClientModInitializer {
	public static final String MOD_ID = "enderchestquick";

	private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
	private static KeyBinding storeKey;

	private enum Stage { IDLE, OPEN, WAIT_SCREEN, RESTORE }

	private static Stage stage = Stage.IDLE;
	private static boolean wasDown = false;
	private static boolean internalUse = false;
	private static int delay = 0;
	private static int timeout = 0;

	private static BlockHitResult lastUseHit = null;

	private static final List<Integer> pending = new ArrayList<>();
	private static BlockPos chestPos = null;
	private static boolean restoreInventory = false;
	private static BlockHitResult restoreHit = null;

	@Override
	public void onInitializeClient() {
		storeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.enderchestquick.store",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_I,
				CATEGORY
		));

		// Merkt sich den zuletzt benutzten Block (z. B. Kiste), damit sie nach dem Transfer wieder geoeffnet werden kann.
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (world.isClient() && !internalUse && hand == Hand.MAIN_HAND) {
				lastUseHit = hitResult;
			}
			return ActionResult.PASS;
		});

		ClientTickEvents.END_CLIENT_TICK.register(EnderChestQuickClient::tick);
	}

	// ------------------------------------------------------------------ Tick

	private static void tick(MinecraftClient mc) {
		ClientPlayerEntity player = mc.player;
		if (player == null || mc.world == null || mc.interactionManager == null) {
			reset();
			wasDown = false;
			return;
		}

		while (storeKey.wasPressed()) {
			// leeren, damit nichts "nachklappert"
		}

		boolean down = isDown(mc);
		boolean pressed = down && !wasDown;
		wasDown = down;

		switch (stage) {
			case IDLE -> {
				if (pressed) {
					start(mc, player);
				}
			}
			case OPEN -> {
				if (delay > 0) {
					delay--;
					return;
				}
				openChest(mc, player);
			}
			case WAIT_SCREEN -> waitForChest(mc, player);
			case RESTORE -> {
				if (delay > 0) {
					delay--;
					return;
				}
				restore(mc, player);
			}
		}
	}

	private static void reset() {
		stage = Stage.IDLE;
		pending.clear();
		chestPos = null;
		restoreInventory = false;
		restoreHit = null;
		delay = 0;
		timeout = 0;
	}

	/** Pollt die (frei belegbare) Taste direkt, damit sie auch bei offenem Inventar funktioniert. */
	private static boolean isDown(MinecraftClient mc) {
		InputUtil.Key bound = KeyBindingHelper.getBoundKeyOf(storeKey);
		if (bound == null || bound.getCode() == GLFW.GLFW_KEY_UNKNOWN) {
			return false;
		}
		Screen screen = mc.currentScreen;
		if (screen != null && screen.getFocused() instanceof TextFieldWidget) {
			return false; // Spieler tippt gerade Text
		}
		long handle = mc.getWindow().getHandle();
		if (bound.getCategory() == InputUtil.Type.MOUSE) {
			return GLFW.glfwGetMouseButton(handle, bound.getCode()) == GLFW.GLFW_PRESS;
		}
		if (bound.getCategory() == InputUtil.Type.KEYSYM) {
			return GLFW.glfwGetKey(handle, bound.getCode()) == GLFW.GLFW_PRESS;
		}
		return false;
	}

	// ------------------------------------------------------------------ Start

	private static void start(MinecraftClient mc, ClientPlayerEntity player) {
		Screen screen = mc.currentScreen;
		if (screen != null && !(screen instanceof HandledScreen<?>)) {
			return;
		}
		if (screen instanceof CreativeInventoryScreen) {
			return;
		}
		if (screen != null && isEnderChestScreen(screen)) {
			msg(player, "message.enderchestquick.already");
			return;
		}

		BlockPos chest = findEnderChest(mc, player);
		// Schon vor der Quelle pruefen, damit nichts halb verschoben wird.
		boolean hasSource = false;

		pending.clear();
		restoreInventory = false;
		restoreHit = null;

		if (screen == null) {
			// ----- Item in der Hand
			if (player.getMainHandStack().isEmpty()) {
				msg(player, "message.enderchestquick.nothing");
				return;
			}
			if (player.isSneaking()) {
				msg(player, "message.enderchestquick.sneaking");
				return;
			}
			if (chest == null) {
				msg(player, "message.enderchestquick.nochest");
				return;
			}
			pending.add(player.getInventory().getSelectedSlot());
			hasSource = true;
		} else {
			// ----- Item unter dem Mauszeiger
			HandledScreen<?> hs = (HandledScreen<?>) screen;
			Slot slot = hs.focusedSlot;
			if (slot == null || !slot.hasStack()) {
				msg(player, "message.enderchestquick.nothing");
				return;
			}
			if (player.isSneaking()) {
				msg(player, "message.enderchestquick.sneaking");
				return;
			}
			if (chest == null) {
				msg(player, "message.enderchestquick.nochest");
				return;
			}
			ScreenHandler handler = hs.getScreenHandler();

			if (slot.inventory == player.getInventory()) {
				int index = slot.getIndex();
				if (index < 0 || index > 35) {
					msg(player, "message.enderchestquick.unsupported");
					return;
				}
				pending.add(index);
				hasSource = true;
			} else {
				// Item liegt in einer Kiste o. ae.: erst per Shift-Klick ins Inventar holen.
				ItemStack[] before = new ItemStack[36];
				for (int i = 0; i < 36; i++) {
					before[i] = player.getInventory().getStack(i).copy();
				}
				mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, player);
				for (int i = 0; i < 36; i++) {
					ItemStack after = player.getInventory().getStack(i);
					if (after.isEmpty()) {
						continue;
					}
					boolean changed = before[i].isEmpty()
							|| !ItemStack.areItemsAndComponentsEqual(before[i], after)
							|| after.getCount() > before[i].getCount();
					if (changed) {
						pending.add(i);
					}
				}
				hasSource = !pending.isEmpty();
				if (!hasSource) {
					msg(player, "message.enderchestquick.inventory_full");
					return;
				}
			}

			// Merken, was nach dem Transfer wieder geoeffnet werden soll.
			if (screen instanceof InventoryScreen) {
				restoreInventory = true;
			} else if (lastUseHit != null
					&& mc.world.getBlockEntity(lastUseHit.getBlockPos()) instanceof LockableContainerBlockEntity) {
				restoreHit = lastUseHit;
			}
			player.closeHandledScreen();
			delay = 2;
		}

		if (!hasSource) {
			return;
		}
		chestPos = chest;
		stage = Stage.OPEN;
		if (screen == null) {
			delay = 0;
		}
	}

	// ------------------------------------------------------------------ Ender Chest suchen

	private static BlockPos findEnderChest(MinecraftClient mc, ClientPlayerEntity player) {
		World world = mc.world;
		BlockPos origin = player.getBlockPos();
		int r = (int) Math.ceil(player.getBlockInteractionRange()) + 1;
		Vec3d eye = player.getEyePos();

		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;

		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					BlockPos pos = origin.add(dx, dy, dz);
					if (!world.getBlockState(pos).isOf(Blocks.ENDER_CHEST)) {
						continue;
					}
					if (!player.canInteractWithBlockAt(pos, 0.0)) {
						continue;
					}
					// Wie im Spiel: ein festes Vollblock darueber verhindert das Oeffnen.
					BlockPos above = pos.up();
					if (world.getBlockState(above).isSolidBlock(world, above)) {
						continue;
					}
					double d = eye.squaredDistanceTo(Vec3d.ofCenter(pos));
					if (d < bestDist) {
						bestDist = d;
						best = pos.toImmutable();
					}
				}
			}
		}
		return best;
	}

	// ------------------------------------------------------------------ Ablauf

	private static void openChest(MinecraftClient mc, ClientPlayerEntity player) {
		if (chestPos == null || player.isSneaking()
				|| !mc.world.getBlockState(chestPos).isOf(Blocks.ENDER_CHEST)
				|| !player.canInteractWithBlockAt(chestPos, 0.0)) {
			msg(player, "message.enderchestquick.nochest");
			reset();
			return;
		}
		BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(chestPos), Direction.UP, chestPos, false);
		internalUse = true;
		try {
			mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
		} finally {
			internalUse = false;
		}
		stage = Stage.WAIT_SCREEN;
		timeout = 30;
	}

	private static void waitForChest(MinecraftClient mc, ClientPlayerEntity player) {
		Screen screen = mc.currentScreen;
		if (screen instanceof GenericContainerScreen gcs && isEnderChestScreen(screen)) {
			transfer(mc, player, gcs.getScreenHandler());
			player.closeHandledScreen();
			pending.clear();
			if (restoreInventory || restoreHit != null) {
				stage = Stage.RESTORE;
				delay = 2;
			} else {
				reset();
			}
			return;
		}
		if (--timeout <= 0) {
			msg(player, "message.enderchestquick.failed");
			reset();
		}
	}

	private static void transfer(MinecraftClient mc, ClientPlayerEntity player, GenericContainerScreenHandler handler) {
		int total = 0;
		int moved = 0;
		Text name = null;

		for (int index : pending) {
			for (Slot slot : handler.slots) {
				if (slot.inventory == player.getInventory() && slot.getIndex() == index && slot.hasStack()) {
					ItemStack stack = slot.getStack();
					if (name == null) {
						name = stack.getName();
					}
					int before = stack.getCount();
					mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, player);
					int after = slot.hasStack() ? slot.getStack().getCount() : 0;
					total += before;
					moved += Math.max(0, before - after);
					break;
				}
			}
		}

		if (total == 0 || name == null) {
			msg(player, "message.enderchestquick.nothing");
		} else if (moved >= total) {
			player.sendMessage(Text.translatable("message.enderchestquick.moved", name, moved), true);
		} else {
			player.sendMessage(Text.translatable("message.enderchestquick.partial", moved, total), true);
		}
	}

	private static void restore(MinecraftClient mc, ClientPlayerEntity player) {
		if (restoreInventory) {
			mc.setScreen(new InventoryScreen(player));
		} else if (restoreHit != null && player.canInteractWithBlockAt(restoreHit.getBlockPos(), 0.0)) {
			internalUse = true;
			try {
				mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, restoreHit);
			} finally {
				internalUse = false;
			}
		}
		reset();
	}

	// ------------------------------------------------------------------ Helfer

	private static boolean isEnderChestScreen(Screen screen) {
		return screen instanceof GenericContainerScreen gcs
				&& gcs.getScreenHandler().getRows() == 3
				&& gcs.getTitle().getString().equals(Text.translatable("container.enderchest").getString());
	}

	private static void msg(ClientPlayerEntity player, String key) {
		player.sendMessage(Text.translatable(key), true);
	}
}
