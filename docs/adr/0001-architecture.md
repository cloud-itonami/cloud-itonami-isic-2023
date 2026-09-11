# ADR-0001: SoapAdvisor ⊣ Soap & Detergent Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-2023` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-2023` publishes an OSS blueprint for soap-and-
detergent, cleaning-and-polishing-preparation, perfume, and toilet-
preparation (cosmetics) **plant operations coordination** (production-
batch product-type/weight/off-spec-rate/fragrance-allergen data
logging, saponification/mixing/formulation-kettle and filling-line
maintenance scheduling, safety-concern flagging, and outbound shipment
coordination). Like every actor in this fleet, the blueprint alone is
not an implementation: this ADR records the governed-actor
architecture that promotes it to real, tested code, following the
same langgraph StateGraph + independent Governor + Phase 0->3 rollout
pattern established across the cloud-itonami fleet.

The closest domain analog is `cloud-itonami-isic-2013` (Manufacture of
plastics and synthetic rubber in primary forms): both are back-office
coordination actors for a fixed processing PLANT with heavy
manufacturing equipment and a real physical safety dimension, and both
share the same four-op shape (`:log-production-batch`/
`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment`)
and the same two-entity verified/registered gate structure (equipment
for maintenance scheduling, batch for shipment coordination). The two
verticals are, however, distinct plants with distinct hazard AND
regulatory profiles: 2013's hazard is chemical-process (monomer
exposure, exothermic runaway-reaction risk during polymerization),
while 2023's hazard is caustic-alkali/surfactant handling during
saponification/mixing AND a genuine fragrance-allergen labeling
regulatory obligation (EU Regulation (EC) No 1223/2009 Annex III) that
2013 has no analog of. This build mirrors 2013's architecture closely
(itself informed by `cloud-itonami-isic-2220`'s Plastics-products
actor) but adapts the hazard profile, equipment/product vocabulary,
and adds one genuinely new domain-specific governor check for the
fragrance-allergen disclosure obligation: 2023's permanent
equipment-actuation block guards a formulation/filling LINE
(`:actuate-line?`) rather than a polymerization REACTOR
(`:actuate-reactor?`); 2023's production-batch record declares a
`:product-type` (spanning soap, detergents, cleaning/polishing
preparations, perfumes, and toilet preparations, per ISIC 2023's own
combined scope) and an `:off-spec-rate-percent`, plus an OPTIONAL
`:fragrance-allergens` vector that -- when present on a fragrance-
bearing product type -- must be accompanied by an independently
re-verified `:allergen-labeling-complete? true` flag before the batch
patch may commit.

