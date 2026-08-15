package com.muhammaddaffa.nextgens.autosell;

import com.muhammaddaffa.mdlib.hooks.VaultEconomy;
import com.muhammaddaffa.mdlib.task.ExecutorManager;
import com.muhammaddaffa.mdlib.utils.Common;
import com.muhammaddaffa.mdlib.utils.ItemBuilder;
import com.muhammaddaffa.mdlib.utils.LocationUtils;
import com.muhammaddaffa.mdlib.utils.Logger;
import com.muhammaddaffa.mdlib.utils.Placeholder;
import com.muhammaddaffa.nextgens.NextGens;
import com.muhammaddaffa.nextgens.autosell.models.AutoSellChest;
import com.muhammaddaffa.nextgens.database.DatabaseManager;
import com.muhammaddaffa.nextgens.users.models.User;
import com.muhammaddaffa.nextgens.utils.Settings;
import com.muhammaddaffa.nextgens.utils.Utils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class AutoSellChestManager {

    private final DatabaseManager dbm;
    private final ConcurrentMap<String, AutoSellChest> chestMap = new ConcurrentHashMap<>();

    public AutoSellChestManager(DatabaseManager dbm) {
        this.dbm = dbm;
    }

    public boolean isEnabled() {
        return Settings.AUTOSELL_CHEST_ENABLED;
    }

    public int getMaxPerPlayer(boolean barrel) {
        return barrel ? Settings.AUTOSELL_BARREL_MAX_PER_PLAYER : Settings.AUTOSELL_CHEST_MAX_PER_PLAYER;
    }

    public boolean isAutoSellItem(ItemStack stack) {
        if (stack == null || stack.getItemMeta() == null) return false;
        return stack.getItemMeta().getPersistentDataContainer().has(NextGens.autosell_id, PersistentDataType.STRING);
    }

    @Nullable
    public AutoSellChest getByBlock(@Nullable Block block) {
        if (block == null) return null;
        return this.chestMap.get(LocationUtils.serialize(block.getLocation()));
    }

    @Nullable
    public AutoSellChest getByLocation(Location location) {
        if (location == null || location.getWorld() == null) return null;
        return this.chestMap.get(LocationUtils.serialize(location));
    }

    public Collection<AutoSellChest> getChests() {
        return this.chestMap.values();
    }

    /**
     * Removes every placed container whose block no longer exists in the world
     * (e.g. it was removed by another plugin or a setblock command without
     * going through the break/pickup flow). The stored money is refunded to
     * the owner before the container is unregistered.
     *
     * @return the amount of containers that were cleaned up
     */
    public int cleanupGhostChests() {
        // collect the ghosts first so we don't modify the map while iterating
        List<AutoSellChest> ghosts = new ArrayList<>();
        for (AutoSellChest chest : this.chestMap.values()) {
            Location location = chest.getLocation();
            // if the world is not loaded we cannot verify the block, leave it alone
            if (location.getWorld() == null) continue;
            Block block = location.getBlock();
            boolean valid;
            if (chest.isBarrel()) {
                valid = block.getType() == Material.BARREL;
            } else {
                valid = block.getType() == Material.CHEST || block.getType() == Material.TRAPPED_CHEST;
            }
            if (!valid) {
                ghosts.add(chest);
            }
        }

        for (AutoSellChest chest : ghosts) {
            // refund the stored money to the owner
            double stored = chest.getStoredAmount();
            if (stored > 0) {
                OfflinePlayer owner = Bukkit.getOfflinePlayer(chest.getOwner());
                VaultEconomy.deposit(owner, stored);
            }
            // remove the container from the cache and the database
            this.unregister(chest.getLocation());
        }
        return ghosts.size();
    }

    public void register(AutoSellChest chest) {
        this.chestMap.put(LocationUtils.serialize(chest.getLocation()), chest);
        ExecutorManager.getProvider().async(() -> this.saveChest(chest));
    }

    public void unregister(Location location) {
        if (location == null) return;
        String serialized = LocationUtils.serialize(location);
        AutoSellChest removed = this.chestMap.remove(serialized);
        if (removed != null) {
            ExecutorManager.getProvider().async(() -> this.deleteChest(removed));
        }
    }

    public int getCount(UUID owner, AutoSellChest.Type type) {
        int count = 0;
        for (AutoSellChest chest : this.chestMap.values()) {
            if (chest.getOwner().equals(owner) && chest.getType() == type) {
                count++;
            }
        }
        return count;
    }

    /**
     * Checks if the player is allowed to access the container. The owner always
     * has access, together with the members trusted via the trust system, and
     * players that hold the nextgens.autosell.others permission.
     */
    public boolean hasAccess(Player player, AutoSellChest chest) {
        if (player.getUniqueId().equals(chest.getOwner())) return true;
        if (player.hasPermission("nextgens.autosell.others")) return true;
        User owner = NextGens.getInstance().getUserManager().getUser(chest.getOwner());
        return owner.isMember(player.getUniqueId());
    }

    /**
     * Finds the container that should collect the drops of a generator owned by
     * the given player. The container owner must either be the generator owner
     * itself, or the generator owner must have trusted the container owner.
     */
    @Nullable
    public AutoSellChest getApplicableChest(UUID generatorOwner) {
        if (generatorOwner == null || !this.isEnabled() || this.chestMap.isEmpty()) return null;
        // containers owned directly by the generator owner
        AutoSellChest own = this.getOwned(generatorOwner, AutoSellChest.Type.CHEST);
        if (own != null) return own;
        own = this.getOwned(generatorOwner, AutoSellChest.Type.BARREL);
        if (own != null) return own;
        // containers owned by players the generator owner has trusted
        User ownerUser = NextGens.getInstance().getUserManager().getUser(generatorOwner);
        for (AutoSellChest chest : this.chestMap.values()) {
            if (ownerUser.isMember(chest.getOwner())) {
                return chest;
            }
        }
        return null;
    }

    private AutoSellChest getOwned(UUID owner, AutoSellChest.Type type) {
        for (AutoSellChest chest : this.chestMap.values()) {
            if (chest.getOwner().equals(owner) && chest.getType() == type) {
                return chest;
            }
        }
        return null;
    }

    /**
     * Adds money into the container. Sell barrels do not consume any use on
     * deposit, the uses are only consumed when the owner claims the money
     * through the gui (see {@link #consumeBarrelUse(AutoSellChest)}).
     */
    public void deposit(AutoSellChest chest, double amount) {
        if (chest == null || amount <= 0) return;
        synchronized (chest) {
            chest.addAmount(amount);
        }
        ExecutorManager.getProvider().async(() -> this.saveChest(chest));
    }

    /**
     * Consumes one use of a sell barrel. Each claim consumes exactly one use,
     * and once the uses run out the barrel breaks and disappears.
     *
     * @return true if the barrel broke (and was removed from the world)
     */
    public boolean consumeBarrelUse(AutoSellChest chest) {
        if (chest == null || !chest.isBarrel()) return false;
        boolean broke;
        synchronized (chest) {
            chest.decrementUses();
            broke = chest.getUsesLeft() <= 0;
        }
        if (broke) {
            this.breakBarrel(chest);
            return true;
        }
        ExecutorManager.getProvider().async(() -> this.saveChest(chest));
        return false;
    }

    private void breakBarrel(AutoSellChest chest) {
        Location location = chest.getLocation();
        double stored = chest.getStoredAmount();
        // remove the container from the cache and the database
        this.unregister(location);
        // remove the block
        Block block = location.getBlock();
        if (block.getType() == Material.BARREL) {
            block.setType(Material.AIR);
        }
        // pay out the stored money to the owner
        if (stored > 0) {
            OfflinePlayer owner = Bukkit.getOfflinePlayer(chest.getOwner());
            VaultEconomy.deposit(owner, stored);
            Player online = owner.getPlayer();
            if (online != null) {
                NextGens.DEFAULT_CONFIG.sendMessage(online, "messages.sellbarrel-broke", new Placeholder()
                        .add("{amount}", Common.digits(stored))
                        .add("{amount_formatted}", Utils.formatBalance((long) stored)));
            }
        }
    }

    // --------------------------------
    // Item creation
    // --------------------------------

    @Nullable
    public ItemStack createChestItem(double amount) {
        ItemBuilder builder = ItemBuilder.fromConfig(NextGens.AUTOSELL_CONFIG.getConfig(), "chest-item");
        if (builder == null) return null;
        builder.pdc(NextGens.autosell_id, "chest");
        builder.pdc(NextGens.autosell_amount, amount);
        builder.placeholder(new Placeholder()
                .add("{amount}", Common.digits(amount))
                .add("{amount_formatted}", Utils.formatBalance((long) amount)));
        return builder.build();
    }

    @Nullable
    public ItemStack createBarrelItem(String tier, int usesLeft, double amount) {
        FileConfiguration config = NextGens.AUTOSELL_CONFIG.getConfig();
        if (tier == null || !config.isConfigurationSection("sell-barrel.tiers." + tier)) return null;
        ItemBuilder builder = ItemBuilder.fromConfig(config, "sell-barrel.tiers." + tier);
        if (builder == null) return null;
        builder.pdc(NextGens.autosell_id, "barrel");
        builder.pdc(NextGens.autosell_tier, tier);
        builder.pdc(NextGens.autosell_uses, usesLeft);
        builder.pdc(NextGens.autosell_amount, amount);
        builder.placeholder(new Placeholder()
                .add("{tier}", tier)
                .add("{uses}", Common.digits(usesLeft))
                .add("{amount}", Common.digits(amount))
                .add("{amount_formatted}", Utils.formatBalance((long) amount)));
        return builder.build();
    }

    public int getDefaultBarrelUses(String tier) {
        return NextGens.AUTOSELL_CONFIG.getInt("sell-barrel.tiers." + tier + ".uses");
    }

    @Nullable
    public String getTierFromItem(ItemStack stack) {
        if (stack == null || stack.getItemMeta() == null) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(NextGens.autosell_tier, PersistentDataType.STRING);
    }

    @Nullable
    public Integer getUsesFromItem(ItemStack stack) {
        if (stack == null || stack.getItemMeta() == null) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(NextGens.autosell_uses, PersistentDataType.INTEGER);
    }

    public double getAmountFromItem(ItemStack stack) {
        if (stack == null || stack.getItemMeta() == null) return 0;
        Double amount = stack.getItemMeta().getPersistentDataContainer().get(NextGens.autosell_amount, PersistentDataType.DOUBLE);
        return amount == null ? 0 : amount;
    }

    // --------------------------------
    // Database
    // --------------------------------

    public void load() {
        String query = "SELECT * FROM " + DatabaseManager.AUTOSELL_TABLE;
        this.dbm.executeQuery(query, result -> {
            int count = 0;
            while (result.next()) {
                try {
                    String locationString = result.getString("location");
                    String ownerString = result.getString("owner");
                    if (locationString == null || ownerString == null) continue;
                    Location location = LocationUtils.deserialize(locationString);
                    if (location == null || location.getWorld() == null) continue;
                    String type = result.getString("type");
                    double amount = result.getDouble("amount");
                    String tier = result.getString("tier");
                    int uses = result.getInt("uses");
                    AutoSellChest.Type chestType = "BARREL".equalsIgnoreCase(type) ? AutoSellChest.Type.BARREL : AutoSellChest.Type.CHEST;
                    AutoSellChest chest = new AutoSellChest(UUID.fromString(ownerString), location, chestType, tier, uses);
                    chest.setStoredAmount(amount);
                    this.chestMap.put(locationString, chest);
                    count++;
                } catch (Exception ex) {
                    Logger.warning("Failed to load an autosell chest, skipping it!");
                }
            }
            Logger.info("Successfully loaded " + count + " autosell chests!");
        });
    }

    public void saveChest(AutoSellChest chest) {
        if (chest == null || chest.getLocation().getWorld() == null) return;
        String query = this.dbm.isMysql() ?
                "INSERT INTO " + DatabaseManager.AUTOSELL_TABLE + " " +
                        "(location, owner, type, amount, tier, uses) " +
                        "VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE amount = VALUES(amount), uses = VALUES(uses)" :
                "INSERT INTO " + DatabaseManager.AUTOSELL_TABLE + " " +
                        "(location, owner, type, amount, tier, uses) " +
                        "VALUES (?,?,?,?,?,?) ON CONFLICT(location) DO UPDATE SET amount = excluded.amount, uses = excluded.uses";
        this.dbm.buildStatement(query, statement -> {
            statement.setString(1, LocationUtils.serialize(chest.getLocation()));
            statement.setString(2, chest.getOwner().toString());
            statement.setString(3, chest.getType().name());
            statement.setDouble(4, chest.getStoredAmount());
            statement.setString(5, chest.getTier());
            statement.setInt(6, chest.getUsesLeft());
            statement.executeUpdate();
        });
    }

    public void deleteChest(AutoSellChest chest) {
        if (chest == null) return;
        this.dbm.buildStatement("DELETE FROM " + DatabaseManager.AUTOSELL_TABLE + " WHERE location=?;", statement -> {
            statement.setString(1, LocationUtils.serialize(chest.getLocation()));
            statement.executeUpdate();
        });
    }

}
