(ns soapmfg.registry
  "Pure-function domain logic for the soap-and-detergent, cleaning-and-
  polishing-preparation, perfume, and toilet-preparation (cosmetics)
  plant-operations coordination actor -- equipment/batch verification,
  shipment-weight recompute, product-type validation, off-spec-rate
  plausibility validation, fragrance-allergen-labeling completeness
  validation, and draft maintenance-schedule/shipment-coordination
  record construction.

  Per docs/adr/0001-architecture.md Decision 1: this vertical has NO
  pre-existing `kotoba-lang/soapmfg`-style capability library to wrap
  (verified: no such repo exists). The domain logic therefore lives
  here as pure functions, re-verified INDEPENDENTLY by
  `soapmfg.governor` -- the same 'ground truth, not self-report'
  discipline every sibling actor's own registry establishes (e.g.
  `resinmfg.registry/shipment-weight-exceeded?` from
  `cloud-itonami-isic-2013`, this actor's closest chemical-process-
  plant analog): never trust a proposal's own self-reported weight/
  status/labeling-completeness when the inputs needed to recompute it
  independently are already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real plant-operations system. It builds the DRAFT record
  a plant coordinator would keep (a scheduled maintenance window, a
  coordinated shipment), not the act of actuating a saponification/
  formulation/filling line or dispatching a real freight carrier (this
  actor NEVER does either -- see README `What this actor does NOT
  do`).

  SCOPE note: ISIC 2023 covers FOUR related product families under one
  plant-operations shape -- soap (`:bar-soap`/`:liquid-soap`),
  detergents (`:laundry-detergent`/`:dishwashing-detergent`), cleaning
  and polishing preparations (`:household-cleaner`/
  `:industrial-cleaner`/`:furniture-polish`/`:metal-polish`), and
  perfumes/toilet preparations (`:perfume`/`:cologne`/
  `:toilet-preparation`/`:cosmetic-cream`). Unlike a single-product
  plant, several of these product types (perfumes, toilet
  preparations, most soaps/detergents/cleaners) carry a genuine
  regulatory fragrance-allergen disclosure obligation -- EU Regulation
  (EC) No 1223/2009 Annex III designates 26 fragrance allergens that
  must be individually listed on the label above a concentration
  threshold. `fragrance-allergen-labeling-complete?` independently
  re-verifies that obligation the same way `shipment-weight-exceeded?`
  independently re-verifies a shipment's own claimed weight -- never
  taken on the advisor's self-report.")

;; ----------------------------- constants -----------------------------

(def valid-product-types
  "The closed set of product-type values a production-batch record may
  declare -- spanning ISIC 2023's own combined scope: soap, detergents,
  cleaning and polishing preparations, perfumes, and toilet
  preparations (cosmetics). Anything else is a fabricated/unrecognized
  product type -- the governor HARD-holds rather than let an invented
  product type pass through."
  #{;; soap
    :bar-soap :liquid-soap
    ;; detergents
    :laundry-detergent :dishwashing-detergent
    ;; cleaning and polishing preparations
    :household-cleaner :industrial-cleaner :furniture-polish :metal-polish
    ;; perfumes
    :perfume :cologne
    ;; toilet preparations (cosmetics/personal care)
    :toilet-preparation :cosmetic-cream})

(def fragrance-bearing-product-types
  "Product types that ordinarily carry an added fragrance and are
  therefore subject to fragrance-allergen labeling obligations when the
  formulation contains any of `known-fragrance-allergens` above the
  regulatory disclosure threshold. Purely-functional cleaning products
  with no fragrance component (`:industrial-cleaner`/`:metal-polish`)
  are deliberately excluded -- see README `Fragrance-allergen labeling`."
  #{:bar-soap :liquid-soap :laundry-detergent :dishwashing-detergent
    :household-cleaner :furniture-polish
    :perfume :cologne :toilet-preparation :cosmetic-cream})

(def known-fragrance-allergens
  "The 26 fragrance allergens EU Regulation (EC) No 1223/2009 Annex III
  designates for mandatory individual label disclosure above the
  regulatory concentration threshold (0.001% leave-on / 0.01% rinse-off
  products), abbreviated here to the most commonly cited subset for
  this actor's own closed-set validation (Limonene, Linalool,
  Geraniol, Citronellol, Eugenol, Coumarin, Citral, Benzyl Alcohol,
  Benzyl Benzoate, Benzyl Salicylate, Cinnamal, Hexyl Cinnamal,
  Farnesol). A patch's own `:fragrance-allergens` vector may only cite
  values from this closed set -- an invented allergen name is not a
  real disclosure."
  #{:limonene :linalool :geraniol :citronellol :eugenol :coumarin
    :citral :benzyl-alcohol :benzyl-benzoate :benzyl-salicylate
    :cinnamal :hexyl-cinnamal :farnesol})

