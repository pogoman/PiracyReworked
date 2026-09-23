package piratepat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MonthlyReport;
import com.fs.starfarer.api.campaign.econ.MonthlyReport.FDNode;
import com.fs.starfarer.api.campaign.listeners.EconomyTickListener;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.impl.campaign.intel.MessageIntel;
import com.fs.starfarer.api.impl.campaign.shared.SharedData;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI.TooltipCreator;
import com.fs.starfarer.api.util.Misc;

/**
 * The monthly half of the debt: interest every month, and - once the player
 * has signed for terms - an installment line in the vanilla monthly report
 * that the income system actually charges against their credits.
 *
 * <p>Registered TRANSIENT by PiratePatModPlugin:
 * <code>sector.getListenerManager().addListener(new DebtInstallmentTicker(), true)</code>.
 * A transient listener is never written into the save, so the listener
 * registration itself is safe.
 *
 * <p><b>THIS CLASS MUST NEVER GAIN AN INSTANCE FIELD, AND MUST NEVER BE
 * DELETED OR RENAMED.</b> The transient registration is not the only path
 * into a save. Setting <code>FDNode.tooltipCreator = this</code> puts a
 * reference to this object inside a MonthlyReport, and both MonthlyReports
 * live inside SharedData, which lives in sector persistent data under
 * CoreScript.SHARED_DATA_KEY. Any save taken in the month a line was booked
 * therefore carries the string "piratepat.DebtInstallmentTicker" - and
 * 0.98a-RC8 does not enable XStream's ignoreUnknownElements, so a class name
 * or a field element welded into a save can never be taken back out. The
 * class is deliberately field-free so its serialized form is an empty
 * element, which nothing can break. Everything it needs to remember lives in
 * PirateDebt's persistent keys or in the node's own plain-JDK tooltipParam.
 *
 * <p>Two design points that are load-bearing and easy to undo by accident:
 *
 * <p>Critique F4 - <b>book the FULL installment as node.upkeep, never
 * min(installment, cash)</b>. Booking only what the player can afford makes
 * the line always read as paid, so getBooked() never differs from what was
 * owed, recordMiss() can never fire, and debtMissesBeforeSale - the entire
 * road to the pirate act for a player who signs terms and never fires a shot
 * - is dead code. Book the whole figure, let vanilla clamp and carry the
 * shortfall, and reconcile afterwards.
 *
 * <p>The node is written ONLY on the last economy iteration of the month, and
 * upkeep is ASSIGNED, never accumulated with +=. reportEconomyTick runs many
 * times a month; a += would bill the player the installment once per
 * iteration.
 */
public class DebtInstallmentTicker implements EconomyTickListener, TooltipCreator {

	/** Child id of our line under the vanilla "Fleet" node. */
	public static final String NODE_ID = "piratepat_debtInstallment";

	/**
	 * Book this month's installment, in full, on the last economy iteration.
	 *
	 * <p>Guarded on the last iteration for two reasons: it is the closest
	 * point to month end, so the balance and the interest rate are the ones
	 * the player is actually charged on, and it means one assignment per
	 * month rather than ten.
	 */
	public void reportEconomyTick(int iterIndex) {
		int lastIter = (int) Global.getSettings().getFloat("economyIterPerMonth") - 1;
		if (iterIndex != lastIter) return;
		if (!PiratePatConfig.enabled() || !PiratePatConfig.debtEnabled()) return;
		if (!PirateDebt.hasDebt() || !PirateDebt.hasPlan()) return;

		float installment = PirateDebt.installment();
		if (installment < 1f) return;

		float balanceBefore = PirateDebt.principal();
		float interest = balanceBefore * PirateDebt.monthlyRate();
		if (interest < 0f) interest = 0f;
		if (interest > installment) interest = installment;
		float againstPrincipal = installment - interest;

		// F4: the FULL figure, so month end has something to reconcile against
		PirateDebt.setBooked(installment);

		MonthlyReport report = SharedData.getData().getCurrentReport();

		// CoreScript names the Fleet node in its own reportEconomyTick, but
		// listener dispatch order relative to CoreScript is not defined and
		// CoreScript returns early during the tutorial. An unnamed parent
		// renders as a blank row, so name it defensively if nobody has.
		FDNode fleetNode = report.getNode(MonthlyReport.FLEET);
		if (fleetNode.name == null) {
			fleetNode.name = "Fleet";
			fleetNode.custom = MonthlyReport.FLEET;
			fleetNode.tooltipCreator = report.getMonthlyReportTooltip();
		}

		FDNode node = report.getNode(fleetNode, NODE_ID);
		node.upkeep = installment;
		node.name = nodeName();
		node.icon = Global.getSettings().getSpriteName("income_report", "generic_expense");
		node.tooltipCreator = this;
		// a primitive float array - a plain JDK type, safe to serialize inside
		// the report, and it keeps the tooltip honest after the month rolls
		// over and the live balance no longer matches what was billed
		node.tooltipParam = new float[] { installment, interest, againstPrincipal, balanceBefore };

		if (PiratePatConfig.debugLogging()) {
			PirateDebt.log.info("Booked installment " + (int) installment
					+ " against balance " + (int) balanceBefore);
		}
	}

