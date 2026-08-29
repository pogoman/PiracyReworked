package piratepat;

import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction.ShipSaleInfo;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.impl.items.BlueprintProviderItem;
import com.fs.starfarer.api.campaign.listeners.ColonyInteractionListener;
import com.fs.starfarer.api.impl.campaign.CoreCampaignPluginImpl;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.shared.PlayerTradeDataForSubmarket;
import com.fs.starfarer.api.util.Misc;

/**
 * Feeds the war chest from black market transactions. Trades count regardless
 * of transponder state - the authorities may not see you, but the pirates are
 * the counterparty. Values are actual demand-adjusted credits (vanilla's own
 * price computation), weighted by what the goods are worth to a war effort,
 * with recent opposing transactions netted out so wash-trading contributes
 * nothing.
 */
public class BlackMarketListener implements ColonyInteractionListener {

	public void reportPlayerOpenedMarket(MarketAPI market) {}
	public void reportPlayerClosedMarket(MarketAPI market) {}
	public void reportPlayerOpenedMarketAndCargoUpdated(MarketAPI market) {}

	public void reportPlayerMarketTransaction(PlayerMarketTransaction transaction) {
		if (!PiratePatConfig.enabled()) return;
		if (transaction == null || transaction.getSubmarket() == null) return;
		if (transaction.getSubmarket().getPlugin() == null) return;
		if (!transaction.getSubmarket().getPlugin().isBlackMarket()) return;

		MarketAPI market = transaction.getMarket();
		String submarketKey = market.getId() + "_" + transaction.getSubmarket().getSpecId();

		float total = 0f;

		for (CargoStackAPI stack : transaction.getSold().getStacksCopy()) {
			if (stack.getSize() <= 0) continue;
			String itemKey = stack.getType().name() + "_" + String.valueOf(stack.getData());
			float counted = PiratePatData.countSold(submarketKey, itemKey, stack.getSize());
			if (counted < 1) continue;

			float value = PlayerTradeDataForSubmarket.computePriceOfHavingAlreadySold(
					market, stack.getType(), stack.getData(), stack.getBaseValuePerUnit(), counted);
			total += value * weightFor(stack);

			if (stack.isSpecialStack() && stack.getPlugin() instanceof BlueprintProviderItem) {
				total += PiratePatConfig.blueprintBonus() * counted;
			}
		}

		for (CargoStackAPI stack : transaction.getBought().getStacksCopy()) {
			if (stack.getSize() <= 0) continue;
			String itemKey = stack.getType().name() + "_" + String.valueOf(stack.getData());
			float counted = PiratePatData.countBought(submarketKey, itemKey, stack.getSize());
			if (counted < 1) continue;

			float value = PlayerTradeDataForSubmarket.computePriceOfHavingAlreadyBought(
					market, stack.getType(), stack.getData(), stack.getBaseValuePerUnit(), counted);
			total += value * PiratePatConfig.buyWeight();
		}

		for (ShipSaleInfo info : transaction.getShipsSold()) {
			String hullId = info.getMember().getVariant().getHullSpec().getHullId();
			float counted = PiratePatData.countSold(submarketKey, "SHIP_" + hullId, 1f);
			if (counted <= 0) continue;
			total += info.getPrice() * counted * PiratePatConfig.shipWeight();
		}

		for (ShipSaleInfo info : transaction.getShipsBought()) {
			String hullId = info.getMember().getVariant().getHullSpec().getHullId();
			float counted = PiratePatData.countBought(submarketKey, "SHIP_" + hullId, 1f);
			if (counted <= 0) continue;
			total += info.getPrice() * counted * PiratePatConfig.buyWeight();
		}

		if (total > 0) {
			PiratePatData.addPlayerContribution(total, market.getName());
			WarChestIntel.ensureAdded();
			accrueBounty(market, total);
		}
	}

	/**
	 * The market's owning faction quietly adds the value you fed the pirates
	 * to a personal bounty on your head - but only to the extent their port
	 * authority actually suspects you. Uses vanilla's live smuggling
	 * suspicion (the black market tooltip's level): transponder-off trading
	 * generates none, so careful smugglers stay off the wanted lists. Below
	 * the floor nothing accrues; attribution scales to 100% at the "full"
	 * suspicion level. Pirates don't bounty their patron, and you can't
	 * bounty yourself.
	 */
	private static void accrueBounty(MarketAPI market, float amount) {
		if (!PiratePatConfig.bountyEnabled()) return;
		if (market.getFaction() == null) return;
		if (market.getFaction().isPlayerFaction()) return;
		String factionId = market.getFactionId();
		if (Factions.PIRATES.equals(factionId)) return;

		float suspicion = CoreCampaignPluginImpl.computeSmugglingSuspicionLevel(market);
		if (suspicion < PiratePatConfig.bountySuspicionFloor()) return;
		float full = Math.max(0.01f, PiratePatConfig.bountySuspicionFull());
		amount *= Math.min(1f, suspicion / full);
		if (amount <= 0) return;

		float before = PiratePatData.getBounty(factionId);
		PiratePatData.addBounty(factionId, amount);
		PersonalBountyIntel.ensureAdded();
		if (before <= 0f) {
			// first accrual for this faction: a quiet ledger line for provenance,
			// so a bounty never seems to appear from nowhere. It stays dormant
			// (hidden, no notification) until BountyHunterManager judges it worth
			// hunting - that transition is where the player is alerted.
			PiratePatData.addLedger(Misc.ucFirst(market.getFaction().getDisplayName())
					+ " takes note of your black market dealings at " + market.getName(), 0f);
		}
	}

	private static float weightFor(CargoStackAPI stack) {
		if (stack.isWeaponStack() || stack.isFighterWingStack()) {
			return PiratePatConfig.weaponWeight();
		}
		if (stack.isCommodityStack()) {
			String id = stack.getCommodityId();
			if (Commodities.ORE.equals(id) || Commodities.RARE_ORE.equals(id)) {
				return PiratePatConfig.oreWeight();
			}
		}
		return PiratePatConfig.commodityWeight();
	}
}
