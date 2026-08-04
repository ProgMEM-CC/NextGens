package com.muhammaddaffa.nextgens.commands.subcommands;

import com.muhammaddaffa.mdlib.commands.args.ArgSuggester;
import com.muhammaddaffa.mdlib.commands.args.builtin.IntArg;
import com.muhammaddaffa.mdlib.commands.args.builtin.OnlinePlayerArg;
import com.muhammaddaffa.mdlib.commands.args.builtin.StringArg;
import com.muhammaddaffa.mdlib.commands.commands.RoutedCommand;
import com.muhammaddaffa.mdlib.utils.Common;
import com.muhammaddaffa.mdlib.utils.Placeholder;
import com.muhammaddaffa.nextgens.NextGens;
import com.muhammaddaffa.nextgens.autosell.AutoSellChestManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;

public class SellBarrelCommand {

    public static void handle(RoutedCommand.CommandPlan plan, AutoSellChestManager manager) {
        plan.perm("nextgens.admin")
                .alias("sellbarrels")
                .arg("target", new OnlinePlayerArg())
                .arg("tier", new StringArg(), ArgSuggester.ofDynamic((sender, prefix) -> {
                    ConfigurationSection tiers = NextGens.AUTOSELL_CONFIG.getConfig().getConfigurationSection("sell-barrel.tiers");
                    return tiers == null ? new ArrayList<>() : new ArrayList<>(tiers.getKeys(false));
                }))
                .argOptional("amount", new IntArg())
                .exec((sender, ctx) -> {
                    Player target = ctx.get("target", Player.class);
                    String tier = ctx.get("tier", String.class);
                    Integer amount = ctx.get("amount", Integer.class);

                    if (!NextGens.AUTOSELL_CONFIG.getConfig().isConfigurationSection("sell-barrel.tiers." + tier)) {
                        NextGens.DEFAULT_CONFIG.sendMessage(sender, "messages.sellbarrel-invalid-tier");
                        return;
                    }

                    int actualAmount = amount == null ? 1 : Math.max(1, amount);
                    ItemStack item = manager.createBarrelItem(tier, manager.getDefaultBarrelUses(tier), 0);
                    if (item == null) return;
                    item.setAmount(actualAmount);

                    // actually give the item to the player
                    Common.addInventoryItem(target, item);
                    // send message to the sender
                    NextGens.DEFAULT_CONFIG.sendMessage(sender, "messages.sellbarrel-give", new Placeholder()
                            .add("{amount}", actualAmount)
                            .add("{tier}", tier)
                            .add("{player}", target.getName()));
                    // send message to the receiver
                    NextGens.DEFAULT_CONFIG.sendMessage(target, "messages.sellbarrel-receive", new Placeholder()
                            .add("{amount}", actualAmount)
                            .add("{tier}", tier));
                });
    }

}
