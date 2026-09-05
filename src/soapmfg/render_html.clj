(ns soapmfg.render-html
  "Build-time HTML renderer for the operator console.

  Drives the REAL SoapOperationActor (`soapmfg.operation/build` -> a
  compiled langgraph-clj StateGraph) over the REAL seeded store
  (`soapmfg.store/sample-data!`), through the REAL Soap & Detergent
  Plant Operations Governor (`soapmfg.governor/check`) and the REAL
  rollout phase gate (`soapmfg.phase/gate`), and renders whatever those
  produced. Nothing on the page is written by hand:

    - every table row is read back out of the store after the run
      (`store/ledger`, `store/all-batches`, `store/all-equipment`,
      `store/all-maintenance`, `store/shipment`,
      `store/safety-concerns`, `store/maintenance-history`,
      `store/shipment-history`),
    - every HARD-hold rule name and every violation detail string is
      the governor's own `:violations` entry off the ledger fact --
      never a literal in this namespace,
    - the op-gate table is derived from `soapmfg.phase/phases` +
      `soapmfg.governor/allowed-ops`, and the governor-configuration /
      ground-truth-bound tables from `soapmfg.governor` and
      `soapmfg.registry` public vars.

  The ONE hand-written thing on the page is each scenario's
  `:exercises` sentence -- an annotation on the *input* saying why that
  request was driven. It is labelled as such in the page itself. Every
  verdict, disposition, rule name, count and stored field in the same
  row is real output.

  Subject provenance (the demo may not invent subjects): every batch and
  equipment id driven below is either seeded by `store/sample-data!`
  (`batch-001` `batch-002` `batch-003` `kettle-001` `filler-002`) or
  created by an op inside this demo itself -- `batch-004` exists only
  because the `t01` `:log-production-batch` commit created it, and every
  `mnt-*` / `ship-*` / `concern-*` subject is the draft record its own
  op registers via `soapmfg.registry`.

  Fields rendered are only fields the domain model actually carries. In
  particular `:approved-by` is NOT rendered on a committed shipment /
  maintenance record: `soapmfg.operation`'s `:request-approval` node
  puts the approver on the record's `:payload`, while
  `store/commit-record!` persists `:value` -- so the approver is shown
  from the run timeline (where it is real), not from the stored record
  (where it does not exist).

  Deterministic: no clock, no randomness, no network, no timestamp in
  the page content. Re-running writes a byte-identical file. Sets
  (which have no order) are sorted before rendering; vectors are
  rendered in the order the code produced them.

  Run: `clojure -M:dev:render-html [out-file]`
  (default out-file `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [soapmfg.governor :as governor]
            [soapmfg.operation :as op]
            [soapmfg.phase :as phase]
            [soapmfg.registry :as registry]
            [soapmfg.store :as store]))

;; ----------------------------- the run -----------------------------

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase phase/default-phase})

(def ^:private scenarios
  "One entry = one coordination request driven through the real actor.
  `:approval`, when present, is the human decision handed back to the
  paused graph (`interrupt-before #{:request-approval}`); it is only
  ever reached when the governor did NOT hold.

  Between them these exercise all eleven of this governor's HARD rules
  (`soapmfg.governor/check`), the SOFT confidence/high-stakes gate, the
  phase gate's `:phase-approval` escalation, and a human veto of a
  governor-clean proposal."
  [{:tid "t01"
    :exercises "Intake of a NEW production batch (a fragranced perfume with its Annex III allergen disclosure already complete). Governor-clean, and :log-production-batch is the one op in phase 3's :auto set -> auto-commit. batch-004 exists for the rest of this page only because this op created it."
    :request {:op :log-production-batch :effect :propose :subject "batch-004"
              :patch {:product-type :perfume
                      :output-form :spray
                      :material "Neroli Eau de Parfum"
                      :weight-kg 1200.0
                      :off-spec-rate-percent 0.4
                      :fragrance-allergens [:limonene :citral]
                      :allergen-labeling-complete? true
                      :last-assessed "2026-07-20"}}}

   {:tid "t02"
    :exercises "Maintenance window against a verified + registered saponification kettle. Never auto-eligible at any phase -> escalates; the human plant supervisor approves."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "kettle-001"
                      :maintenance-type :agitator-inspection
                      :scheduled-date "2026-08-01"
                      :actuate-line? false}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t03"
    :exercises "Chemical-hazard safety concern. Always high-stakes, so the governor escalates regardless of confidence; the human approves."
    :request {:op :flag-safety-concern :effect :propose :subject "concern-1"
              :value {:equipment-id "kettle-001" :severity :moderate
                      :description "サポニフィケーション釜周辺の苛性アルカリ蒸気濃度上昇"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t04"
    :exercises "Shipment against a verified + registered batch with headroom. Escalates; the human shipping approver approves and the batch's own shipped-weight advances."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-1"
              :value {:batch-id "batch-001" :weight-kg 500.0
                      :destination "buyer-yard-north"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t05"
    :exercises "Governor-clean shipment the human VETOES. Distinct from a HARD hold: the governor cleared it, a person did not."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-2"
              :value {:batch-id "batch-001" :weight-kg 200.0
                      :destination "buyer-yard-west"}}
    :approval {:status :rejected :by "coord-1"}}

   {:tid "t06"
    :exercises "Shipment against batch-004 -- the batch t01 just created, which carries no verified?/registered? ground truth of its own. Logging a batch is not QC-verifying it. HARD hold."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-3"
              :value {:batch-id "batch-004" :weight-kg 100.0
                      :destination "buyer-yard-north"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t07"
    :exercises "Shipment against the seeded UNVERIFIED / unregistered detergent batch. HARD hold."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-4"
              :value {:batch-id "batch-003" :weight-kg 1000.0
                      :destination "buyer-yard-south"}}}

   {:tid "t08"
    :exercises "Shipment whose claimed weight would push batch-002 past its own recorded production weight. The governor recomputes headroom from the batch's own fields, never from the proposal's claim. HARD hold."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-5"
              :value {:batch-id "batch-002" :weight-kg 1000.0
                      :destination "buyer-yard-east"}}}

   {:tid "t09"
    :exercises "Maintenance against the seeded filling line, which is neither inspected nor on file. HARD hold."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-2"
              :value {:equipment-id "filler-002"
                      :maintenance-type :nozzle-inspection
                      :scheduled-date "2026-08-01"
                      :actuate-line? false}}}

   {:tid "t10"
    :exercises "A maintenance proposal that tries to ACTUATE the formulation / filling line rather than draft a window. Permanent scope boundary -- never reaches a human, even though an approval was queued for it. HARD hold."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-3"
              :value {:equipment-id "kettle-001" :maintenance-type :force-run
                      :scheduled-date "2026-09-01"
                      :actuate-line? true}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t11"
    :exercises "The SAME maintenance window as t02, scheduled twice. Guarded off a dedicated :scheduled? fact, never a :status value. HARD hold."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "kettle-001"
                      :maintenance-type :agitator-inspection
                      :scheduled-date "2026-08-01"
                      :actuate-line? false}}}

   {:tid "t12"
    :exercises "A batch patch declaring a product type outside the closed known set for ISIC 2023's four product families. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-003"
              :patch {:product-type :unobtainium-soap}}}

   {:tid "t13"
    :exercises "A batch patch claiming an off-spec rate above 100% -- a batch cannot reject more than its own output. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-003"
              :patch {:off-spec-rate-percent 999.0}}}

   {:tid "t14"
    :exercises "A patch declaring designated fragrance allergens on a fragrance-bearing batch WITHOUT confirming the Annex III label disclosure is complete. Re-derived from the batch's own recorded product type, never from the advisor's self-report. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:fragrance-allergens [:eugenol :coumarin]}}}

   {:tid "t15"
    :exercises "A mis-wired caller whose own request :effect is not :propose -- checked before anything else. HARD hold."
    :request {:op :log-production-batch :effect :direct-write :subject "batch-001"
              :patch {:product-type :bar-soap}}}

   {:tid "t16"
    :exercises "An op outside the closed allowlist. Both the op allowlist and the proposal-effect allowlist reject it. HARD hold."
    :request {:op :actuate-saponification-kettle :effect :propose :subject "batch-001"}}])

