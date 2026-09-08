(ns soapmfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: before this namespace
  existed there was NO demo page and no generator at all. Everything on
  the generated page is REAL actor output -- this file seeds a fresh
  `soapmfg.store` MemStore with this repo's own `sample-data!`, drives
  the REAL `soapmfg.operation` StateGraph (SoapAdvisor -> Soap &
  Detergent Plant Operations Governor -> `soapmfg.phase` gate ->
  commit | hold | approval) through `langgraph.graph/run*`, and renders
  from the resulting store, ledger, registry drafts and per-run
  governor verdicts. No hand-typed statuses, ids, weights, rule names
  or hold reasons: if a value appears on the page it came out of the
  actor, out of `soapmfg.governor`'s own allowlists/floors, or out of
  `soapmfg.phase`'s own phase table.

  Determinism: the store is a fresh in-memory atom per run, the advisor
  is `soapmfg.advisor/mock-advisor` (deterministic), thread ids are
  supplied by the caller, and neither `soapmfg.registry` nor the ledger
  facts carry a clock -- so two consecutive runs are byte-identical.
  Nothing on the page is dated except the sample data's own recorded
  `:last-assessed` / `:last-maintenance-date` strings, which are seeded
  constants in `soapmfg.store`, not a wall clock.

  Build-time invariant: `-main` REFUSES to write the file if the
  resulting ledger contains zero `:governor-hold` facts. The whole
  point of the console is to show that HARD violations never reach a
  human; a scenario that quietly stopped producing holds would render a
  page that lies about the governor, so it is a build failure rather
  than a convention.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [kotoba.lang.text :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [soapmfg.governor :as governor]
            [soapmfg.operation :as op]
            [soapmfg.phase :as phase]
            [soapmfg.store :as store]))

(def ^:private coordinator
  "The operator context every run below is executed as -- phase 3
  (`supervised-auto`), the same context `soapmfg.sim` uses."
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

;; ----------------------------- scenario -----------------------------

(defn- exec!
  "One real graph run. Returns langgraph's own {:state :events :status}."
  [actor tid request]
  (g/run* actor {:request request :context coordinator} {:thread-id tid}))

(defn- approve!
  "Resume an interrupted (escalated) run as the human plant supervisor /
  shipping approver would."
  [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}}
          {:thread-id tid :resume? true}))

