(ns frontend.models.build
  (:require [frontend.datetime :as datetime]
            [frontend.models.project :as proj]
            [goog.string :as gstring]
            goog.string.format))

;; XXX paths should use secretary
(defn path-for [project build]
  (str "/gh/" (proj/project-name project) "/" (:build_num build)))

(defn path-for-parallelism [build]
  (str "/gh/" (:username build) "/" (:reponame build) "/edit#parallel-builds"))

(defn github-revision [build]
  (when (:vcs_revision build)
    (subs (:vcs_revision build) 0 7)))

(defn github-commit-url [build]
  (when (:vcs_revision build)
    (gstring/format "%s/commit/%s" (:vcs_url build) (:vcs_revision build))))

(defn branch-in-words [build]
  (if-let [branch (:branch build)]
    (.replace branch (js/RegExp. "^remotes\\/origin\\/") "")
    "unknown"))

(defn author [build]
  (or (:author_name build) (:author_email build)))

(defn committer [build]
  (or (:committer_name build) (:committer_email build)))

(defn author-isnt-committer [{:keys [committer_email author_email committer_name author_name] :as build}]
  (or (not= committer_email author_email)
      (not= committer_name author_name)))

(defn status-icon [build]
  (cond (= "success" (:outcome build)) "fa-check"
        (= "running" (:status build)) "fa-refresh"
        (= "scheduled" (:status build)) "fa-calendar-o"
        (#{"canceled" "killed" "failed" "timedout" "no_tests"} (:status build)) "fa-times"
        (#{"queued" "not_running"} (:status build)) "fa-clock-o"
        (#{"not_run" "infrastructure_fail"} (:status build)) "fa-ban"
        :else nil))

;; XXX figure out how to update duration
(defn duration [{:keys [start_time stop_time] :as build}]
  (let [start-time (when start_time (js/Date.parse start_time))
        stop-time (when stop_time (js/Date.parse stop_time))]
    (cond (= "canceled" (:status build)) "canceled"
          (and start-time stop-time) (datetime/as-duration (- stop-time start-time))
          start-time (datetime/as-duration (- (.getTime (js/Date.)) start-time))
          :else nil)))

(defn pretty-start-time [build]
  (str (datetime/time-ago (js/Date.parse (:start_time build)))
       " ago"))

(defn finished? [build]
  (or (:stop_time build) (:canceled build)))

(defn in-usage-queue? [build]
  (and (not (finished? build))
       (not (:queued_at build))))

(defn in-run-queue? [build]
  (and (not (finished? build))
       (:queued_at build)
       (not (:start_time build))))

;; XXX figure out how to update duration
(defn run-queued-time [{:keys [start_time stop_time queued_at] :as build}]
  (cond (and start_time queued_at) (- (js/Date.parse start_time) (js/Date.parse queued_at))
        ;; canceled before left queue
        (and queued_at stop_time) (- (js/Date.parse stop_time) (js/Date.parse queued_at))
        queued_at (- (.getTime (js/Date.)) (js/Date.parse queued_at))
        :else 0))

;; XXX figure out how to update duration
(defn usage-queued-time [{:keys [stop_time queued_at usage_queued_at] :as build}]
  (cond (and usage_queued_at queued_at) (- (js/Date.parse queued_at) (js/Date.parse usage_queued_at))
        ;; canceled before left queue
        (and usage_queued_at stop_time) (- (js/Date.parse stop_time) (js/Date.parse usage_queued_at))
        usage_queued_at (- (.getTime (js/Date.)) (js/Date.parse usage_queued_at))
        :else 0))

(defn queued-time [build]
  (+ (usage-queued-time build)
     (run-queued-time build)))

;; XXX figure out how to update duration
(defn queued-time-summary [build]
  (if (> 0 (run-queued-time build))
    (gstring/format "%s waiting + %s in queue"
                    (datetime/as-duration (usage-queued-time build))
                    (datetime/as-duration (run-queued-time build)))
    (gstring/format "%s waiting for builds to finish"
                    (datetime/as-duration (usage-queued-time build)))))

(defn status-words [build]
  (condp = (:status build)
    "infrastructure_fail" "circle bug"
    "timedout" "timed out"
    "no_tests" "no tests"
    "not_run" "not run"
    "not_running" "not running"
    (:status build)))

(defn status-class [build]
  (cond (#{"failed" "timedout" "no_tests"} (:status build)) "label-important"
        (#{"infrastructure_fail" "killed" "not_run"} (:status build)) "label-warning"
        (= "success" (:outcome build)) "label-success"
        (= "running" (:status build)) "info-style"
        :else nil))

(defn why-in-words [build]
  (condp = (:why build)
    "github" (str "GitHub push by " (get-in build [:user :login]))
    "edit" "Edit of the project settings"
    "first-build" "First build"
    "retry" (str "Manual retry of build " (:retry_of build))
    "ssh" (gstring/format "Retry of build %s, with SSH enabled" (:retry_of build))
    "auto-retry" (gstring/format "Auto-retry of build %s" (:retry_of build))
    "trigger" (if (:user build)
                (gstring/format "%s on CircleCI.com" (get-in build [:user :login]))
                "CircleCI.com")
    (if (:job_name build)
      (:job_name build)
      "unknown")))

(defn can-cancel? [build]
  (and (not= "canceled" (:status build))
       (#{"not_running" "running" "queued" "scheduled"} (:lifecycle build))))

(defn ssh-enabled-now? [build]
  (and (:ssh_enabled build)
       (:node build)
       (every? :ssh_enabled (:node build))))

;; XXX display-build-invite logic
(defn display-build-invite [build]
  false)

(defn fill-steps
  "Steps can arrive out of order, but we need to maintain the indices in the :steps
  array so that we can find the step on updates."
  [build step-index]
  (let [step-count (count (:steps build))]
    (update-in build [:steps]
               (fnil (comp vec concat) [])
               (take (- step-index step-count)
                     (repeat {:filler-step true})))))

(defn fill-actions
  "Actions can arrive out of order, but we need to maintain the indices in the
  array so that we can find the action on updates. Assumes that step-index exists."
  [build step-index action-index action-log]
  (let [action-count (count (get-in build [:steps step-index :actions]))]
    (update-in build [:steps step-index :actions]
               (fnil (comp vec concat) [])
               (map (fn [i] (-> action-log
                                (select-keys [:name :type :parallel :command :bash-command :step :source])
                                (assoc :index i :status "running")))
                    (range (- action-index action-count))))))

(defn filler-step?
  "Steps can arrive out of order, but we need to maintain the indices in the :steps
  array so that we can find the step on updates."
  [step]
  (:filler-step step))

(defn containers [build]
  (let [steps (remove filler-step? (:steps build))
        parallel (:parallel build)
        actions (reduce (fn [groups step]
                          (map (fn [group action]
                                 (conj group action))
                               groups (if (> parallel (count (:actions step)))
                                        (apply concat (repeat parallel (:actions step)))
                                        (:actions step))))
                        (repeat (or parallel 1) [])
                        steps)]
    (map (fn [i actions] {:actions actions
                          :index i})
         (range) actions)))

(defn id [build]
  (:build_url build))
