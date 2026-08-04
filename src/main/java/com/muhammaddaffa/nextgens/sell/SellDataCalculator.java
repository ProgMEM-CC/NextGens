package com.muhammaddaffa.nextgens.sell;

import com.muhammaddaffa.nextgens.NextGens;
import com.muhammaddaffa.nextgens.sell.multipliers.SellMultiplierProvider;
import com.muhammaddaffa.nextgens.sellwand.models.SellwandData;
import com.muhammaddaffa.nextgens.users.models.User;
import com.muhammaddaffa.nextgens.utils.SellData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

public class SellDataCalculator {

    /**
     * Calculates the total sell multiplier for a player by summing up all the
     * registered multiplier providers and applying the player multiplier limit.
     * This is the single source of truth used by sells, the autosell chest and
     * sell barrel claims, and the PlaceholderAPI placeholders, so they all stay
     * in sync with each other.
     */
    public static double calculateMultiplier(Player player, User user, SellwandData sellwand) {
        double totalMultiplier = 0;

        // Get all multipliers
        for (SellMultiplierProvider provider : NextGens.getInstance().getMultiplierRegistry().getMultipliers()) {
            double multiplier = provider.getMultiplier(player, user, sellwand);
            if (multiplier > 0) {
                totalMultiplier += multiplier;
            }
        }

        // Apply multiplier limit if needed
        FileConfiguration config = NextGens.DEFAULT_CONFIG.getConfig();
        if (config.getBoolean("player-multiplier-limit.enabled")) {
            double limit = config.getDouble("player-multiplier-limit.limit");
            if (totalMultiplier > limit) {
                totalMultiplier = limit;
            }
        }

        return totalMultiplier;
    }

    /**
     * Applies the multiplier to a raw value. A multiplier below 1 is treated as
     * a bonus on top of the base value (e.g. 0.5 becomes 1.5x), any multiplier
     * of 1 or above is used directly.
     */
    public static double calculateFinalAmount(double totalValue, double multiplier) {
        if (multiplier < 1) {
            return totalValue * (multiplier + 1);
        }
        return totalValue * multiplier;
    }

    public static SellData calculateSellData(Player player, User user, SellwandData sellwand, double totalValue, int totalItems) {
        double totalMultiplier = calculateMultiplier(player, user, sellwand);
        double finalAmount = calculateFinalAmount(totalValue, totalMultiplier);
        return new SellData(user, finalAmount, totalItems, totalMultiplier, sellwand);
    }

}
