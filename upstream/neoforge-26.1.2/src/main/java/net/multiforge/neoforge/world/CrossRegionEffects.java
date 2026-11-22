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
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
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

    /** An entity not yet in a level is visible only to the code building it, so it runs inline. */
    private static boolean isForeign(LivingEntity entity) {
        Level level = entity.level();
        if (level.isClientSide() || !entity.isAddedToLevel()) return false;
        return OwnershipEnforcer.isCrossRegionFromWorker(
                level.mfWorldRef(), entity.getBlockX() >> 4, entity.getBlockZ() >> 4);
    }

    private static void defer(String site, LivingEntity entity, Runnable call) {
        ProbeRegistry.bump(site + ":deferred-cross-region");
        OwnershipEnforcer.reroute(site, () -> {
            if (!entity.isRemoved()) call.run();
        });
    }
}
