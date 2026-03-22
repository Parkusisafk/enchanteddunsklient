package dunsklient.modid;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.concurrent.ConcurrentHashMap;

public class ParticleTracker {
    // Maps BlockPos to the last seen "multiplier" particle on that block
    private static final ConcurrentHashMap<BlockPos, Vec3d> activeMultipliers = new ConcurrentHashMap<>();

    public static void addParticle(BlockPos pos, Vec3d particleCoords) {
        activeMultipliers.put(pos, particleCoords);
    }

    public static Vec3d getParticleForBlock(BlockPos pos) {
        // Remove it once we've "used" it so we don't snap back to old particles
        return activeMultipliers.remove(pos);
    }

    public static void clear() {
        activeMultipliers.clear();
    }
}