(def ^:private scenario
  "The scenario, as data: one entry per graph run. `:approve?` marks the
  runs a human is expected to be handed (they are resumed with an
  approval); every other entry is expected to be decided without one.
  Nothing here asserts an outcome -- the outcome rendered on the page is
  whatever the real governor/phase gate actually returns for each of
  these requests.

  Adapted from this repo's own `soapmfg.sim` demo driver, whose ids
  (`batch-001`..`batch-003`, `kettle-001`, `filler-002`) do match
  `soapmfg.store/sample-data!`."
  [{:tid "t1" :approve? false
    :label "生産バッチ記録 (clean patch)"
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:product-type :bar-soap :last-assessed "2026-07-14"}}}

   {:tid "t2" :approve? true
    :label "保守作業予定 (検証済み釜)"
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "kettle-001" :maintenance-type :agitator-inspection
                      :scheduled-date "2026-08-01" :actuate-line? false}}}

   {:tid "t3" :approve? true
    :label "安全懸念報告 (常に高ステーク)"
    :request {:op :flag-safety-concern :effect :propose :subject "concern-1"
              :value {:equipment-id "kettle-001" :severity :moderate
                      :description "サポニフィケーション釜周辺の苛性アルカリ蒸気濃度上昇"}}}

   {:tid "t4" :approve? true
    :label "出荷調整 (残容量内)"
    :request {:op :coordinate-shipment :effect :propose :subject "ship-1"
              :value {:batch-id "batch-001" :weight-kg 500.0
                      :destination "buyer-yard-north"}}}

   {:tid "t5" :approve? false
    :label "request :effect が :propose でない"
    :request {:op :log-production-batch :effect :direct-write :subject "batch-001"
              :patch {:product-type :bar-soap}}}

   {:tid "t6" :approve? false
    :label "許可リスト外の op"
    :request {:op :actuate-saponification-kettle :effect :propose :subject "batch-001"}}

   {:tid "t7" :approve? false
    :label "未検証/未登録の充填ラインへの保守予定"
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-2"
              :value {:equipment-id "filler-002" :maintenance-type :nozzle-inspection
                      :scheduled-date "2026-08-01" :actuate-line? false}}}

   {:tid "t8" :approve? false
    :label "未検証/未登録バッチからの出荷調整"
    :request {:op :coordinate-shipment :effect :propose :subject "ship-2"
              :value {:batch-id "batch-003" :weight-kg 1000.0
                      :destination "buyer-yard-south"}}}

   {:tid "t9" :approve? false
    :label "記録生産量を超える出荷申請"
    :request {:op :coordinate-shipment :effect :propose :subject "ship-3"
              :value {:batch-id "batch-002" :weight-kg 1000.0
                      :destination "buyer-yard-east"}}}

   {:tid "t10" :approve? false
    :label "配合/充填ラインの直接操作 (actuate) 提案"
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-3"
              :value {:equipment-id "kettle-001" :maintenance-type :force-run
                      :scheduled-date "2026-09-01" :actuate-line? true}}}

   {:tid "t11" :approve? false
    :label "同一保守窓の二重スケジュール"
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "kettle-001" :maintenance-type :agitator-inspection
                      :scheduled-date "2026-08-01" :actuate-line? false}}}

   {:tid "t12" :approve? false
    :label "捏造された product-type"
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:product-type :unobtainium-soap}}}

   {:tid "t13" :approve? false
    :label "物理的にありえない不良率"
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:off-spec-rate-percent 999.0}}}

   {:tid "t14" :approve? false
    :label "香料アレルゲン開示の未完了"
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:fragrance-allergens [:eugenol :coumarin]}}}])

(defn run-demo!
  "Runs `scenario` through a REAL SoapOperationActor bound to a freshly
  seeded store. Returns {:db store :runs [..]} where each run entry
  carries the actor's own returned state for the first pass and (when
  the actor actually interrupted for a human) for the resumed pass.
  Every field the renderer reads below is actor output."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)
        runs (mapv (fn [{:keys [tid request approve?] :as entry}]
                     (let [r1 (exec! actor tid request)
                           ;; Only resume when the actor ACTUALLY
                           ;; interrupted. A HARD hold never reaches a
                           ;; human, so there is nothing to resume --
                           ;; and asking is how we prove it.
                           r2 (when (and approve? (= :interrupted (:status r1)))
                                (approve! actor tid))]
                       (assoc entry :first r1 :resumed r2)))
                   scenario)]
    {:db db :runs runs}))

;; ----------------------------- derived (all from actor output) -----------------------------

(defn- final-state [{:keys [first resumed]}]
  (:state (or resumed first)))

(defn- reached-human? [run]
  (= :interrupted (:status (:first run))))

(defn- hard? [run]
  (boolean (:hard? (:verdict (:state (:first run))))))

(defn- rules [run]
  (mapv :rule (:violations (:verdict (:state (:first run))))))

