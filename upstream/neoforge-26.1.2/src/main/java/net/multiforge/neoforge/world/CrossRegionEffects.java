/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package net.multiforge.neoforge.world;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.ownership.OwnershipEnforcer;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * Potion-effect, health, experience and exhaustion changes aimed at an entity another region owns. An entity's effect
 * map and attribute map belong to the region ticking it, so a block entity changing
 * them from its own worker (a beacon-like block buffing its owner anywhere in the
 * world) races that entity's tick and corrupts both. The call is re-run on the server
 * thread after the tick barrier, while no region runs. It lives here so the patched
 * {@code LivingEntity} gains no lambdas.
 */
@ApiStatus.Internal
public final class CrossRegionEffects {
    private CrossRegionEffects() {}

    public static boolean deferAdd(LivingEntity entity, MobEffectInstance effect, @Nullable Entity source) {
        if (!isForeign(entity)) return false;
        // The caller may reuse its instance; the deferred call must not see that.
        MobEffectInstance copy = new MobEffectInstance(effect);
        defer("LivingEntity.addEffect", entity, () -> entity.addEffect(copy, source));
        return true;
    }

    public static boolean deferForceAdd(LivingEntity entity, MobEffectInstance effect, @Nullable Entity source) {
        if (!isForeign(entity)) return false;
        MobEffectInstance copy = new MobEffectInstance(effect);
        defer("LivingEntity.forceAddEffect", entity, () -> entity.forceAddEffect(copy, source));
        return true;
    }

    public static boolean deferRemove(LivingEntity entity, Holder<MobEffect> effect) {
        if (!isForeign(entity)) return false;
        defer("LivingEntity.removeEffect", entity, () -> entity.removeEffect(effect));
        return true;
    }

    public static boolean deferRemoveNoUpdate(LivingEntity entity, Holder<MobEffect> effect) {
        if (!isForeign(entity)) return false;
        defer("LivingEntity.removeEffectNoUpdate", entity, () -> entity.removeEffectNoUpdate(effect));
        return true;
    }

    public static boolean deferRemoveAll(LivingEntity entity) {
        if (!isForeign(entity)) return false;
        defer("LivingEntity.removeAllEffects", entity, entity::removeAllEffects);
        return true;
    }

    public static boolean deferHeal(LivingEntity entity, float amount) {
        if (!isForeign(entity)) return false;
        defer("LivingEntity.heal", entity, () -> entity.heal(amount));
        return true;
    }

    public static boolean deferSetHealth(LivingEntity entity, float health) {
        if (!isForeign(entity)) return false;
        defer("LivingEntity.setHealth", entity, () -> entity.setHealth(health));
        return true;
    }

    public static boolean deferAbsorption(LivingEntity entity, float amount) {
        if (!isForeign(entity)) return false;
        defer("LivingEntity.setAbsorptionAmount", entity, () -> entity.setAbsorptionAmount(amount));
        return true;
    }

    public static boolean deferExperiencePoints(Player player, int points) {
        if (!isForeign(player)) return false;
        defer("Player.giveExperiencePoints", player, () -> player.giveExperiencePoints(points));
        return true;
    }

    public static boolean deferExperienceLevels(Player player, int levels) {
        if (!isForeign(player)) return false;
        defer("Player.giveExperienceLevels", player, () -> player.giveExperienceLevels(levels));
        return true;
    }

    public static boolean deferExhaustion(Player player, float amount) {
        if (!isForeign(player)) return false;
        defer("Player.causeFoodExhaustion", player, () -> player.causeFoodExhaustion(amount));
        return true;
    }

    /**
     * Damage aimed at an entity another region is ticking. The real call runs after the
     * barrier, so the caller gets a prediction: {@code false}, with nothing queued, when the
     * entity is gone or immune to this source (Vanilla's answer too); otherwise {@code true},
     * although an event or armor may still reduce or cancel the damage.
     */
    public static boolean deferHurt(Entity entity, ServerLevel level, DamageSource source, float amount) {
        if (entity.isRemoved() || !entity.isAlive()) return false;
        if (entity instanceof LivingEntity living && living.isInvulnerableTo(level, source)) return false;
        defer("Entity.hurtServer", entity, () -> {
            if (entity.level() == level) entity.hurtServer(level, source, amount);
        });
        return true;
    }

    public static boolean deferSetItemSlot(LivingEntity entity, EquipmentSlot slot, ItemStack stack, boolean insideTransaction) {
        if (!isForeign(entity)) return false;
        ItemStack copy = stack.copy();
        defer("LivingEntity.setItemSlot", entity, () -> entity.setItemSlot(slot, copy, insideTransaction));
        return true;
    }

    public static boolean deferEat(FoodData food, @Nullable Player owner, int nutrition, float saturationModifier) {
        if (owner == null || !isForeign(owner)) return false;
        defer("FoodData.eat", owner, () -> food.eat(nutrition, saturationModifier));
        return true;
    }

    public static boolean deferEat(FoodData food, @Nullable Player owner, FoodProperties properties) {
        if (owner == null || !isForeign(owner)) return false;
        defer("FoodData.eat", owner, () -> food.eat(properties));
        return true;
    }

    public static boolean deferAddExhaustion(FoodData food, @Nullable Player owner, float amount) {
        if (owner == null || !isForeign(owner)) return false;
        defer("FoodData.addExhaustion", owner, () -> food.addExhaustion(amount));
        return true;
    }

    public static boolean deferSetFoodLevel(FoodData food, @Nullable Player owner, int level) {
        if (owner == null || !isForeign(owner)) return false;
        defer("FoodData.setFoodLevel", owner, () -> food.setFoodLevel(level));
        return true;
    }

    public static boolean deferSetSaturation(FoodData food, @Nullable Player owner, float saturation) {
        if (owner == null || !isForeign(owner)) return false;
        defer("FoodData.setSaturation", owner, () -> food.setSaturation(saturation));
        return true;
    }

    public static boolean deferUpdateAbilities(ServerPlayer player) {
        if (!isForeign(player)) return false;
        defer("ServerPlayer.onUpdateAbilities", player, player::onUpdateAbilities);
        return true;
    }

    /** An entity not yet in a level is visible only to the code building it, so it runs inline. */
    public static boolean isForeign(Entity entity) {
        Level level = entity.level();
        if (level.isClientSide() || !entity.isAddedToLevel()) return false;
        return OwnershipEnforcer.isCrossRegionFromWorker(
                level.mfWorldRef(), entity.getBlockX() >> 4, entity.getBlockZ() >> 4);
    }

    /** Runs even if the entity has gone: the call holds items that must not vanish with it. */
    static void deferAlways(String site, Runnable call) {
        ProbeRegistry.bump(site + ":deferred-cross-region");
        OwnershipEnforcer.reroute(site, call);
    }

    static void defer(String site, Entity entity, Runnable call) {
        ProbeRegistry.bump(site + ":deferred-cross-region");
        OwnershipEnforcer.reroute(site, () -> {
            if (!entity.isRemoved()) call.run();
        });
    }
}
