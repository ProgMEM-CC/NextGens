package com.muhammaddaffa.nextgens.autosell.models;

import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Represents a placed AutoSell Chest or Sell Barrel inside the world.
 * The Sell Barrel is a limited use variant, once its uses run out it
 * breaks and disappears.
 */
public class AutoSellChest {

    public enum Type {
        CHEST,
        BARREL
    }

    private final UUID owner;
    private final Location location;
    private final Type type;
    @Nullable
    private final String tier;
    private int usesLeft;
    private double storedAmount;

    public AutoSellChest(UUID owner, Location location, Type type, @Nullable String tier, int usesLeft) {
        this.owner = owner;
        this.location = location;
        this.type = type;
        this.tier = tier;
        this.usesLeft = usesLeft;
    }

    public UUID getOwner() {
        return owner;
    }

    public Location getLocation() {
        return location;
    }

    public Type getType() {
        return type;
    }

    @Nullable
    public String getTier() {
        return tier;
    }

    public boolean isBarrel() {
        return type == Type.BARREL;
    }

    public synchronized double getStoredAmount() {
        return storedAmount;
    }

    public synchronized void setStoredAmount(double storedAmount) {
        this.storedAmount = Math.max(0, storedAmount);
    }

    public synchronized void addAmount(double amount) {
        this.storedAmount += Math.max(0, amount);
    }

    public synchronized int getUsesLeft() {
        return usesLeft;
    }

    public synchronized void setUsesLeft(int usesLeft) {
        this.usesLeft = Math.max(0, usesLeft);
    }

    public synchronized void decrementUses() {
        if (this.usesLeft > 0) {
            this.usesLeft--;
        }
    }

}