This vertical has NO pre-existing `kotoba-lang/soapmfg`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic — pure functions in
`soapmfg.registry` (equipment/batch verification, shipment-weight
recompute, product-type validation, off-spec-rate plausibility
validation, fragrance-allergen-labeling completeness validation) are
re-verified independently by the governor, the same "ground truth,
not self-report" discipline established across prior actors (most
directly `cloud-itonami-isic-2013`'s `resinmfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:soap-detergent-plant-operations-governor`, is grep-verified UNIQUE
fleet-wide (`gh search code "soap-detergent-plant-operations-governor"
--owner cloud-itonami`, zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external soap/detergent/cosmetics-manufacturing capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
soap-and-detergent/cleaning-preparation/perfume/toilet-preparation
vertical has NO pre-existing capability library to wrap. The
equipment/batch-verification / shipment-weight / product-type /
off-spec-rate / fragrance-allergen-labeling validation functions live
as pure functions in `soapmfg.registry` and are re-verified
independently by `soapmfg.governor` — the same "ground truth, not
self-report" discipline established across prior actors (most
directly `cloud-itonami-isic-2013`'s `resinmfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of soap/detergent/
cleaning-preparation/perfume/toilet-preparation plant operations. It
does NOT:
- Control saponification/mixing/formulation-kettle or filling-line equipment directly
- Make plant-safety or product-safety decisions (exclusive to the human plant supervisor)
- Actuate the formulation/filling line

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority —
it is a proposal-screening and documentation layer.

**CRITICAL SAFETY BOUNDARY**: soap/detergent/cleaning-preparation/
perfume/toilet-preparation manufacturing is a safety-critical and
regulated domain (caustic-alkali/surfactant chemical hazard,
fragrance-allergen labeling obligation, microbial-contamination risk
in unpreserved formulations, heavy material handling). Safety-concern
flagging NEVER auto-commits. All safety concerns escalate immediately
to human review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (chemical-hazard concern, allergen-labeling
concern, microbial-contamination concern, equipment-safety concern,
crew fatigue) ALWAYS escalates, never auto-commits. This is not a
"low-stakes proposal" — it is a circuit-breaker that must reach human
authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-2013`, this vertical has TWO entity kinds
each gating a different op: `:schedule-maintenance` independently
verifies the referenced **equipment** unit's own `:verified?`/
`:registered?` fields; `:coordinate-shipment` independently verifies
the referenced **batch**'s own `:verified?`/`:registered?` fields.
Both are the same "plant/batch record must be independently
verified/registered before any action" HARD invariant applied to the
two distinct record kinds this domain actually has.
`:coordinate-shipment` additionally independently recomputes whether a
batch's own recorded shipped-to-date weight plus the proposal's own
claimed weight would exceed the batch's own recorded production
weight — never taken on the advisor's self-report.

### Decision 5: Fragrance-allergen-labeling completeness — a new independently-verified check

Unlike `cloud-itonami-isic-2013` (no analogous regulatory disclosure
obligation), this vertical adds an eleventh governor check:
`:log-production-batch` INDEPENDENTLY re-derives the effective product
type (patch's own `:product-type`, else the batch's already-recorded
type) and, when that type ordinarily bears a fragrance AND the patch
cites one or more of the 26 EU Regulation (EC) No 1223/2009 Annex III
designated fragrance allergens, requires the patch's own
`:allergen-labeling-complete?` flag to independently be `true` — a
patch that merely claims allergens were disclosed "elsewhere" or
omits the flag entirely is HARD-held. This mirrors the "ground truth,
not self-report" discipline every other governor check in this fleet
establishes, applied to a genuinely new domain-specific regulatory
fact this vertical's own product mix introduces.

### Decision 6: HARD invariants (no override)

Four HARD governor invariants (elaborated into eleven concrete checks
in `soapmfg.governor`, mirroring `cloud-itonami-isic-2013`'s own
elaboration of its HARD invariants into concrete checks, plus one new
domain-specific check per Decision 5) block proposals and cannot be
overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's weight must independently recompute within the batch's own logged production weight
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct formulation/filling-line-equipment control or line actuation is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Soap/detergent/cleaning-preparation/perfume/toilet-preparation
plant operations back-office now has a documented, governed, auditable
coordination layer that funnels all decisions through independent
validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into eleven concrete governor checks) protect against scope creep into
unauthorized equipment operation, line actuation, or incomplete
regulatory disclosure. Safety concerns are a circuit-breaker, not a
threshold.

(+) Safety-critical and regulatory discipline is explicit:
safety-concern flagging cannot be rate-limited, suppressed, or
auto-decided by phase gate; fragrance-allergen labeling completeness
is independently re-verified, never taken on trust. Human review is
mandatory for the former.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation and formulation/filling-line
operation remain human-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch, or an authoritative
fragrance-allergen ingredient database) — this is a standalone
coordinator blueprint; the closed `known-fragrance-allergens` set is a
representative subset of the EU's 26 designated allergens, not an
exhaustive multi-jurisdiction ingredient database.

## Verification

- `cloud-itonami-isic-2023`: `kbb -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `kbb -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-weight-exceeded, line-actuate-blocked,
  already-scheduled, invalid-product-type, invalid-off-spec-rate,
  fragrance-allergen-labeling-incomplete).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `kbb -M:test` resolves offline inside the monorepo checkout.