	/**
	 * Month end: charge the interest, retire what the player could actually
	 * cover, and either reset the miss counter or take the late fee.
	 *
	 * <p>Interest is applied BEFORE the payment is retired. installment() is
	 * principal * fraction + principal * rate, so adding the month's interest
	 * and then removing the whole installment makes the balance fall by
	 * exactly the fraction, every month, whatever the rate - which is what
	 * makes a plan terminate.
	 */
	public void reportEconomyMonthEnd() {
		float booked = PirateDebt.getBooked();
		boolean live = PiratePatConfig.enabled() && PiratePatConfig.debtEnabled();

		// The debt closed out between the last iteration and month end - a
		// seizure, or paying a collector off in full - or the whole system
		// was switched off in that window. Either way the line we booked is
		// stale and must not be charged.
		if (!live || !PirateDebt.hasDebt()) {
			if (booked >= 1f) cancelBookedCharge();
			if (booked >= 1f || PirateDebt.hasDebt()) PirateDebt.setBooked(0f);
			return;
		}

		PirateDebt.applyInterest();

		// no plan running: interest only, which is the whole of the pressure
		// on a player who has not signed for anything
		if (booked < 1f) return;

		float cash = creditsBackingThisMonth(booked);
		if (cash < 0f) cash = 0f;
		float paid = Math.min(booked, cash);

		if (paid >= 1f) {
			// reduceBalance settles, announces and stands every collector down
			// on its own if this finished the debt - do not duplicate any of it
			PirateDebt.reduceBalance(paid);
			PiratePatData.addLedger("Monthly installment against the receivable in your name",
					paid);
		}
		if (!PirateDebt.hasDebt()) return;

		if (paid >= booked - 1f) {
			PirateDebt.recordSuccess();
		} else {
			float shortfall = booked - paid;
			boolean sold = PirateDebt.recordMiss(shortfall);
			reportMissedPayment(shortfall, sold);
		}
	}

	/**
	 * What the player had to put against the installment this month.
	 *
	 * <p>Deliberately NOT SharedData.getPreviousReport().getDebt(), which is
	 * the player's WHOLE budget shortfall: a colony running a deficit would
	 * compound a late fee on the debt every month through no fault of the
	 * debt. The measure is simply whether the player was carrying the
	 * installment when the report charged it - which under-reports (a rich
	 * player who shorts us because of colony upkeep is never marked missed)
	 * and that asymmetry is intentional and in the player's favour.
	 *
	 * <p>The awkward part is ordering. CoreScript.reportEconomyMonthEnd is a
	 * CampaignEventListener and we are an EconomyTickListener; the two are
	 * dispatched down different paths and nothing documents which runs first.
	 * So detect whether CoreScript has already charged the report this month
	 * and, if it has, rebuild the pre-charge balance exactly from its own
	 * arithmetic rather than guessing.
	 */
	protected float creditsBackingThisMonth(float booked) {
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player == null || player.getCargo() == null) return 0f;
		float credits = player.getCargo().getCredits().get();

		if (!coreReportAlreadyCharged()) return credits;

