package com.github.zly2006.worldguard.mixin;

import com.github.zly2006.enclosure.EnclosureArea;
import com.github.zly2006.enclosure.ServerMain;
import com.github.zly2006.enclosure.access.PlayerAccess;
import com.github.zly2006.enclosure.utils.Permission;
import com.github.zly2006.enclosure.utils.Utils;
import com.mojang.authlib.GameProfile;
import net.fabricmc.api.Environment;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.EntityDamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.encryption.PlayerPublicKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.github.zly2006.enclosure.ServerMain.Instance;
import static com.github.zly2006.enclosure.ServerMain.commonConfig;
import static com.github.zly2006.enclosure.commands.EnclosureCommand.CONSOLE;
import static com.github.zly2006.enclosure.utils.Permission.*;
import static net.fabricmc.api.EnvType.SERVER;

@Environment(SERVER)
@Mixin(ServerPlayerEntity.class)
public abstract class MixinServerPlayerEntity extends PlayerEntity implements PlayerAccess {
    private long lastTeleportTime = 0;
    private Vec3d lastPos = null;
    private EnclosureArea lastArea = null;
    private final List<ItemStack> drops = new ArrayList<>();
    @Shadow public ServerPlayNetworkHandler networkHandler;
    private ServerWorld lastWorld;

    @Shadow public abstract void sendMessage(Text message);

    @Shadow public abstract ServerWorld getWorld();

    @Shadow public abstract void sendMessage(Text message, boolean overlay);

    @Shadow @Final public MinecraftServer server;

    public MixinServerPlayerEntity(World world, BlockPos pos, float yaw, GameProfile gameProfile, @Nullable PlayerPublicKey publicKey) {
        super(world, pos, yaw, gameProfile, publicKey);
    }

    @Inject(method = "damage", at = @At("HEAD"), cancellable = true)
    private void protectPVP(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (!(source instanceof EntityDamageSource)) {
            return;
        }
        if (source.getAttacker() instanceof ServerPlayerEntity attacker) {
            //pvp
            EnclosureArea area = Instance.getAllEnclosures((ServerWorld) this.getWorld()).getArea(getBlockPos());
            EnclosureArea attackerArea = Instance.getAllEnclosures(attacker.getWorld()).getArea(attacker.getBlockPos());
            if (area != null && !area.areaOf(getBlockPos()).hasPubPerm(Permission.PVP)) {
                cir.setReturnValue(false);
            }
            if (attackerArea != null && !attackerArea.areaOf(attacker.getBlockPos()).hasPubPerm(Permission.PVP)
                    && !attacker.getCommandSource().hasPermissionLevel(4)) {
                attacker.sendMessage(PVP.getNoPermissionMes(attacker));
                cir.setReturnValue(false);
            }
        }
    }

    @Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
    private void protectDropItem(ItemStack stack, boolean throwRandomly, boolean retainOwnership, CallbackInfoReturnable<ItemEntity> cir) {
        EnclosureArea area = Instance.getAllEnclosures(getWorld()).getArea(getBlockPos());
        if (area == null) {
            return;
        }
        area = area.areaOf(getBlockPos());
        if (!area.hasPerm(networkHandler.player, Permission.DROP_ITEM)) {
            this.sendMessage(Permission.DROP_ITEM.getNoPermissionMes(networkHandler.player));
            if (!isDead() && getInventory().insertStack(stack)) {
                cir.setReturnValue(null);
            }
            else {
                drops.add(stack);
            }
        }
    }

    @Nullable
    private Text formatMessage(@NotNull String message, @NotNull EnclosureArea area, ServerPlayerEntity player) {
        if (message.equals("#none")) {
            return null;
        }
        if (message.startsWith("#rich:")) {
            if (ServerMain.commonConfig.allowRichMessage) {
                MutableText text = Text.Serializer.fromJson(message.substring(6));
                return text;
            }
            else {
                message = message.substring(6);
            }
        }
        String username = area.getOwner() == CONSOLE ? "Server-Owned-Land" :
                Optional.ofNullable(Utils.getNameByUUID(area.getOwner()))
                        .orElse("§cUnknown§r");
        return Text.of(
                message.replace("%player%", player.getDisplayName().getString())
                        .replace("%name%", area.getName())
                        .replace("%owner%", username)
                        .replace("%world%", area.getWorld().getRegistryKey().getValue().toString())
                        .replace("%full_name%", area.getFullName())
        );
    }

    private void sendFormattedMessage(ServerPlayerEntity player, EnclosureArea area, boolean enter) {
        MutableText text = Text.of(enter ? commonConfig.enterMessageHeader : commonConfig.leaveMessageHeader).copy();
        if (enter) {
            if (area.getEnterMessage().equals("#none")) {
                return;
            } else if (area.getEnterMessage().isEmpty()) {
                text.append(formatMessage(ServerMain.commonConfig.defaultEnterMessage, area, player));
            } else {
                text.append(formatMessage(area.getEnterMessage(), area, player));
            }
        } else {
            if (area.getLeaveMessage().equals("#none")) {
                return;
            } else if (area.getLeaveMessage().isEmpty()) {
                text.append(formatMessage(ServerMain.commonConfig.defaultLeaveMessage, area, player));
            } else {
                text.append(formatMessage(area.getLeaveMessage(), area, player));
            }
        }
        player.sendMessage(text);
    }
    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        if (!isDead() && !drops.isEmpty()) {
            drops.removeIf(itemStack -> getInventory().insertStack(itemStack));
        }
        if (server.getTicks() % 10 == 0) {
            ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
            EnclosureArea area = Instance.getAllEnclosures(getWorld()).getArea(getBlockPos());
            if (area != null) {
                area = area.areaOf(getBlockPos());
            }
            if (lastArea != null) {
                if (area != lastArea) {
                    sendFormattedMessage(player, lastArea, false);
                }
            }
            if (area != null) {
                if (!area.hasPerm(player, MOVE)) {
                    if (area != lastArea) {
                        // teleport back
                        player.sendMessage(MOVE.getNoPermissionMes(player));
                        player.teleport(lastWorld, lastPos.x, lastPos.y, lastPos.z, 0, 0);
                    } else {
                        // kick player out
                        player.sendMessage(MOVE.getNoPermissionMes(player));
                        int x = area.getMinX() - 1;
                        int z = area.getMinZ() - 1;
                        int y = area.getWorld().getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                        player.teleport(area.getWorld(), x, y, z, 0, 0);
                    }
                }
                if (area != lastArea) {
                    sendFormattedMessage(player, area, true);
                }
                // glowing effect
                if (Boolean.TRUE.equals(area.hasPerm(player, GLOWING))) {
                    player.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 15, 1, false, false, false));
                }
            }
            lastArea = area;
            lastPos = player.getPos();
            lastWorld = player.getWorld();
        }
    }

    @Override
    public long getLastTeleportTime() {
        return lastTeleportTime;
    }

    @Override
    public void setLastTeleportTime(long lastTeleportTime) {
        this.lastTeleportTime = lastTeleportTime;
    }
}