(def valid-output-forms
  "The closed set of physical output forms this plant's own filling
  line may package -- bar/liquid/powder/gel/aerosol/cream/spray, the
  same shape every ISIC 2023 sub-category's own finished-goods take."
  #{:bar :liquid :powder :gel :aerosol :cream :spray})

(def off-spec-rate-min-percent
  "Physical floor for a batch's own off-spec/reject-rate reading (zero
  off-spec output is the best possible outcome, never negative)."
  0.0)

(def off-spec-rate-max-percent
  "Physical ceiling for a batch's own off-spec/reject-rate reading -- a
  batch cannot reject more than 100% of its own output. A reading
  above this is implausible sensor/QC data, not a real batch."
  100.0)

;; ----------------------------- equipment checks -----------------------------

(defn equipment-verified?
  "Ground-truth check: has `equipment`'s own record been marked
  verified (i.e. it has actually been inspected/commissioned and
  registered in the SSoT, not merely referenced from an unverified
  maintenance request)? A pure predicate over the equipment's own
  permanent field -- no proposal inspection needed."
  [equipment]
  (true? (:verified? equipment)))

(defn equipment-registered?
  "Ground-truth check: does `equipment`'s own record carry a
  `:registered?` true flag (i.e. it is on file in the plant's
  equipment registry)? Scheduling maintenance against equipment that
  is not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [equipment]
  (true? (:registered? equipment)))

(defn equipment-ready?
  "Combined ground-truth gate: the equipment must be both `verified?`
  AND `registered?` before ANY maintenance may be scheduled against
  it. Two independent facts on the equipment's own permanent record,
  neither inferred from the advisor's own rationale."
  [equipment]
  (and (equipment-verified? equipment) (equipment-registered? equipment)))

;; ----------------------------- batch checks -----------------------------

(defn batch-verified?
  "Ground-truth check: has `batch`'s own record been marked verified
  (i.e. its product-type/weight/off-spec-rate claims have actually
  been QC-inspected, not merely logged from an unverified intake
  patch)?"
  [batch]
  (true? (:verified? batch)))

(defn batch-registered?
  "Ground-truth check: is `batch`'s own record on file in the plant's
  production ledger? Coordinating a shipment against a batch that is
  not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [batch]
  (true? (:registered? batch)))

(defn batch-ready?
  "Combined ground-truth gate: the batch must be both `verified?` AND
  `registered?` before ANY shipment may be coordinated against it."
  [batch]
  (and (batch-verified? batch) (batch-registered? batch)))

