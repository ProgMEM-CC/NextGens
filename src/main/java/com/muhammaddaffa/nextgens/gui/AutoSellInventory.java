package com.muhammaddaffa.nextgens.gui;

import com.muhammaddaffa.mdlib.fastinv.FastInv;
import com.muhammaddaffa.mdlib.hooks.VaultEconomy;
import com.muhammaddaffa.mdlib.task.ExecutorManager;
import com.muhammaddaffa.mdlib.task.handleTask.HandleTask;
import com.muhammaddaffa.mdlib.utils.Common;
import com.muhammaddaffa.mdlib.utils.Config;
import com.muhammaddaffa.mdlib.utils.ItemBuilder;
import com.muhammaddaffa.mdlib.utils.Placeholder;
import com.muhammaddaffa.mdlib.xseries.XSound;
import com.muhammaddaffa.nextgens.NextGens;
import com.muhammaddaffa.nextgens.autosell.AutoSellChestManager;
import com.muhammaddaffa.nextgens.autosell.models.AutoSellChest;
import com.muhammaddaffa.nextgens.sell.SellDataCalculator;
import com.muhammaddaffa.nextgens.sellwand.models.SellwandData;
import com.muhammaddaffa.nextgens.users.models.User;
import com.muhammaddaffa.nextgens.utils.Utils;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public class AutoSellInventory extends FastInv {

    public static void openInventory(Player player, AutoSellChest chest, AutoSellChestManager manager) {
        new AutoSellInventory(player, chest, manager).open(player);
    }

    private final Player player;
    private final AutoSellChest chest;
    private final AutoSellChestManager manager;
    private final SellwandData sellwand;

    private HandleTask refreshTask;

    public AutoSellInventory(Player player, AutoSellChest chest, AutoSellChestManager manager) {
        super(guiConfig(chest).getInt("size"), Common.color(guiConfig(chest).getString("title")
                .replace("{type}", chest.isBarrel() ? "Sell Barrel" : "AutoSell Chest")
                .replace("{tier}", chest.getTier() == null ? "-" : chest.getTier())
                .replace("{uses}", Common.digits(chest.getUsesLeft()))));
        this.player = player;
        this.chest = chest;
        this.manager = manager;
        this.sellwand = this.getSellwandInHand(player);

        // cancel all clicks on the gui itself, the item handlers still run
        this.addClickHandler(event -> {
            if (event.getClickedInventory() != null && event.getClickedInventory().equals(this.getInventory())) {
                event.setCancelled(true);
            }
        });

        // refresh the gui every second so the stored money stays up to date
        this.refreshTask = ExecutorManager.getProvider().syncTimer(20L, 20L, () -> {
            // if the container is no longer in the world, close the gui
            if (this.manager.getByLocation(this.chest.getLocation()) == null) {
                this.player.closeInventory();
                return;
            }
            this.setAllItems();
        });
        // stop the task once the inventory is closed
        this.addCloseHandler(event -> {
            if (this.refreshTask != null) {
                this.refreshTask.cancel();
                this.refreshTask = null;
            }
        });

        this.setAllItems();
    }

    /**
     * The sell barrel has its own gui configuration (sell_barrel_gui.yml)
     * while the autosell chest keeps using autosell_gui.yml.
     */
    private static Config guiConfig(AutoSellChest chest) {
        return chest.isBarrel() ? NextGens.SELL_BARREL_GUI_CONFIG : NextGens.AUTOSELL_GUI_CONFIG;
    }

    private SellwandData getSellwandInHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!NextGens.getInstance().getSellwandManager().isSellwand(hand)) {
            return null;
        }
        ItemMeta meta = hand.getItemMeta();
        if (meta == null) return null;
        Double multiplier = meta.getPersistentDataContainer().get(NextGens.sellwand_multiplier, PersistentDataType.DOUBLE);
        return multiplier == null ? null : new SellwandData(hand, multiplier);
    }

    private void setAllItems() {
        FileConfiguration config = guiConfig(this.chest).getConfig();
        // clear the gui first
        this.clearItems();

        // filler items
        ItemBuilder filler = ItemBuilder.fromConfig(config, "filler-item");
        if (filler != null) {
            this.setItems(Utils.convertListToIntArray(config.getIntegerList("filler-slots")), filler.build());
        }
        // claim button
        this.setClaimButton(config);
        // pickup button
        this.setPickupButton(config);
        // stored amount item
        this.setStoredButton(config);
    }

    private void setClaimButton(FileConfiguration config) {
        List<Integer> slots = config.getIntegerList("claim-slots");
        String displayName = config.getString("claim-item.display-name");
        List<String> lore = new ArrayList<>(config.getStringList("claim-item.lore"));

        // if the player is holding a sellwand, insert its multiplier line
        if (this.sellwand != null) {
            List<String> sellwandLore = config.getStringList("claim-item.sellwand-lore");
            int index = -1;
            for (int i = 0; i < lore.size(); i++) {
                if (lore.get(i).contains("{multiplier}")) {
                    index = i;
                    break;
                }
            }
            if (index != -1) {
                lore.addAll(index + 1, sellwandLore);
            } else {
                lore.addAll(sellwandLore);
            }
        }

        double multiplier = this.getMultiplier();
        double claimable = this.getClaimable(multiplier);

        ItemBuilder builder = new ItemBuilder(Material.EMERALD)
                .name(displayName)
                .lore(lore)
                .customModelData(config.getInt("claim-item.custom-model-data"))
                .placeholder(this.createPlaceholder()
                        .add("{claimable}", Utils.formatBalance((long) claimable))
                        .add("{claimable_raw}", Common.digits(claimable))
                        .add("{multiplier}", Common.digits(multiplier))
                        .add("{sellwand_multiplier}", this.sellwand == null ? "0" : Common.digits(this.sellwand.getMultiplier())));

        this.setItems(Utils.convertListToIntArray(slots), builder.build(), event -> {
            // safety check to prevent dupes, the container must still exist
            if (this.manager.getByLocation(this.chest.getLocation()) == null) {
                this.player.closeInventory();
                return;
            }
            double stored = this.chest.getStoredAmount();
            if (stored <= 0) {
                NextGens.DEFAULT_CONFIG.sendMessage(this.player, this.chest.isBarrel() ? "messages.sellbarrel-no-money" : "messages.autosell-no-money");
                Utils.bassSound(this.player);
                return;
            }
            double finalMultiplier = this.getMultiplier();
            double payout = this.getClaimable(finalMultiplier);
            // pay out and reset the stored amount
            this.chest.setStoredAmount(0);
            VaultEconomy.deposit(this.player, payout);
            // consume a sellwand use if enabled
            this.consumeSellwandUse();
            // save the container
            ExecutorManager.getProvider().async(() -> this.manager.saveChest(this.chest));
            // send the message
            NextGens.DEFAULT_CONFIG.sendMessage(this.player, this.chest.isBarrel() ? "messages.sellbarrel-claim" : "messages.autosell-claim", new Placeholder()
                    .add("{amount}", Common.digits(payout))
                    .add("{amount_formatted}", Utils.formatBalance((long) payout))
                    .add("{multiplier}", Common.digits(finalMultiplier)));
            this.player.playSound(this.player.getLocation(), XSound.ENTITY_EXPERIENCE_ORB_PICKUP.get(), 1.0f, 1.0f);
            // refresh the gui
            this.setAllItems();
        });
    }

    private void setPickupButton(FileConfiguration config) {
        List<Integer> slots = config.getIntegerList("pickup-slots");
        ItemBuilder builder = ItemBuilder.fromConfig(config, "pickup-item");
        if (builder == null) {
            return;
        }
        builder.placeholder(this.createPlaceholder());

        this.setItems(Utils.convertListToIntArray(slots), builder.build(), event -> {
            // safety check, the container must still exist
            if (this.manager.getByLocation(this.chest.getLocation()) == null) {
                this.player.closeInventory();
                return;
            }
            // create the item with the stored money and remaining uses
            ItemStack item = this.chest.isBarrel()
                    ? this.manager.createBarrelItem(this.chest.getTier(), this.chest.getUsesLeft(), this.chest.getStoredAmount())
                    : this.manager.createChestItem(this.chest.getStoredAmount());
            if (item == null) return;
            // remove the container from the world
            this.manager.unregister(this.chest.getLocation());
            this.chest.getLocation().getBlock().setType(Material.AIR);
            // give the item back to the player
            Common.addInventoryItem(this.player, item);
            // close the gui
            this.player.closeInventory();
            // send the message
            NextGens.DEFAULT_CONFIG.sendMessage(this.player, this.chest.isBarrel() ? "messages.sellbarrel-pickup" : "messages.autosell-pickup");
            this.player.playSound(this.player.getLocation(), XSound.ENTITY_ITEM_PICKUP.get(), 1.0f, 1.0f);
        });
    }

    private void setStoredButton(FileConfiguration config) {
        List<Integer> slots = config.getIntegerList("stored-slots");
        ItemBuilder builder = ItemBuilder.fromConfig(config, "stored-item");
        if (builder == null) {
            return;
        }
        builder.placeholder(this.createPlaceholder()
                .add("{stored}", Utils.formatBalance((long) this.chest.getStoredAmount()))
                .add("{stored_raw}", Common.digits(this.chest.getStoredAmount())));

        this.setItems(Utils.convertListToIntArray(slots), builder.build());
    }

    private Placeholder createPlaceholder() {
        return new Placeholder()
                .add("{stored}", Utils.formatBalance((long) this.chest.getStoredAmount()))
                .add("{stored_raw}", Common.digits(this.chest.getStoredAmount()))
                .add("{tier}", this.chest.getTier() == null ? "-" : this.chest.getTier())
                .add("{uses}", Common.digits(this.chest.getUsesLeft()))
                .add("{type}", this.chest.isBarrel() ? "Sell Barrel" : "AutoSell Chest");
    }

    private double getMultiplier() {
        User user = NextGens.getInstance().getUserManager().getUser(this.player);
        // use the shared calculator so the displayed multiplier always matches
        // the one used by /sell, sellwands, and the PlaceholderAPI placeholders
        return SellDataCalculator.calculateMultiplier(this.player, user, this.sellwand);
    }

    private double getClaimable(double multiplier) {
        // shared formula with /sell so the payout always matches the displayed amount
        return SellDataCalculator.calculateFinalAmount(this.chest.getStoredAmount(), multiplier);
    }

    private void consumeSellwandUse() {
        if (this.sellwand == null) return;
        // check if the option is enabled
        if (!NextGens.DEFAULT_CONFIG.getConfig().getBoolean("autosell-chest.consume-sellwand-uses")) return;

        ItemStack stack = this.sellwand.getStack();
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        Integer uses = meta.getPersistentDataContainer().get(NextGens.sellwand_uses, PersistentDataType.INTEGER);
        // unlimited use sellwand
        if (uses == null || uses < 0) return;

        int finalUses = uses - 1;
        if (finalUses <= 0) {
            // destroy the sellwand
            stack.setAmount(0);
            NextGens.DEFAULT_CONFIG.sendMessage(this.player, "messages.sellwand-broke");
            this.player.playSound(this.player.getLocation(), XSound.ENTITY_ITEM_BREAK.get(), 1.0f, 1.0f);
            return;
        }
        meta.getPersistentDataContainer().set(NextGens.sellwand_uses, PersistentDataType.INTEGER, finalUses);
        stack.setItemMeta(meta);
        // refresh the sellwand lore
        NextGens.getInstance().getSellwandManager().update(stack);
    }

}
