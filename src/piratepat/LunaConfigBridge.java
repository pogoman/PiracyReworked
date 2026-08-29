package piratepat;

import lunalib.lunaSettings.LunaSettings;

/**
 * Thin wrapper around LunaLib's settings API. This class references LunaLib
 * types directly, so it must ONLY be loaded when LunaLib is present - callers
 * gate every use behind {@link PiratePatConfig#lunaAvailable()}.
 */
class LunaConfigBridge {

	static Integer getInt(String key) {
		return LunaSettings.getInt(PiratePatConfig.MOD_ID, key);
	}

	static Float getFloat(String key) {
		return LunaSettings.getFloat(PiratePatConfig.MOD_ID, key);
	}

	static Boolean getBoolean(String key) {
		return LunaSettings.getBoolean(PiratePatConfig.MOD_ID, key);
	}

	static String getString(String key) {
		return LunaSettings.getString(PiratePatConfig.MOD_ID, key);
	}
}
