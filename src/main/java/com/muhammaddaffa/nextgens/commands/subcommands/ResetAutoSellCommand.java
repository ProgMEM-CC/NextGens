package com.muhammaddaffa.nextgens.commands.subcommands;

import com.muhammaddaffa.mdlib.commands.commands.RoutedCommand;
import com.muhammaddaffa.mdlib.utils.Placeholder;
import com.muhammaddaffa.nextgens.NextGens;
import com.muhammaddaffa.nextgens.autosell.AutoSellChestManager;

public class ResetAutoSellCommand {

    public static void handle(RoutedCommand.CommandPlan plan, AutoSellChestManager manager) {
        plan.perm("nextgens.admin")
                .alias("resetautosells")
                .exec((sender, ctx) -> {
                    int reset = manager.cleanupGhostChests();
                    NextGens.DEFAULT_CONFIG.sendMessage(sender, "messages.autosell-reset", new Placeholder()
                            .add("{amount}", reset));
                });
    }

}
