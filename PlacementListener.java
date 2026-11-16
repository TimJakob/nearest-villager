package dev.nearestvillager;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/** Haengt sich per Fabric-API an "Rechtsklick auf Block". Gibt immer PASS zurueck, ausser beim Highlighten. */
public final class PlacementListener {
	private PlacementListener() {
	}

	public static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}

		ItemStack stack = player.getItemInHand(hand);

		// Shift + Rechtsklick mit leeren Haenden auf eine Workstation -> Besitzer leuchtet
		if (stack.isEmpty()) {
			if (hand == InteractionHand.MAIN_HAND
					&& Config.get().highlightEnabled
					&& player.isShiftKeyDown()
					&& player.getOffhandItem().isEmpty()
					&& JobArbiter.highlight(serverPlayer, serverLevel, hit.getBlockPos())) {
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		}

		// Workstation in der Hand: Zielposition berechnen wie Vanilla und vormerken.
		// Platziert wird danach ganz normal von Vanilla; scheitert das, verfaellt der Eintrag von selbst.
		if (stack.getItem() instanceof BlockItem blockItem
				&& JobArbiter.isJobSite(blockItem.getBlock().defaultBlockState())) {
			BlockPlaceContext context = new BlockPlaceContext(player, hand, stack, hit);
			if (context.canPlace()) {
				JobArbiter.onWorkstationPlaced(serverLevel, context.getClickedPos());
			}
		}

		return InteractionResult.PASS;
	}
}