		// CoreScript ran first. It did:
		//     total      = root.totalIncome - root.totalUpkeep
		//     newCredits = credits + total
		//     if (newCredits < 0) { setDebt(abs(newCredits)); newCredits = 0; }
		// which inverts to credits = newCredits - total - debt in both the
		// clamped and the unclamped case.
		MonthlyReport prev = SharedData.getData().getPreviousReport();
		float total = prev.getRoot().totalIncome - prev.getRoot().totalUpkeep;
		return credits - total - prev.getDebt();
	}

	/**
	 * Whether CoreScript has already rolled the report over and taken the
	 * month's money. It stamps the rolled-over report with the current clock
	 * timestamp as the last thing it does before charging, so a stamp from
	 * this instant means it has run and a stamp from a month ago means it has
	 * not.
	 */
	protected boolean coreReportAlreadyCharged() {
		MonthlyReport prev = SharedData.getData().getPreviousReport();
		long stamp = prev.getTimestamp();
		if (stamp <= 0L) return false;
		return Global.getSector().getClock().getElapsedDaysSince(stamp) < 0.5f;
	}

	/**
	 * Zero a line we booked for a debt that no longer exists, so the player is
	 * not billed one last installment on a balance they already cleared.
	 *
	 * <p>Only reachable while CoreScript has not yet charged; once it has, the
	 * money is gone and there is nothing honest left to do about it. Reads the
	 * child map directly rather than calling getNode, which CREATES nodes on
	 * the way down and would leave an unnamed empty row in the report.
	 */
	protected void cancelBookedCharge() {
		if (coreReportAlreadyCharged()) return;
		MonthlyReport report = SharedData.getData().getCurrentReport();
		FDNode root = report.getRoot();
		FDNode fleetNode = root.getChildren().get(MonthlyReport.FLEET);
		if (fleetNode == null) return;
		FDNode node = fleetNode.getChildren().get(NODE_ID);
		if (node == null) return;
		node.upkeep = 0f;
		node.name = "Debt repayment - settled";
		node.tooltipParam = null;
		node.tooltipCreator = null;
	}

	/** The line's name, which follows the holder - so it renames itself after a sale. */
	protected String nodeName() {
		if (PirateDebt.isPirateHeld()) return "Debt repayment - pirate creditors";
		return "Debt repayment - collection house";
	}

	/**
	 * Tell the player they came up short, since a late fee that only shows up
	 * as a bigger number later reads as a bug. The forced sale, if this miss
	 * triggered one, announces itself from PirateDebt.sellToPirates - do not
	 * repeat it here.
	 */
	protected void reportMissedPayment(float shortfall, boolean sold) {
		MessageIntel msg = new MessageIntel();
		msg.addLine("Missed a payment on your debt", Misc.getNegativeHighlightColor());
		msg.addLine(BaseIntelPlugin.BULLET + "Short by %s. Half the shortfall is added back.",
				Misc.getTextColor(), new String[] { Misc.getDGSCredits(shortfall) },
				Misc.getHighlightColor());
		msg.addLine(BaseIntelPlugin.BULLET + "Balance: %s", Misc.getTextColor(),
				new String[] { Misc.getDGSCredits(PirateDebt.principal()) },
				Misc.getHighlightColor());
		if (!sold && PirateDebt.isEnforcing()) {
			msg.addLine(BaseIntelPlugin.BULLET + "They have stopped writing letters.",
					Misc.getTextColor());
		}
		FactionAPI holder = PirateDebt.holderFaction();
		if (holder != null) msg.setIcon(holder.getCrest());
		Global.getSector().getCampaignUI().addMessage(msg);
	}

	// ------------------------------------------------------------------
	// TooltipCreator - all three methods, or it does not compile
	// ------------------------------------------------------------------

	/**
	 * Breaks the month's bill into interest and principal.
	 *
	 * <p>Reads the figures out of tooltipParam rather than off PirateDebt
	 * wherever it can: the tooltip is drawn when the player hovers the row,
	 * which for last month's report can be a month after the line was booked,
	 * by which time the live balance no longer matches what was billed.
	 */
	public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
		float installment;
		float interest;
		float againstPrincipal;
		float balanceBefore;
		if (tooltipParam instanceof float[] && ((float[]) tooltipParam).length >= 4) {
			float[] p = (float[]) tooltipParam;
			installment = p[0];
			interest = p[1];
			againstPrincipal = p[2];
			balanceBefore = p[3];
		} else {
			balanceBefore = PirateDebt.principal();
			installment = PirateDebt.installment();
			interest = balanceBefore * PirateDebt.monthlyRate();
			if (interest > installment) interest = installment;
			againstPrincipal = installment - interest;
		}

		tooltip.addPara("Terms signed against a receivable held by %s. The balance when this "
				+ "month's bill was drawn was %s.", 0f, Misc.getHighlightColor(),
				PirateDebt.holderName(), Misc.getDGSCredits(balanceBefore));
		tooltip.addPara("Interest this month: %s", 10f, Misc.getHighlightColor(),
				Misc.getDGSCredits(interest));
		tooltip.addPara("Against the principal: %s", 3f, Misc.getHighlightColor(),
				Misc.getDGSCredits(againstPrincipal));

		if (PirateDebt.missed() > 0) {
			tooltip.addPara("You have missed %s consecutive payments. Miss "
					+ "them for long enough and the paper is sold on.", 10f,
					Misc.getNegativeHighlightColor(), "" + PirateDebt.missed());
		} else {
			tooltip.addPara("Come up short and half of whatever you did not cover is added "
					+ "back to the balance.", 10f);
		}

		if (PirateDebt.hasDebt()) {
			tooltip.addPara("Outstanding now: %s", 10f, Misc.getHighlightColor(),
					Misc.getDGSCredits(PirateDebt.principal()));
		} else {
			tooltip.addPara("Settled. Nobody is coming to collect.",
					Misc.getPositiveHighlightColor(), 10f);
		}
	}

	public float getTooltipWidth(Object tooltipParam) {
		return 450f;
	}

	public boolean isTooltipExpandable(Object tooltipParam) {
		return false;
	}
}
