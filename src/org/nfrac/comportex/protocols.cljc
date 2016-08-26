(ns org.nfrac.comportex.protocols
  (:require [clojure.spec :as s]
            [clojure.spec.gen :as gen]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Common specs

(s/def ::bit (-> nat-int? (s/with-gen #(s/gen (s/int-in 0 2048)))))
(s/def ::bits (s/every ::bit :distinct true))
(s/def ::bits-set (s/every ::bit :kind set?))
(s/def ::column-id (-> nat-int? (s/with-gen #(s/gen (s/int-in 0 2048)))))
(s/def ::cell-index (-> nat-int? (s/with-gen #(s/gen (s/int-in 0 32)))))
(s/def ::cell-id (s/tuple ::column-id ::cell-index))
(s/def ::seg-path (s/tuple ::column-id ::cell-index ::cell-index))
(s/def ::excitation-amt (-> (s/and number? #(<= 0 % 1e12)
                                   #(not (Double/isNaN %)))
                            (s/with-gen #(s/gen (s/int-in 0 500)))))
(s/def ::seg-exc (s/every-kv ::seg-path ::excitation-amt))
(s/def ::timestep nat-int?)

(s/def ::permanence (s/double-in :min 0.0 :max 1.0 :NaN? false))
(s/def ::segment (s/every-kv ::bit ::permanence))

(s/def ::operation #{:learn :punish :reinforce})
(s/def ::grow-sources (s/nilable ::bits))
(s/def ::die-sources (s/nilable ::bits))
(s/def ::seg-update
  (s/keys :req-un [::target-id
                   ::operation]
          :opt-un [::grow-sources
                   ::die-sources]))

(s/def ::n-synapse-targets (-> nat-int? (s/with-gen #(s/gen (s/int-in 0 2048)))))
(s/def ::n-synapse-sources (-> nat-int? (s/with-gen #(s/gen (s/int-in 0 2048)))))

(defmulti layer-spec ::layer-type)
(s/def ::layer-of-cells (s/multi-spec layer-spec ::layer-type))
(s/def ::layer-type keyword?)

(defmulti synapse-graph-spec ::synapse-graph-type)
(s/def ::synapse-graph (s/multi-spec synapse-graph-spec ::synapse-graph-type))
(s/def ::synapse-graph-type keyword?)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Util

(defprotocol PTemporal
  (timestep [this]))

(defprotocol PParameterised
  (params [this]
    "A parameter set as map with keyword keys."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Hierarchy

(defprotocol PHTM
  "A network of regions and senses, forming Hierarchical Temporal Memory."
  (htm-sense [this inval mode]
    "Takes an input value. Updates the HTM's senses by applying
    corresponding sensors to the input value. `mode` may be
    :sensory or :motor to update only such senses, or nil to update
    all. Also updates :input-value. Returns updated HTM.")
  (htm-activate [this]
    "Propagates feed-forward input through the network to activate
    columns and cells. Assumes senses have already been encoded, with
    `htm-sense`. Increments the time step. Returns updated HTM.")
  (htm-learn [this]
    "Applies learning rules to synapses. Assumes `this` has been through
    the `htm-activate` phase already. Returns updated HTM.")
  (htm-depolarise [this]
    "Propagates lateral and feed-back activity to put cells into a
    depolarised (predictive) state. Assumes `this` has been through
    the `htm-activate` phase already. Returns updated HTM."))

(defn htm-step
  "Advances a HTM by a full time step with the given input value. Just
  (-> htm (htm-sense inval nil) htm-activate htm-learn htm-depolarise)"
  [htm inval]
  (-> htm
      (htm-sense inval nil)
      (htm-activate)
      (htm-learn)
      (htm-depolarise)))

(defprotocol PRegion
  "Cortical regions need to extend this together with PTopological,
   PFeedForward, PTemporal, PParameterised."
  (region-activate [this ff-bits stable-ff-bits])
  (region-learn [this])
  (region-depolarise [this distal-ff-bits apical-fb-bits apical-fb-wc-bits]))

(defn region-step
  ([this ff-bits]
   (region-step this ff-bits #{} #{} #{} #{}))
  ([this ff-bits stable-ff-bits distal-ff-bits apical-fb-bits apical-fb-wc-bits]
   (-> this
       (region-activate ff-bits stable-ff-bits)
       (region-learn)
       (region-depolarise distal-ff-bits apical-fb-bits apical-fb-wc-bits))))

(defprotocol PFeedForward
  "A feed-forward input source with a bit set representation. Could be
   sensory input or a region (where cells are bits)."
  (ff-topology [this])
  (bits-value [this]
    "The set of indices of all active bits/cells.")
  (stable-bits-value [this]
    "The set of indices of active cells where those cells were
    predicted (so, excluding cells from bursting columns).")
  (source-of-bit [this i]
    "Given the index of an output bit from this source, return the
    corresponding local cell id as [col ci] where col is the column
    index. If the source is a sense, returns [i]."))

(defprotocol PFeedBack
  (wc-bits-value [this]
    "The set of indices of all winner cells."))

(defprotocol PFeedForwardMotor
  (ff-motor-topology [this])
  (motor-bits-value [this]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Layer of cells

(defprotocol PLayerOfCells
  (layer-activate* [this ff-bits stable-ff-bits])
  (layer-learn* [this])
  (layer-depolarise* [this distal-ff-bits apical-fb-bits apical-fb-wc-bits])
  (layer-state* [this])
  (layer-depth* [this]))

(defn layer-activate
  [this ff-bits stable-ff-bits]
  (layer-activate* this ff-bits stable-ff-bits))

(s/def ::layer-activate-args
  #_"Args spec for layer-activate, given an id here to allow generator override."
  (s/and
   (s/cat :layer ::layer-of-cells
          :ff-bits ::bits
          :stable-ff-bits ::bits)
   (fn [v]
     (let [par (params (:layer v))
           n-in (reduce * (:input-dimensions par))]
       (and (every? #(< % n-in) (:ff-bits v))
            (every? (set (:ff-bits v)) (:stable-ff-bits v)))))))

(s/fdef layer-activate
        :args ::layer-activate-args
        :fn (s/and #(= (timestep (:ret %))
                       (inc (timestep (-> % :args :layer)))))
        :ret ::layer-of-cells)

(defn layer-learn
  [this]
  (layer-learn* this))

(s/fdef layer-learn
        :args (s/cat :layer ::layer-of-cells)
        :ret ::layer-of-cells)

(defn layer-depolarise
  [this distal-ff-bits apical-fb-bits apical-fb-wc-bits]
  (layer-depolarise* this distal-ff-bits apical-fb-bits apical-fb-wc-bits))

(s/fdef layer-depolarise
        :args (s/cat :layer ::layer-of-cells
                     :distal-ff-bits ::bits
                     :apical-fb-bits ::bits
                     :apical-fb-wc-bits ::bits)
        :ret ::layer-of-cells)

(defn layer-state
  "The current information content of a layer, including the sets of active and
  predicted cells. This is a generic view to work with different implementations."
  [layer]
  (layer-state* layer))

(s/def ::active-columns (s/coll-of ::column-id :kind set?))
(s/def ::bursting-columns ::active-columns)
(s/def ::active-cells (s/coll-of ::cell-id :kind set?))
(s/def ::winner-cells
  #_"The set of winning cell ids, one in each active column. These are
  only _learning_ cells when they turn on, but are always _learnable_."
  ::active-cells)
(s/def ::predictive-cells
  #_"The set of predictive cell ids derived from the current active
  cells. Can be nil if the depolarise phase has not been applied yet."
  (s/nilable ::active-cells))
(s/def ::prior-predictive-cells
  #_"The set of predictive cell ids from the previous timestep,
  i.e. their prediction can be compared to the current active cells."
  ::active-cells)
(s/def ::layer-state
  (s/keys :req-un [::active-columns
                   ::bursting-columns
                   ::active-cells
                   ::winner-cells
                   ::predictive-cells
                   ::prior-predictive-cells]))

(s/fdef layer-state
        :args (s/cat :layer ::layer-of-cells)
        :ret ::layer-state)

(defn layer-depth
  "Number of cells per column."
  [this]
  (layer-depth* this))

(s/fdef layer-depth :ret pos-int?)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Synapse graphs

(defprotocol PSynapseGraph
  "The synaptic connections from a set of sources to a set of targets.
   Synapses have an associated permanence value between 0 and 1; above
   some permanence level they are defined to be connected."
  (in-synapses [this target-id]
    "All synapses to the target. A map from source ids to permanences.")
  (sources-connected-to [this target-id]
    "The collection of source ids actually connected to target id.")
  (targets-connected-from [this source-id]
    "The collection of target ids actually connected from source id.")
  (excitations [this active-sources stimulus-threshold]
    "Computes a map of target ids to their degree of excitation -- the
    number of sources in `active-sources` they are connected to -- excluding
    any below `stimulus-threshold`.")
  (bulk-learn* [this seg-updates active-sources pinc pdec pinit]
    "Applies learning updates to a batch of targets. `seg-updates` is
    a sequence of SegUpdate records, one for each target dendrite
    segment."))

(defn bulk-learn
  [this seg-updates active-sources pinc pdec pinit]
  (bulk-learn* this seg-updates active-sources pinc pdec pinit))

(defn valid-seg-update?
  [upd sg]
  (let [n (::n-synapse-sources sg)
        syns (in-synapses sg (:target-id upd))]
    (and (map? syns)
         (every? #(< % n) (:grow-sources upd))
         (every? #(< % n) (:die-sources upd))
         (not-any? #(contains? syns %) (:grow-sources upd))
         (every? #(contains? syns %) (:die-sources upd)))))

(s/def ::bulk-learn-args
  #_"Args spec for bulk-learn, given an id here to allow generator override."
  (s/and
   (s/cat :sg ::synapse-graph
          :seg-updates (s/and (s/every ::seg-update)
                              #(->> (map :target-id %) (apply distinct? nil)))
          :active-sources (s/or :set ::bits-set
                                :fn (s/fspec :args (s/cat :bit ::bit)))
          :pinc ::permanence
          :pdec ::permanence
          :pinit ::permanence)
   (fn [v]
     (every? #(valid-seg-update? % (:sg v)) (:seg-updates v)))))

(s/fdef bulk-learn
        :args ::bulk-learn-args
        :ret ::synapse-graph)

(defprotocol PSegments
  (cell-segments [this cell-id]
    "A vector of segments on the cell, each being a synapse map."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Sensors and Encoders

(defprotocol PSense
  "Sense nodes need to extend this together with PFeedForward."
  (sense-activate [this bits]))

(defprotocol PSelector
  "Pulls out a value according to some pattern, like a path or lens.
  Should be serializable. A Sensor is defined as [Selector Encoder]."
  (extract [this state]
    "Extracts a value from `state` according to some configured pattern. A
    simple example is a lookup by keyword in a map."))

(defprotocol PEncoder
  "Encoders need to extend this together with PTopological."
  (encode [this x]
    "Encodes `x` as a collection of distinct integers which are the on-bits.")
  (decode [this bit-votes n]
    "Finds `n` domain values matching the given bit set in a sequence
     of maps with keys `:value`, `:votes-frac`, `:votes-per-bit`,
     `:bit-coverage`, `:bit-precision`, ordered by votes fraction
     decreasing. The argument `bit-votes` is a map from encoded bit
     index to a number of votes, typically the number of synapse
     connections from predictive cells."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Etc

(defprotocol PRestartable
  (restart [this]
    "Returns this model (or model component) reverted to its initial
    state prior to any learning."))

(defprotocol PInterruptable
  (break [this mode]
    "Returns this model (or model component) without its current
    sequence state, forcing the following input to be treated as a new
    sequence. `mode` can be

    * :tm, cancels any distal predictions and prevents learning
      lateral/distal connections.
    * :fb, cancels any feedback predictions and prevents learning
      connections on apical dendrites.
    * :syns, cancels any continuing stable synapses used for temporal
      pooling in any higher layers (not `this` layer).
    * :winners, allows new winner cells to be chosen in continuing
      columns."))

(defprotocol PTopological
  (topology [this]))

(defprotocol PTopology
  "Operating on a regular grid of certain dimensions, where each
   coordinate is an n-tuple vector---or integer for 1D---and also has
   a unique integer index."
  (dimensions [this])
  (coordinates-of-index [this idx])
  (index-of-coordinates [this coord])
  (neighbours* [this coord outer-r inner-r])
  (coord-distance [this coord-a coord-b]))

(defn size
  "The total number of elements indexed in the topology."
  [topo]
  (reduce * (dimensions topo)))

(defn dims-of
  "The dimensions of a PTopological as an n-tuple vector."
  [x]
  (dimensions (topology x)))

(defn size-of
  "The total number of elements in a PTopological."
  [x]
  (size (topology x)))

(defn neighbours
  "Returns the coordinates away from `coord` at distances
  `inner-r` (exclusive) out to `outer-r` (inclusive) ."
  ([topo coord radius]
   (neighbours* topo coord radius 0))
  ([topo coord outer-r inner-r]
   (neighbours* topo coord outer-r inner-r)))

(defn neighbours-indices
  "Same as `neighbours` but taking and returning indices instead of
   coordinates."
  ([topo idx radius]
   (neighbours-indices topo idx radius 0))
  ([topo idx outer-r inner-r]
   (->> (neighbours* topo (coordinates-of-index topo idx)
                     outer-r inner-r)
        (map (partial index-of-coordinates topo)))))