(defn- run-hold-details
  "The governor's own `:detail` strings for a held run."
  [run]
  (mapv :detail (:violations (:verdict (:state (:first run))))))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-list
  "Render a `:basis` / rule / effect collection. Keeps the NAMESPACE of a
  qualified keyword (`:batch/upsert` -> `batch/upsert`) -- dropping it
  would silently turn four distinct effects into `flag, propose,
  schedule, upsert`, which is not what the governor's allowlist says."
  [xs]
  (str/join ", " (map #(if (keyword? %) (subs (str %) 1) (str %)) xs)))

(defn- td [& cells]
  (str "        <tr>" (str/join "" (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- yes-no [b klass-yes klass-no yes no]
  (if b
    (str "<span class=\"" klass-yes "\">" yes "</span>")
    (str "<span class=\"" klass-no "\">" no "</span>")))

(defn- governor-cell [run]
  (cond
    (hard? run)
    (str "<span class=\"critical\">HARD &middot; " (esc (kw-list (rules run))) "</span>")

    (:escalate? (:verdict (:state (:first run))))
    (str "<span class=\"warn\">clean &middot; escalate (confidence "
         (esc (:confidence (:verdict (:state (:first run))))) ")</span>")

    :else "<span class=\"ok\">clean</span>"))

(defn- outcome-cell [run]
  (case (:disposition (final-state run))
    :commit (if (reached-human? run)
              "<span class=\"ok\">承認後コミット</span>"
              "<span class=\"ok\">自動コミット</span>")
    :hold (if (reached-human? run)
            "<span class=\"warn\">承認者が却下</span>"
            "<span class=\"critical\">HOLD (人に届かない)</span>")
    :escalate "<span class=\"warn\">承認待ち</span>"
    "<span class=\"muted\">-</span>"))

(defn- run-row [{:keys [tid label request] :as run}]
  (td (str "<code>" (esc tid) "</code>")
      (esc label)
      (str "<code>" (esc (:op request)) "</code>")
      (str "<code>" (esc (:subject request)) "</code>")
      (governor-cell run)
      (yes-no (reached-human? run) "warn" "muted" "はい" "いいえ")
      (outcome-cell run)))

(defn- hold-row [run]
  (td (str "<code>" (esc (:op (:request run))) "</code>")
      (str "<code>" (esc (:subject (:request run))) "</code>")
      (str "<code>" (esc (kw-list (rules run))) "</code>")
      (esc (str/join " / " (run-hold-details run)))))

(defn- batch-row [{:keys [id product-type output-form material weight-kg
                          off-spec-rate-percent shipped-weight-kg
                          fragrance-allergens allergen-labeling-complete?
                          verified? registered? last-assessed]}]
  (td (str "<code>" (esc id) "</code>")
      (esc material)
      (str "<code>" (esc product-type) "</code> / <code>" (esc output-form) "</code>")
      (esc weight-kg)
      (esc shipped-weight-kg)
      (esc off-spec-rate-percent)
      (if (seq fragrance-allergens)
        (str (esc (kw-list fragrance-allergens)) " &middot; "
             (yes-no allergen-labeling-complete? "ok" "err" "開示済み" "未開示"))
        "<span class=\"muted\">なし</span>")
      (yes-no (and verified? registered?) "ok" "err" "検証済み・登録済み" "未検証/未登録")
      (esc last-assessed)))

(defn- equipment-row [{:keys [id kind verified? registered?
                              last-maintenance-date last-scheduled-maintenance-date]}]
  (td (str "<code>" (esc id) "</code>")
      (str "<code>" (esc kind) "</code>")
      (yes-no (and verified? registered?) "ok" "err" "検証済み・登録済み" "未検証/未登録")
      (if last-maintenance-date (esc last-maintenance-date) "<span class=\"muted\">記録なし</span>")
      (if last-scheduled-maintenance-date
        (str "<span class=\"ok\">" (esc last-scheduled-maintenance-date) "</span>")
        "<span class=\"muted\">未予定</span>")))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (td (case t
        :committed "<span class=\"ok\">committed</span>"
        :governor-hold "<span class=\"critical\">governor-hold</span>"
        :approval-rejected "<span class=\"warn\">approval-rejected</span>"
        (str "<span class=\"muted\">" (esc (name t)) "</span>"))
      (str "<code>" (esc op) "</code>")
      (str "<code>" (esc subject) "</code>")
      (esc (name (or disposition :unknown)))
      (esc (kw-list (or basis [])))))

(defn- gate-rows
  "The action gate, derived from `soapmfg.governor`'s own closed op
  allowlist and `soapmfg.phase`'s own phase table -- not a hand-written
  description of them. If either changes, this table changes with it."
  []
  (let [phases (sort (keys phase/phases))
        first-writable (fn [op] (first (filter #(contains? (:writes (phase/phases %)) op) phases)))
        auto-phase (fn [op] (first (filter #(contains? (:auto (phase/phases %)) op) phases)))]
    (for [op (sort-by name governor/allowed-ops)]
      (let [w (first-writable op)
            a (auto-phase op)]
        (td (str "<code>" (esc op) "</code>")
            (if w
              (str "phase " w " (" (esc (:label (phase/phases w))) ") から")
              "<span class=\"muted\">どの phase でも不可</span>")
            (if a
              (str "<span class=\"ok\">phase " a " で自動コミット可</span>")
              "<span class=\"warn\">どの phase でも人の承認が必要</span>")
            (if (contains? governor/high-stakes :coordination/safety-concern)
              (if (= op :flag-safety-concern)
                (str "<span class=\"warn\">常に高ステーク (<code>"
                     (esc :coordination/safety-concern) "</code>)</span>")
                (str "<span class=\"muted\">confidence &lt; " governor/confidence-floor
                     " で承認へ</span>"))
              ""))))))

(defn- draft-row [record keys*]
  (apply td (map #(str "<code>" (esc (get record %)) "</code>") keys*)))

(defn- section [title lead headers rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       "    <table>\n"
       "      <thead><tr>" (str/join "" (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

(defn render
  "Renders the operator console from the result of `run-demo!`."
  [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))
        holds (filter #(= :governor-hold (:t %)) ledger)
        commits (filter #(= :committed (:t %)) ledger)
        held-runs (filter hard? runs)]
    (str
     "<!doctype html>\n"
     "<html lang=\"ja\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-2023 &middot; soap &amp; detergent plant operations</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>石鹸・洗剤・洗浄/研磨剤・香水・化粧品 製造プラント運用 (ISIC 2023) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample &middot; governor-gated &middot; 保守予定・安全懸念・出荷調整は常に人の承認</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>この実行の要約</h2>\n"
     "    <p>この頁は <code>clojure -M:dev:render-html</code> がビルド時に、実際の "
     "<code>soapmfg.operation</code> StateGraph を <code>langgraph.graph/run*</code> で "
     (count runs) " 回走らせて生成したものです。数値・id・判定・理由はすべて実行結果であり、"
     "手書きの文字列ではありません。</p>\n"
     "    <ul>\n"
     "      <li>コミット済み事実: <span class=\"ok\">" (count commits) "</span></li>\n"
     "      <li>HARD hold (人に届かない): <span class=\"critical\">" (count holds) "</span></li>\n"
     "      <li>人の承認を経た実行: <span class=\"warn\">" (count (filter reached-human? runs)) "</span></li>\n"
     "      <li>保守予定ドラフト: " (count (store/maintenance-history db))
     " &middot; 出荷調整ドラフト: " (count (store/shipment-history db))
     " &middot; 安全懸念: " (count (store/safety-concerns db)) "</li>\n"
     "    </ul>\n"
     "  </section>\n"

     (section "実行 (this run)"
              "1 行 = 1 グラフ実行。「人に届いたか」は actor が実際に <code>interrupt-before</code> で停止したかどうかで、HARD 違反は一度も停止しません。"
              ["thread" "シナリオ" "op" "subject" "governor" "人に届いたか" "結果"]
              (map run-row runs))

     (section "HARD hold の内訳"
              "governor が返した <code>:rule</code> と <code>:detail</code> をそのまま表示しています。どれも override 不能で、人の承認画面に到達しません。"
              ["op" "subject" "rule" "governor の理由"]
              (map hold-row held-runs))

     (section "アクションゲート (Soap &amp; Detergent Plant Operations Governor)"
              (str "<code>soapmfg.governor/allowed-ops</code> と <code>soapmfg.phase/phases</code> から導出。"
                   "confidence floor = <code>" governor/confidence-floor "</code>、"
                   "許可される proposal effect = <code>"
                   (esc (kw-list (sort-by str governor/allowed-proposal-effects))) "</code>。")
              ["op" "書き込み可能になる phase" "自動コミット" "備考"]
              (gate-rows))

     (section "生産バッチ (実行後の SSoT)"
              "出荷実績は出荷調整のコミットで実際に更新された値です。"
              ["batch" "材料" "product-type / form" "生産量 kg" "出荷済み kg" "不良率 %"
               "香料アレルゲン" "検証/登録" "最終評価日"]
              (map batch-row (store/all-batches db)))

     (section "設備 (実行後の SSoT)"
              "「予定済み保守窓」は承認済みの <code>:maintenance/schedule</code> コミットが書いた値です。"
              ["equipment" "種別" "検証/登録" "最終保守日" "予定済み保守窓"]
              (map equipment-row (store/all-equipment db)))

     (section "監査台帳 (append-only)"
              "<code>soapmfg.store</code> の追記専用ログ。commit と hold の両方が残ります。"
              ["fact" "op" "subject" "disposition" "basis"]
              (map ledger-row ledger))

     (section "保守予定ドラフト (soapmfg.registry)"
              "実運転ではなく DRAFT レコード。番号は <code>register-maintenance</code> が採番した実値です。"
              ["record_id" "kind" "maintenance_id" "equipment_id"]
              (map #(draft-row % ["record_id" "kind" "maintenance_id" "equipment_id"])
                   (store/maintenance-history db)))

     (section "出荷調整ドラフト (soapmfg.registry)"
              "実際の運送手配ではなく DRAFT レコード。番号は <code>register-shipment</code> が採番した実値です。"
              ["record_id" "kind" "shipment_id"]
              (map #(draft-row % ["record_id" "kind" "shipment_id"])
                   (store/shipment-history db)))

     (section "安全懸念ログ"
              "<code>:flag-safety-concern</code> は常に高ステークで、承認を経てのみ記録されます。"
              ["id" "equipment" "severity" "内容"]
              (map (fn [{:keys [id equipment-id severity description approved-by]}]
                     (td (str "<code>" (esc id) "</code>")
                         (str "<code>" (esc equipment-id) "</code>")
                         (str "<span class=\"warn\">" (esc severity) "</span>")
                         (str (esc description)
                              (when approved-by
                                (str " <span class=\"muted\">(承認: " (esc approved-by) ")</span>")))))
                   (store/safety-concerns db)))

     "</main>\n"
     "<footer>\n"
     "  <p class=\"muted\">生成: <code>soapmfg.render-html</code> (<code>clojure -M:dev:render-html</code>)。"
     "決定的 — 同じ seed からの再実行はバイト単位で同一です。時刻はページ本文に一切含まれません。</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        ledger (vec (store/ledger db))
        holds (filterv #(= :governor-hold (:t %)) ledger)
        commits (filterv #(= :committed (:t %)) ledger)]
    ;; Build-time invariant: a console that shows no HARD hold would be
    ;; a page that lies about this governor. Refuse to write it.
    (when (zero? (count holds))
      (throw (ex-info
              (str "REFUSING to write " out
                   ": the scenario produced ZERO :governor-hold ledger facts. "
                   "The operator console exists to show that HARD violations never reach "
                   "a human, so a run with no holds cannot be rendered honestly. "
                   "Fix the scenario (or the governor) before regenerating.")
              {:out out
               :ledger-facts (count ledger)
               :governor-holds 0
               :committed (count commits)})))
    (let [parent (.getParentFile (java.io.File. ^String out))]
      (when parent (.mkdirs parent)))
    (spit out (render result) :encoding "UTF-8")
    (println "wrote" out
             (str "(" (count runs) " graph runs, "
                  (count ledger) " ledger facts, "
                  (count holds) " governor-holds, "
                  (count commits) " commits, "
                  (count (store/maintenance-history db)) " maintenance drafts, "
                  (count (store/shipment-history db)) " shipment drafts)"))))
