package dunsklient.modid.mixin.client;

import dunsklient.modid.ParticleTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ParticleManager.class)
public class ParticleManagerMixin {
    @Inject(method = "addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)Lnet/minecraft/client/particle/Particle;", at = @At("HEAD"))
    private void onAddParticle(ParticleEffect parameters, double x, double y, double z, double velocityX, double velocityY, double velocityZ, CallbackInfoReturnable<Particle> cir) {
        net.minecraft.util.Identifier particleId = net.minecraft.registry.Registries.PARTICLE_TYPE
                .getId(parameters.getType());

        if (particleId == null) return;

        String idStr = particleId.toString();

        // Debug — now you'll see the actual name like "minecraft:crit"
        MinecraftClient mc = MinecraftClient.getInstance();


        // Compare by string ID instead of object reference
        if (mc.player != null && mc.player.squaredDistanceTo(x, y, z) < 4.0 &&
                idStr.equals("minecraft:crit") || idStr.equals("minecraft:enchanted_hit")) {
            BlockPos pos = BlockPos.ofFloored(x, y - 1.0, z);
            ParticleTracker.addParticle(pos, new Vec3d(x, y, z));
            //System.out.println("sdfguislahgfuias");
        }
    }
}