# Piracy Reworked

A standalone mod for Starsector 0.98a. The black market is pirate
infrastructure - and every credit that moves through the underworld is revenue
for it. In vanilla, trading with the transponder off has literally zero
consequences, and pirates fade into irrelevance the moment you stop needing
the black market; this mod doesn't punish you for smuggling, it makes the
pirates *spend your money* - and gives a rich late-game captain reasons to
keep dealing with them.

## The war chest

Every black market transaction - buys and sells, transponder on or off (the
authorities may not see you, but the pirates are the counterparty) - feeds a
sector-wide pirate war chest. Sales are valued at actual demand-adjusted
credits and weighted by usefulness to a war effort:

- Weapons and fighter LPCs: x3
- Ships: x2
- Commodities (incl. drugs/organs): x1
- Raw ores: x0.5
- Blueprints: flat bonus on top of the vanilla behavior (pirates learn them)

Wash-trading contributes nothing: opposing buys/sells of the same goods at the
same submarket are netted against each other.

**A closed-loop economy.** Operating bases earn passive income (tuned to
sustain their vanilla raid cadence, no more). Launching a raid *costs* the
chest credits per fleet point - a broke underworld doesn't sail. A successful
raid pays spoils back with interest; a repelled raid is a pure loss.

**Bases are priced by tier** (tier 1 ~94k up to tier 5 ~614k at defaults),
and the pirates decide what to buy by circumstance: with their footing gone
they rebuild it - the best tier affordable while reserving enough for another
starter; established, they save toward a base one tier above their current
best. A flush chest chains builds every couple of weeks, up to a cap far
above vanilla's 3.

**The strategic grammar:**
- *Smuggle* and the underworld grows - left unchecked, it snowballs.
- *Repel raids* and it stagnates at break-even, unable to expand.
- *Hunt bases* and it shrinks: each kill destroys the pirates' investment and
  their income, and slows the tithe to a trickle.

## The underworld tithe

The underworld also collects a monthly cut of the *sector's* illegal trade -
the same commodity flows vanilla routes as smuggling fleets, counted at both
ends of every run (the smuggler pays protection at the origin, the fence
takes a cut at the destination). This is income that scales with the size of
the economy instead of your personal habits, so pirates stay financed late
game even if you never fence another crate. It is sector income, never your
contribution - it steadily *dilutes* your share of the underworld's books.

**Your colonies are clients whether you like it or not.** Your markets'
share of the tithe is shown as its own intel line - a big colony's drug and
organ demand is met by smuggling into it. Free ports (anyone's, yours
included) fence at a multiplier: a free port is where everyone else's
contraband becomes legal cargo, so Free Port finally has an underworld price
tag.

With zero operating bases only a configurable trickle flows - eradication
buys years of quiet, but piracy follows interstellar civilization, and the
trickle eventually re-founds a base.

## The broker

Underworld-tagged **contacts** offer, alongside their regular job list, to
have things *sourced*: goods no legal market sells.

- **Industrial equipment - real theft.** The menu lists colony items
  actually installed at NPC colonies, scanned live from the economy, each
  entry naming its source. The price carries a danger premium scaled to the
  source colony's real defenses, and a source the network's best base can't
  crack shows greyed out - feed the war chest and the menu opens up. On
  success the item is *removed from the victim colony's industry*: theft
  with permanent consequences, not manufacture.
- **Blueprints.** A small rotating monthly menu of rare blueprints
  "liberated" from faction archives - copies, and of course the pirates keep
  one for themselves.

Terms: full deposit up front, straight into the war chest (it counts as your
personal contribution at full weight). Fulfillment is a **real raid** by an
operating war-chest base against the system holding the goods - resolved by
the actual raid sim against the target's actual defenses. Repelled raid:
half back. The broker deals even when the underworld has no bases - the
deposit then quite literally finances the reconstruction that will fulfill
it. Delivery lands in storage at the market where you ordered.