(defn- drive!
  "Runs one scenario through the real compiled graph and returns the
  scenario enriched with what the graph actually did."
  [actor {:keys [tid request approval] :as scenario}]
  (let [r1 (g/run* actor {:request request :context coordinator} {:thread-id tid})
        paused? (= :interrupted (:status r1))
        r2 (when (and approval paused?)
             (g/run* actor {:approval approval} {:thread-id tid :resume? true}))
        final (:state (or r2 r1))
        audit (:audit final [])]
    (assoc scenario
           :verdict (:verdict final)
           :paused? paused?
           :escalation (first (filter #(= :approval-requested (:t %)) audit))
           :human (when r2 (:status approval))
           :disposition (:disposition final))))

(defn run-demo!
  "Seeds a MemStore, builds the real actor, drives every scenario.
  Returns {:db store :runs [..]}."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]
    {:db db :runs (mapv #(drive! actor %) scenarios)}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- fmt
  "Render a stored value, or an em dash when the domain model has no
  value for that field on that record."
  [v]
  (if (nil? v) "—" (esc v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- flag [v]
  (if (true? v)
    "<span class=\"ok\">true</span>"
    (str "<span class=\"err\">" (if (nil? v) "—" (esc v)) "</span>")))

(defn- codes
  "Render a SEQUENCE of keywords in the order the code produced it --
  used for `:basis` (the governor's own evaluation order) and for a
  patch's own `:fragrance-allergens` vector."
  [coll]
  (if (seq coll)
    (str/join " " (map code coll))
    "—"))

(defn- kw-codes
  "Render a SET of keywords. Sorted, because a set has no order and an
  unsorted render would make the output non-deterministic."
  [coll]
  (str/join " " (map code (sort-by str coll))))

(defn- tr [& cells] (str "<tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "<table><thead><tr>"
       (apply str (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead><tbody>\n"
       (str/join "\n" rows)
       "\n</tbody></table>"))

(defn- card [title note body]
  (str "<section class=\"card\"><h2>" (esc title) "</h2>"
       (when note (str "<p class=\"muted\">" note "</p>"))
       body "</section>"))

;; ----------------------------- sections -----------------------------

(defn- ledger-of [db] (vec (store/ledger db)))

(defn- holds
  "The real `:governor-hold` facts on the append-only ledger. A human
  veto (`:approval-rejected`) is deliberately NOT counted here -- it is
  a person declining a governor-clean proposal, not a compliance
  violation."
  [db]
  (filterv #(= :governor-hold (:t %)) (ledger-of db)))

(defn- stat [label value]
  (str "<div class=\"stat\"><span class=\"num\">" (esc value) "</span> "
       "<span class=\"muted\">" (esc label) "</span></div>"))

(defn- summary-section [db runs]
  (let [led (ledger-of db)
        n (fn [t] (count (filter #(= t (:t %)) led)))]
    (card "Run summary"
          (str "Every number below is a count over the actor's own append-only ledger "
               "after driving " (count runs) " requests through "
               (code "soapmfg.operation/build") ".")
          (str "<p>"
               (stat "requests driven" (count runs))
               (stat "ledger facts" (count led))
               (stat "commits" (n :committed))
               (stat "governor HARD holds" (n :governor-hold))
               (stat "human vetoes" (n :approval-rejected))
               (stat "distinct HARD rules fired"
                     (count (distinct (mapcat :basis (holds db)))))
               "</p>"
               "<p class=\"muted\">Note: <code>:approval-granted</code> is emitted to the graph's "
               "in-memory <code>:audit</code> channel only — <code>soapmfg.operation</code> never "
               "appends it to the store ledger, so it is not a fact this page counts. An approved "
               "request is visible as the <code>:committed</code> fact it produced.</p>"))))

(defn- verdict-cell [{:keys [verdict]}]
  (cond
    (nil? verdict) "<span class=\"muted\">—</span>"
    (:hard? verdict)
    (str "<span class=\"critical\">HARD</span> "
         (codes (map :rule (:violations verdict))))
    (:escalate? verdict)
    (str "<span class=\"warn\">escalate</span>"
         (when (:high-stakes? verdict) " <span class=\"muted\">high-stakes</span>"))
    :else (str "<span class=\"ok\">clean</span> <span class=\"muted\">conf "
               (esc (:confidence verdict)) "</span>")))

(defn- human-cell [{:keys [approval human paused?]}]
  (cond
    (= :approved human) "<span class=\"ok\">approved</span>"
    (= :rejected human) "<span class=\"critical\">vetoed</span>"
    (and approval (not paused?))
    "<span class=\"muted\">never offered (no interrupt)</span>"
    :else "<span class=\"muted\">—</span>"))

(defn- disposition-cell [{:keys [disposition]}]
  (case disposition
    :commit "<span class=\"ok\">commit</span>"
    :hold "<span class=\"critical\">hold</span>"
    :escalate "<span class=\"warn\">escalate</span>"
    (str "<span class=\"muted\">" (fmt disposition) "</span>")))

(defn- timeline-section [runs]
  (card "Request timeline"
        (str "One row = one <code>langgraph.graph/run*</code> over the compiled actor. "
             "The governor column is the verdict map the governor itself returned; the human "
             "column is the decision handed back to the graph while it was paused at "
             (code ":request-approval") ". The last column is the only hand-written text on "
             "this page — it annotates the <em>input</em> (why this request was driven); "
             "everything to its left is what the actor actually did.")
        (table ["Thread" "Op" "Subject" "Governor" "Human" "Final" "What this exercises (annotation)"]
               (for [{:keys [tid request escalation exercises] :as r} runs]
                 (tr (code tid)
                     (code (:op request))
                     (code (:subject request))
                     (verdict-cell r)
                     (human-cell r)
                     (str (disposition-cell r)
                          (when-let [reason (:reason escalation)]
                            (str " <span class=\"muted\">after escalation "
                                 (code reason) "</span>")))
                     (str "<span class=\"muted\">" (esc exercises) "</span>"))))))

(defn- holds-section [db]
  (let [hs (holds db)]
    (card "Governor HARD holds"
          (str "Each row is one violation inside a <code>:governor-hold</code> fact on the "
               "append-only ledger. The rule name and the detail text are the governor's own "
               (code ":violations") " entries — this page holds no rule text of its own. A HARD "
               "hold is never offered to a human: the graph routes it straight to "
               (code ":hold") ".")
          (table ["Rule" "Op" "Subject" "Confidence" "Governor's own detail"]
                 (for [h hs
                       v (:violations h)]
                   (tr (str "<span class=\"critical\">" (esc (:rule v)) "</span>")
                       (code (:op h))
                       (code (:subject h))
                       (fmt (:confidence h))
                       (esc (:detail v))))))))

(defn- rejections-section [db]
  (let [rs (filterv #(= :approval-rejected (:t %)) (ledger-of db))]
    (when (seq rs)
      (card "Human vetoes"
            (str "A governor-clean proposal a person declined. Written to the ledger by the same "
                 (code ":hold") " node, but with basis " (code ":approver-rejected") " — not a "
                 "compliance violation.")
            (table ["Op" "Subject" "Basis" "Confidence"]
                   (for [r rs]
                     (tr (code (:op r)) (code (:subject r))
                         (codes (:basis r)) (fmt (:confidence r)))))))))

(defn- op-gate-section []
  (let [ph phase/default-phase
        {:keys [label writes auto]} (get phase/phases ph)]
    (card (str "Op gate — rollout phase " ph " (" label ")")
          (str "Derived from " (code "soapmfg.phase/phases") " and "
               (code "soapmfg.governor/allowed-ops") ", not typed here. A governor HOLD always "
               "stays a HOLD; an op that may write but is not auto-eligible escalates to a human "
               "even when the governor is clean. "
               (code ":schedule-maintenance") " is absent from every phase's "
               (code ":auto") " set by construction — a permanent structural fact, not a rollout "
               "milestone still to come.")
          (table ["Op" "May write in this phase" "May auto-commit when governor-clean"]
                 (for [o (sort-by str governor/allowed-ops)]
                   (tr (code o)
                       (if (contains? writes o)
                         "<span class=\"ok\">yes</span>"
                         "<span class=\"critical\">no — HOLD (:phase-disabled)</span>")
                       (if (contains? auto o)
                         "<span class=\"ok\">yes</span>"
                         "<span class=\"warn\">no — always human approval</span>")))))))

(defn- governor-section []
  (card "Governor configuration"
        (str "Read straight off the public vars of " (code "soapmfg.governor") ".")
        (table ["Setting" "Value"]
               [(tr "confidence floor" (code governor/confidence-floor))
                (tr "allowed ops" (kw-codes governor/allowed-ops))
                (tr "allowed proposal effects" (kw-codes governor/allowed-proposal-effects))
                (tr "always-human stakes" (kw-codes governor/high-stakes))])))

(defn- bounds-section []
  (card "Independent ground-truth bounds"
        (str "The values " (code "soapmfg.registry") " uses to re-derive the truth itself, "
             "rather than believing the advisor's rationale.")
        (table ["Bound" "Value"]
               [(tr "valid product types" (kw-codes registry/valid-product-types))
                (tr "fragrance-bearing product types"
                    (kw-codes registry/fragrance-bearing-product-types))
                (tr "designated fragrance allergens (EU 1223/2009 Annex III subset)"
                    (kw-codes registry/known-fragrance-allergens))
                (tr "valid output forms" (kw-codes registry/valid-output-forms))
                (tr "off-spec rate (%)"
                    (str (code registry/off-spec-rate-min-percent) " … "
                         (code registry/off-spec-rate-max-percent)))])))

(defn- last-fact-for [led subject]
  (last (filter #(= subject (:subject %)) led)))

(defn- subject-status [led subject]
  (let [f (last-fact-for led subject)]
    (cond
      (nil? f) "<span class=\"muted\">no ledger activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-rejected (:t f)) "<span class=\"critical\">vetoed by approver</span>"
      (= :governor-hold (:t f))
      (str "<span class=\"critical\">HARD hold</span> " (codes (:basis f)))
      :else (str "<span class=\"muted\">" (esc (:t f)) "</span>"))))

(defn- remaining-kg [b]
  (let [w (:weight-kg b) s (:shipped-weight-kg b 0.0)]
    (if (and (number? w) (number? s)) (- (double w) (double s)) nil)))

(defn- batches-section [db]
  (let [led (ledger-of db)]
    (card "Production batches"
          (str "Read back from " (code "soapmfg.store/all-batches") " after the run. "
               (code "batch-001") " " (code "batch-002") " " (code "batch-003")
               " are seeded by " (code "store/sample-data!") "; " (code "batch-004")
               " exists because the <code>t01</code> intake op committed it. A field the record "
               "does not carry shows as —; " (code "batch-004") " has no "
               (code ":shipped-weight-kg") " of its own yet, so <em>Remaining</em> uses the same "
               (code "0.0") " default " (code "soapmfg.registry")
               " itself applies when it recomputes headroom.")
          (table ["Batch" "Product type" "Form" "Material" "Weight (kg)" "Shipped (kg)"
                  "Remaining (kg)" "Off-spec (%)" "Fragrance allergens" "labeling complete?"
                  "verified?" "registered?" "ready?" "Last assessed" "Ledger status"]
                 (for [b (store/all-batches db)]
                   (tr (code (:id b)) (fmt (:product-type b)) (fmt (:output-form b))
                       (fmt (:material b)) (fmt (:weight-kg b)) (fmt (:shipped-weight-kg b))
                       (fmt (remaining-kg b)) (fmt (:off-spec-rate-percent b))
                       (codes (:fragrance-allergens b))
                       (flag (:allergen-labeling-complete? b))
                       (flag (:verified? b)) (flag (:registered? b))
                       (if (registry/batch-ready? b)
                         "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>")
                       (fmt (:last-assessed b))
                       (subject-status led (:id b))))))))

(defn- equipment-section [db]
  (card "Saponification / filling-line equipment"
        (str "Read back from " (code "soapmfg.store/all-equipment") ". Equipment ids are never a "
             "request " (code ":subject") " in this domain (a maintenance draft id is), so no "
             "ledger-status column is shown for them — " (code ":last-scheduled-maintenance-date")
             " is the field the commit path actually writes onto an equipment record.")
        (table ["Unit" "Kind" "verified?" "registered?" "ready?" "Last maintenance"
                "Last scheduled maintenance" "Maintenance drafts on file"]
               (for [e (store/all-equipment db)]
                 (tr (code (:id e)) (fmt (:kind e))
                     (flag (:verified? e)) (flag (:registered? e))
                     (if (registry/equipment-ready? e)
                       "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>")
                     (fmt (:last-maintenance-date e))
                     (fmt (:last-scheduled-maintenance-date e))
                     (esc (count (filter #(= (:id e) (:equipment-id %))
                                         (store/all-maintenance db)))))))))

(defn- maintenance-section [db]
  (let [ms (store/all-maintenance db)]
    (card "Maintenance schedule drafts"
          (str "Committed drafts from " (code "soapmfg.store/all-maintenance") ". The maintenance "
               "number is minted by " (code "soapmfg.registry/register-maintenance")
               " at commit time. Nothing here actuates any kettle or filling line.")
          (if (seq ms)
            (table ["Draft" "Equipment" "Type" "Scheduled date" "actuate-line?"
                    "scheduled?" "Maintenance number"]
                   (for [m ms]
                     (tr (code (:id m)) (code (:equipment-id m)) (fmt (:maintenance-type m))
                         (fmt (:scheduled-date m)) (flag (:actuate-line? m))
                         (flag (:scheduled? m)) (fmt (:maintenance-number m)))))
            "<p class=\"muted\">none committed in this run</p>"))))

(defn- shipments-section [db]
  (let [hist (store/shipment-history db)
        ships (keep #(store/shipment db (get % "shipment_id")) hist)]
    (card "Shipment coordination drafts"
          (str "Committed drafts, joined from " (code "soapmfg.store/shipment-history")
               " back to each stored shipment record. This is a draft a coordinator keeps — it "
               "dispatches no freight carrier.")
          (if (seq ships)
            (table ["Draft" "Batch" "Weight (kg)" "Destination" "Shipment number"]
                   (for [s ships]
                     (tr (code (:id s)) (code (:batch-id s)) (fmt (:weight-kg s))
                         (fmt (:destination s)) (fmt (:shipment-number s)))))
            "<p class=\"muted\">none committed in this run</p>"))))

(defn- concerns-section [db]
  (let [cs (store/safety-concerns db)]
    (card "Safety concerns"
          (str "The append-only safety-concern log (" (code "soapmfg.store/safety-concerns")
               "). A concern may be raised against any equipment or batch, verified or not — it "
               "is never blocked on an administrative technicality.")
          (if (seq cs)
            (table ["Concern" "Equipment" "Severity" "Description"]
                   (for [c cs]
                     (tr (code (:id c)) (code (:equipment-id c)) (fmt (:severity c))
                         (fmt (:description c)))))
            "<p class=\"muted\">none flagged in this run</p>"))))

(defn- ledger-section [db]
  (card "Audit ledger (append-only)"
        (str "The full ledger, in append order, exactly as " (code "soapmfg.store/ledger")
             " returns it.")
        (table ["#" "Fact" "Op" "Subject" "Actor" "Disposition" "Basis"]
               (map-indexed
                (fn [i f]
                  (tr (esc (inc i))
                      (let [cls (case (:t f)
                                  :committed "ok"
                                  :governor-hold "critical"
                                  :approval-rejected "critical"
                                  "muted")]
                        (str "<span class=\"" cls "\">" (esc (:t f)) "</span>"))
                      (code (:op f)) (code (:subject f)) (fmt (:actor f))
                      (fmt (:disposition f)) (codes (:basis f))))
                (ledger-of db)))))

;; ----------------------------- page -----------------------------

(defn render
  "The whole page, from the post-run store and the run log."
  [{:keys [db runs]}]
  (str "<!DOCTYPE html>\n<html lang=\"en\">\n<head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       "<meta name=\"color-scheme\" content=\"light\">"
       "<title>Operator console — cloud-itonami-isic-2023 (soapmfg)</title>"
       "<style>" (jp-go-dds.skin/dds+skin) "</style></head>\n<body>\n"
       "<header class=\"bar\">"
       "<h1>Soap &amp; detergent plant operations — operator console</h1>"
       "</header>\n"
       "<p class=\"subtitle\"><span class=\"badge\">ISIC 2023</span> "
       "<span class=\"badge\">soapmfg</span> governor "
       ;; Page title/subtitle text — the repo's own identity, fixed by
       ;; blueprint.edn `:itonami.blueprint/governor`. Not a row, number
       ;; or status: every one of those below is derived from the run.
       (code "soap-detergent-plant-operations-governor")
       " · actor " (esc (:actor-id coordinator))
       " · role " (esc (:actor-role coordinator))
       " · phase " (esc (:phase coordinator)) "</p>\n"
       "<main>\n"
       (str/join "\n"
                 (remove nil?
                         [(summary-section db runs)
                          (timeline-section runs)
                          (holds-section db)
                          (rejections-section db)
                          (op-gate-section)
                          (governor-section)
                          (bounds-section)
                          (batches-section db)
                          (equipment-section db)
                          (maintenance-section db)
                          (shipments-section db)
                          (concerns-section db)
                          (ledger-section db)]))
       "\n</main>\n<footer>"
       "Generated at build time by <code>soapmfg.render-html</code> "
       "(<code>clojure -M:dev:render-html</code>) by driving the real "
       "<code>soapmfg.operation</code> actor graph over the real "
       "<code>soapmfg.store</code> seed. Deterministic — no clock, no randomness, no network. "
       "No usage, revenue or performance metric is claimed anywhere on this page."
       "</footer>\n</body>\n</html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        hs (holds db)]
    ;; A console that shows no real HARD hold is not evidence of a governor.
    (when (empty? hs)
      (throw (ex-info "no :governor-hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  (count hs) " HARD holds, "
                  (count runs) " requests)"))))
