package piratepat;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;

import lunalib.lunaSettings.LunaSettings;
import lunalib.lunaSettings.LunaSettingsListener;

/**
 * LunaLib-only: reacts the moment the player saves the in-game settings
 * panel, so switching the debt system off stands every collector down on the
 * spot rather than on the next tick, and switching it back on restarts a
 * dormant balance's clocks immediately.
 *
 * <p>References LunaLib types directly, so it must ONLY be loaded behind
 * {@link PiratePatConfig#lunaAvailable()} - the same rule as
 * {@link LunaConfigBridge}. Registered once per application run: LunaLib's
 * listener list is static, and hasSettingsListenerOfClass guards the re-add
 * on every game load. It lives in LunaLib, not in the sector, so it is never
 * serialized and holds no state.
 */
class LunaSettingsHook implements LunaSettingsListener {

	private static Logger log = Global.getLogger(LunaSettingsHook.class);

	static void register() {
		if (LunaSettings.hasSettingsListenerOfClass(LunaSettingsHook.class)) return;
		LunaSettings.addSettingsListener(new LunaSettingsHook());
		log.info("Registered LunaLib settings listener");
	}

	public void settingsChanged(String modId) {
		if (!PiratePatConfig.MOD_ID.equals(modId)) return;
		try {
			PiratePatModPlugin.applySettings();
		} catch (Exception e) {
			// never let a settings save fall over on our account
			log.warn("Failed to apply changed settings", e);
		}
	}
}