Access is gated the way vanilla gates contact missions: the option appears
at **Favorable** rep with the contact, and what their network can reach
scales with the contact's **importance** - the crown jewels (pristine
nanoforge, synchrotron) need a well-placed contact.

**Vanilla underworld jobs count too.** Money handed to underworld figures
through vanilla contact missions - custom production markups, hand-me-down
freighters - feeds the chest and your contribution the same way.

## Fading wariness (respite piercing)

Piracy Respite - vanilla's permanent reward for defeating the pirate colony
crisis - says pirates are *wary* of attacking you. Wariness fades when the
war chest overflows with your own money: once your lifetime contribution and
your share of the underworld's income cross the configured thresholds
(500k / 25% by default), chest-funded bases resume preying on your colonies -
the vanilla pirate activity condition (accessibility/stability drain,
ambient fleets), never raids. Your share dilutes as the underworld earns its
own money (tithe included), so laying off eventually restores the respite.
Hunting pirates also offsets your ledger: destroyed bases and fleets reduce
your lifetime contribution.

## A price on your head

Each faction whose markets you smuggle through tracks the value you fed the
pirates there and posts a *personal* bounty - no faction rep consequences,
because nothing can be proven well enough for open hostilities. Accrual is
gated on the market's vanilla smuggling suspicion level: transponder-off
trading generates none; brazen trading gets you posted. Commissioning a
broker raid adds a cut of the deposit to the victim's ledger - a raid is
loud.

The ledgers are per faction, but the hunters are freelancers who collect
from every poster at once - everything on their side works off the
**combined** price:

- The pool activates once it clears the floor (50k default) *and* funds
  hunters worth taking on your current fleet - a formidable fleet deters
  hunters, but the deterrence is uncapped in reverse: a big enough price
  always finds takers.
- While too small to draw hunters, every ledger *festers* at a higher rate
  (10%/month default) - ignoring a small price only lets it ripen. Active
  bounties compound at 2%/month.
- A throwaway decoy fleet *freezes* the bounty instead - the contract is
  your fleet, destroyed, and nobody burns a hunt on a worthless prize.
- Hunter fleets scale with the pool (1 FP per 1,000 credits, capped 200 FP
  per fleet, up to 3 concurrent), and destroying one raises *every*
  poster's bounty. Fighting hunters carries no rep impact either way.
- Buy your way off a faction's list from the intel screen at a 25% premium.

## Everything else

- **Scaling defenses**: tier 3+ bases field additional medium/heavy patrols
  and bigger fleets, plus a tier-scaled garrison stationed on the base - a
  tier 5 base is a fortress.
- **Machine factions excluded**: the Threat, Remnants, Omega and friends
  neither pay the tithe, nor get raided for commissions, nor post bounties.
- **Kept vanilla**: raid strength/frequency per base, tier progression,
  station restoration, the 6-18 month respawn freeze after a base kill, the
  first-year raid grace. Removed: vanilla's rule that every destroyed base
  makes future bases spawn stronger.
- **The intel screen** ("Pirate War Chest") is a balance sheet: chest,
  income streams (including the tithe and your colonies' share), operating
  bases, savings toward the next base, the recent ledger - and exactly how
  much of the pirate war economy *you* bankrolled.

## Compatibility

- Save-compatible to add mid-campaign: existing raiding bases are adopted
  as-is; the chest seeds accounting for them.
- Bases from the colony-crisis system (player-related bases) are untouched,
  as are Hostile Activity, Kanta's Protection, and Piracy Respite.
- Overrides the `cpc`/`hmdf` rows of `person_missions.csv` with subclasses
  (behavior unchanged, payments credited) - flag if another mod edits those.
- Configurable via LunaLib (optional); bundled settings.json is the fallback.

## Building

`compile.ps1` (requires a JDK on PATH or JAVA_HOME). Output: `jars/PiratePat.jar`.
