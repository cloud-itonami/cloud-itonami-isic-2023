# cloud-itonami-isic-2023: Manufacture of soap and detergents, cleaning and polishing preparations, perfumes and toilet preparations

Open Business Blueprint for **ISIC Rev.5 2023**: manufacture of soap and detergents, cleaning and polishing preparations, perfumes and toilet preparations — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **plant operations**: production-batch data logging (product-type/weight/off-spec-rate/fragrance-allergens), saponification/mixing/formulation-kettle and filling-line maintenance scheduling, safety-concern flagging, and outbound shipment coordination.

This repository designs a forkable OSS business for soap/detergent/
cleaning-preparation/perfume/toilet-preparation plant operations: run
by a qualified operator so a plant keeps its own operating records
instead of renting a closed SaaS.

## Scope: one plant-operations shape, four related product families

ISIC 2023 covers a single manufacturing shape spanning four related
product families: **soap** (bar/liquid), **detergents** (laundry/
dishwashing), **cleaning and polishing preparations** (household/
industrial cleaners, furniture/metal polish), and **perfumes and
toilet preparations** (perfume/cologne, cosmetic toilet preparations).
Every family shares the same saponification/mixing/formulation-kettle
+ filling-line plant shape and the same back-office coordination
actor design (verified/registered equipment+batch gate, permanent
equipment-actuation block) — this repo does not split them into
separate actors. This is distinct from a chemical-process primary-
forms plant (e.g. `cloud-itonami-isic-2013`) or a downstream molding
plant (e.g. `cloud-itonami-isic-2220`): this plant's own hazard
profile is chemical (caustic-alkali/surfactant handling during
saponification/mixing) AND regulatory (fragrance-allergen label
disclosure for perfume/toilet-preparation/most soap-and-detergent
products, EU Regulation (EC) No 1223/2009 Annex III), not a
polymerization-reactor or molding-line hazard.

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — product-type/weight/off-spec-rate/fragrance-allergen data logging (administrative, not an operational decision)
- `:schedule-maintenance` — saponification/mixing/formulation-kettle or filling-line maintenance scheduling proposal
- `:flag-safety-concern` — surface a chemical-hazard/allergen-labeling/microbial-contamination concern (always escalates)
- `:coordinate-shipment` — outbound product shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical, regulated domain**
(saponification/mixing/formulation-kettle and filling-line equipment,
caustic-alkali/surfactant chemical hazard, fragrance-allergen labeling
obligation, microbial-contamination risk in unpreserved formulations):

- Does NOT control saponification/mixing/formulation-kettle or filling-line equipment directly
- Does NOT make plant-safety or product-safety decisions (that's the plant supervisor's exclusive human authority)
- Does NOT actuate the formulation/filling line (human plant supervisor decides)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`soapmfg.operation/build`, a langgraph-clj StateGraph):
1. **`soapmfg.advisor`** (sealed intelligence node, `SoapAdvisor`): proposes decisions only, never commits
2. **`soapmfg.governor`** (independent, `Soap & Detergent Plant Operations Governor`): validates against domain rules, re-derived from `soapmfg.registry`'s pure functions and `soapmfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct formulation/filling-line-equipment control)
     - Directly actuating the formulation/filling line (`:actuate-line? true`) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped weight past its own logged production weight (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-type` value on a production-batch patch
     - No physically implausible `:off-spec-rate-percent` value on a production-batch patch
     - A fragrance-bearing batch's own `:fragrance-allergens` disclosure must be independently confirmed complete (`:allergen-labeling-complete? true`) — EU Regulation (EC) No 1223/2009 Annex III designated-allergen labeling obligation, never taken on the advisor's self-report
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`soapmfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`soapmfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
