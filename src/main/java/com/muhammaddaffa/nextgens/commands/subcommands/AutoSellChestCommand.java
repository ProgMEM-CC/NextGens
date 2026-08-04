package com.muhammaddaffa.nextgens.commands.subcommands;

import com.muhammaddaffa.mdlib.commands.args.builtin.IntArg;
import com.muhammaddaffa.mdlib.commands.args.builtin.OnlinePlayerArg;
import com.muhammaddaffa.mdlib.commands.commands.RoutedCommand;
import com.muhammaddaffa.mdlib.utils.Common;
import com.muhammaddaffa.mdlib.utils.Placeholder;
import com.muhammaddaffa.nextgens.NextGens;
import com.muhammaddaffa.nextgens.autosell.AutoSellChestManager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class AutoSellChestCommand {

    public static void handle(RoutedCommand.CommandPlan plan, AutoSellChestManager manager) {
        plan.perm("nextgens.admin")
                .alias("autosellchests")
                .arg("target", new OnlinePlayerArg())
                .argOptional("amount", new IntArg())
                .exec((sender, ctx) -> {
                    Player target = ctx.get("target", Player.class);
                    Integer amount = ctx.get("amount", Integer.class);

                    int actualAmount = amount == null ? 1 : Math.max(1, amount);
                    ItemStack item = manager.createChestItem(0);
                    if (item == null) return;
                    item.setAmount(actualAmount);

                    // actually give the item to the player
                    Common.addInventoryItem(target, item);
                    // send message to the sender
                    NextGens.DEFAULT_CONFIG.sendMessage(sender, "messages.autosell-give", new Placeholder()
                            .add("{amount}", actualAmount)
                            .add("{player}", target.getName()));
                    // send message to the receiver
                    NextGens.DEFAULT_CONFIG.sendMessage(target, "messages.autosell-receive", new Placeholder()
                            .add("{amount}", actualAmount));
                });
    }

}
