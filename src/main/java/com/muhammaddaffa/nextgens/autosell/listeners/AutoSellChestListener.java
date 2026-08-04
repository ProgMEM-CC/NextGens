package com.muhammaddaffa.nextgens.autosell.listeners;

import com.muhammaddaffa.mdlib.xseries.XSound;
import com.muhammaddaffa.nextgens.NextGens;
import com.muhammaddaffa.nextgens.autosell.AutoSellChestManager;
import com.muhammaddaffa.nextgens.autosell.models.AutoSellChest;
import com.muhammaddaffa.nextgens.gui.AutoSellInventory;
import com.muhammaddaffa.nextgens.utils.Settings;
import com.muhammaddaffa.nextgens.utils.Utils;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

public record AutoSellChestListener(
        AutoSellChestManager manager
) implements Listener {

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    private void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();
        ItemStack stack = event.getItemInHand();

        // prevent placing a normal chest next to an autosell chest
        if (!this.manager.isAutoSellItem(stack)) {
            if ((block.getType() == Material.CHEST || block.getType() == Material.TRAPPED_CHEST)
                    && this.hasAdjacentAutoSellChest(block)) {
                event.setCancelled(true);
                NextGens.DEFAULT_CONFIG.sendMessage(player, "messages.autosell-adjacent");
                Utils.bassSound(player);
            }
            return;
        }

        if (!this.manager.isEnabled()) {
            event.setCancelled(true);
            return;
        }

        // check the blacklisted worlds
        if (Settings.BLACKLISTED_WORLDS.contains(block.getWorld().getName())) {
            event.setCancelled(true);
            NextGens.DEFAULT_CONFIG.sendMessage(player, "messages.autosell-invalid-world");
            Utils.bassSound(player);
            return;
        }

        String id = stack.getItemMeta().getPersistentDataContainer().get(NextGens.autosell_id, PersistentDataType.STRING);
        boolean barrel = "barrel".equalsIgnoreCase(id);

        // validate the placed block
        if (barrel) {
            if (block.getType() != Material.BARREL) {
                event.setCancelled(true);
                return;
            }
        } else {
            if (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST) {
                event.setCancelled(true);
                return;
            }
            // prevent the chest from merging into a double chest
            if (this.hasAdjacentChest(block)) {
                event.setCancelled(true);
                NextGens.DEFAULT_CONFIG.sendMessage(player, "messages.autosell-adjacent");
                Utils.bassSound(player);
                return;
            }
        }

        // check the max per player
        int max = this.manager.getMaxPerPlayer(barrel);
        if (max > 0 && this.manager.getCount(player.getUniqueId(), barrel ? AutoSellChest.Type.BARREL : AutoSellChest.Type.CHEST) >= max) {
            event.setCancelled(true);
            NextGens.DEFAULT_CONFIG.sendMessage(player, barrel ? "messages.sellbarrel-max" : "messages.autosell-max");
            Utils.bassSound(player);
            return;
        }

        // read the data from the item
        String tier = null;
        int usesLeft = -1;
        if (barrel) {
            tier = this.manager.getTierFromItem(stack);
            if (tier == null || !NextGens.AUTOSELL_CONFIG.getConfig().isConfigurationSection("sell-barrel.tiers." + tier)) {
                event.setCancelled(true);
                NextGens.DEFAULT_CONFIG.sendMessage(player, "messages.sellbarrel-invalid-tier");
                Utils.bassSound(player);
                return;
            }
            Integer uses = this.manager.getUsesFromItem(stack);
            usesLeft = uses != null ? uses : this.manager.getDefaultBarrelUses(tier);
        }

        double amount = this.manager.getAmountFromItem(stack);

        // register the container
        AutoSellChest chest = new AutoSellChest(player.getUniqueId(), block.getLocation(),
                barrel ? AutoSellChest.Type.BARREL : AutoSellChest.Type.CHEST, tier, usesLeft);
        chest.setStoredAmount(amount);
        this.manager.register(chest);

        // send the message
        NextGens.DEFAULT_CONFIG.sendMessage(player, barrel ? "messages.sellbarrel-place" : "messages.autosell-place");
        player.playSound(player.getLocation(), XSound.UI_BUTTON_CLICK.get(), 1.0f, 1.0f);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    private void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;

        AutoSellChest chest = this.manager.getByBlock(block);
        if (chest == null) return;

        Player player = event.getPlayer();
        if (!this.manager.isEnabled()) return;

        // always cancel so the vanilla container never opens
        event.setCancelled(true);

        // check the access, only the owner and trusted members can open the gui
        if (!this.manager.hasAccess(player, chest)) {
            NextGens.DEFAULT_CONFIG.sendMessage(player, "messages.autosell-no-access");
            Utils.bassSound(player);
            return;
        }

        // open the gui
        AutoSellInventory.openInventory(player, chest, this.manager);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    private void onBreak(BlockBreakEvent event) {
        AutoSellChest chest = this.manager.getByBlock(event.getBlock());
        if (chest == null) return;

        Player player = event.getPlayer();
        // if the system is disabled, allow the break and clean up the data
        if (!this.manager.isEnabled()) {
            this.manager.unregister(chest.getLocation());
            return;
        }

        // the autosell chest cannot be broken manually, it can only be
        // picked up through the gui
        event.setCancelled(true);
        NextGens.DEFAULT_CONFIG.sendMessage(player, "messages.autosell-cant-break");
        Utils.bassSound(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    private void onExplode(EntityExplodeEvent event) {
        if (!this.manager.isEnabled()) return;
        event.blockList().removeIf(block -> this.manager.getByBlock(block) != null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    private void onExplode(BlockExplodeEvent event) {
        if (!this.manager.isEnabled()) return;
        event.blockList().removeIf(block -> this.manager.getByBlock(block) != null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPistonExtend(BlockPistonExtendEvent event) {
        this.checkPiston(event, event.getBlocks());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPistonRetract(BlockPistonRetractEvent event) {
        this.checkPiston(event, event.getBlocks());
    }

    private void checkPiston(BlockPistonEvent event, List<Block> blocks) {
        if (!this.manager.isEnabled()) return;
        for (Block block : blocks) {
            if (this.manager.getByBlock(block) != null) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    private void onWaterFlow(BlockFromToEvent event) {
        if (!this.manager.isEnabled()) return;
        if (this.manager.getByBlock(event.getToBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    private void onPhysics(BlockPhysicsEvent event) {
        if (!this.manager.isEnabled()) return;
        if (this.manager.getByBlock(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    private void onInventoryMove(InventoryMoveItemEvent event) {
        if (!this.manager.isEnabled()) return;
        if (this.isAutoSellHolder(event.getSource().getHolder())
                || this.isAutoSellHolder(event.getDestination().getHolder())) {
            event.setCancelled(true);
        }
    }

    private boolean isAutoSellHolder(InventoryHolder holder) {
        if (holder instanceof BlockState state) {
            return this.manager.getByBlock(state.getBlock()) != null;
        }
        return false;
    }

    private boolean hasAdjacentChest(Block block) {
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            Block relative = block.getRelative(face);
            if (relative.getType() == Material.CHEST || relative.getType() == Material.TRAPPED_CHEST) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAdjacentAutoSellChest(Block block) {
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            if (this.manager.getByBlock(block.getRelative(face)) != null) {
                return true;
            }
        }
        return false;
    }

}
