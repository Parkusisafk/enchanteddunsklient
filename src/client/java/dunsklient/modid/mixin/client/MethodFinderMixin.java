package dunsklient.modid.mixin.client;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.network.ClientConnection.class)
public class MethodFinderMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        java.util.Arrays.stream(net.minecraft.network.ClientConnection.class.getDeclaredMethods())
                .filter(m -> m.getParameterCount() > 0 &&
                        java.util.Arrays.stream(m.getParameterTypes())
                                .anyMatch(t -> t.getSimpleName().contains("Packet")))
                .forEach(m -> System.out.println("[NET] " + m.getName() + " " +
                        java.util.Arrays.toString(m.getParameterTypes())));
    }
}