(defn shipment-weight-exceeded?
  "Ground-truth check for a `:coordinate-shipment` proposal:
  would `shipped-to-date-kg` + `new-weight-kg` exceed `batch`'s own
  recorded `:weight-kg` (the batch's own logged production weight)?
  Needs no proposal inspection or stored-verdict lookup -- its inputs
  are permanent fields already on the batch's own record, the same
  shape every sibling actor's own cost/total-matching check uses."
  [batch new-weight-kg]
  (let [capacity (:weight-kg batch)
        so-far (:shipped-weight-kg batch 0.0)]
    (and (number? capacity)
         (number? new-weight-kg)
         (> (+ (double so-far) (double new-weight-kg)) (double capacity)))))

(defn product-type-valid?
  "Is `product-type` one of the closed, known product-type values
  (soap, detergent, cleaning/polishing preparation, perfume, or toilet
  preparation)? nil/blank is treated as invalid (a production-batch
  patch must declare a real product type, not omit it silently)."
  [product-type]
  (contains? valid-product-types product-type))

(defn off-spec-rate-valid?
  "Is `percent` a physically plausible batch off-spec/reject-rate
  reading? Rejects nil, non-numbers, negative values, and values
  beyond `off-spec-rate-max-percent` -- a fabricated or sensor-error
  reading, never let through as a real batch fact."
  [percent]
  (and (number? percent)
       (>= (double percent) off-spec-rate-min-percent)
       (<= (double percent) off-spec-rate-max-percent)))

;; ----------------------------- fragrance-allergen labeling checks -----------------------------

(defn fragrance-allergens-valid?
  "Is `allergens` (a collection) entirely drawn from the closed
  `known-fragrance-allergens` set? nil/empty is valid (a formulation
  need not declare any allergen at all) -- only an INVENTED allergen
  name is rejected."
  [allergens]
  (every? known-fragrance-allergens (or allergens [])))

(defn fragrance-allergen-disclosure-required?
  "Ground-truth check: does `product-type` ordinarily bear a fragrance
  AND does the patch's own `:fragrance-allergens` vector cite at least
  one designated allergen above the regulatory disclosure threshold?
  If both are true, the patch's own `:allergen-labeling-complete?`
  flag MUST also be true -- see
  `fragrance-allergen-labeling-incomplete?` below."
  [product-type fragrance-allergens]
  (boolean
   (and (contains? fragrance-bearing-product-types product-type)
        (seq fragrance-allergens))))

(defn fragrance-allergen-labeling-incomplete?
  "Ground-truth check for a `:log-production-batch` proposal: if this
  batch's own product type ordinarily bears a fragrance AND the patch
  declares one or more designated fragrance allergens, the patch's own
  `:allergen-labeling-complete?` flag MUST independently be `true` --
  never taken on the advisor's self-report that labeling 'will be'
  handled. `product-type` is the EFFECTIVE product type after the
  patch is applied (patch's own value if present, else the batch's
  already-recorded value -- a patch that only updates
  `:fragrance-allergens` without re-declaring `:product-type` must
  still be checked against the batch's own recorded type)."
  [effective-product-type patch]
  (let [allergens (:fragrance-allergens patch)]
    (boolean
     (and (fragrance-allergen-disclosure-required? effective-product-type allergens)
          (not (true? (:allergen-labeling-complete? patch)))))))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human plant supervisor's/shipping approver's act, not this
  actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-maintenance
  "Validate + construct the MAINTENANCE-SCHEDULE DRAFT -- a proposed
  saponification/mixing/formulation-kettle or filling-line maintenance
  window against a verified, registered piece of equipment. Pure
  function -- does not actuate the kettle/mixing/filling line or
  execute any maintenance; it builds the RECORD a plant coordinator
  would keep. `soapmfg.governor` independently re-verifies the
  equipment's own verified/registered ground truth, and permanently
  blocks any attempt to directly actuate the formulation/filling line
  (see README `Actuation`), before this is ever allowed to commit."
  [maintenance-id equipment-id sequence]
  (when-not (and maintenance-id (not= maintenance-id ""))
    (throw (ex-info "maintenance: maintenance_id required" {})))
  (when-not (and equipment-id (not= equipment-id ""))
    (throw (ex-info "maintenance: equipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "maintenance: sequence must be >= 0" {})))
  (let [maintenance-number (str "MNT-" (zero-pad sequence 6))
        record {"record_id" maintenance-number
                "kind" "maintenance-schedule-draft"
                "maintenance_id" maintenance-id
                "equipment_id" equipment-id
                "immutable" true}]
    {"record" record "maintenance_number" maintenance-number
     "certificate" (unsigned-certificate "MaintenanceSchedule" maintenance-number maintenance-number)}))

(defn register-shipment
  "Validate + construct the SHIPMENT-COORDINATION DRAFT -- a proposed
  outbound soap/detergent/cleaning-preparation/perfume/toilet-
  preparation shipment against a verified, registered production
  batch. Pure function -- does not dispatch any real freight carrier;
  it builds the RECORD a plant coordinator would keep.
  `soapmfg.governor` independently re-verifies the shipment's own
  claimed weight against `shipment-weight-exceeded?`, before this is
  ever allowed to commit."
  [shipment-id sequence]
  (when-not (and shipment-id (not= shipment-id ""))
    (throw (ex-info "shipment: shipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "shipment: sequence must be >= 0" {})))
  (let [shipment-number (str "SHP-" (zero-pad sequence 6))
        record {"record_id" shipment-number
                "kind" "shipment-coordination-draft"
                "shipment_id" shipment-id
                "immutable" true}]
    {"record" record "shipment_number" shipment-number
     "certificate" (unsigned-certificate "ShipmentCoordination" shipment-number shipment-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
