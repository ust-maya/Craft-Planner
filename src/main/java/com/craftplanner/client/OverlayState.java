package com.craftplanner.client;

import com.craftplanner.Config;

/**
 * Visibility flag of the craft-tree overlay, toggled with the {@code V} key.
 *
 * <p>Starts from {@code overlay.enabled} of the client config and is reset to
 * it whenever the config is reloaded.</p>
 */
public final class OverlayState {

    private static boolean visible = Config.overlayEnabled();

    private OverlayState() {
    }

    public static boolean isVisible() {
        return visible;
    }

    public static void setVisible(boolean value) {
        visible = value;
    }

    public static void toggle() {
        visible = !visible;
    }
}
