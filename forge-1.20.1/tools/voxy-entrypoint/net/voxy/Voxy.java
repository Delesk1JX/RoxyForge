package net.voxy;

import net.minecraftforge.fml.common.Mod;

/**
 * Not Voxy's code and not shipped in Voxy's jar: RoxyForge adds this class to its runtime copy of the
 * Voxy jar so Forge finds a mod entrypoint. Voxy itself is a Fabric mod driven by RoxyForge through its
 * own entrypoint shim, so this class only has to exist and stay quiet.
 */
@Mod("voxy")
public final class Voxy {
    public Voxy() {
    }
}