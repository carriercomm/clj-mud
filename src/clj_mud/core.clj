(ns clj-mud.core
  (:require [clojure.string :as string]
            [clj-mud.world :refer :all]
            [clj-mud.room :refer :all]
            [clj-mud.player :refer :all]
            [lamina.core :refer :all]
            [aleph.tcp :refer :all]
            [gloss.core :refer :all])
  (:gen-class)
  (:import clj_mud.world.PlayerHandle))

(def command-handlers (atom {})) ;; Registered command handlers

(defn send-msg
  "Send a message to the specified channel."
  [ch & message]
  (if message
    (enqueue ch (str (apply str message)))
    (enqueue ch "\n")))

(defn normalize-input
  "Normalize input by changing command shortcuts to full command names."
  [line]
  (let [trimmed-line (string/trim line)]
    (case (first trimmed-line)
      \" (str "say " (subs trimmed-line 1))
      \: (str "pose " (subs trimmed-line 1))
      ;; Default
      trimmed-line)))

(defn tokenize-command
  "Tokenize the commnd input."
  [line]
  (let [split-line (string/split (normalize-input line) #"\s" 2)]
    (if (not (empty? split-line))
      (let [trigger (keyword (first split-line))]
        (if (contains? (set (keys @command-handlers)) trigger)
          (if (= 1 (count split-line))
            (list trigger)
            (list trigger (second split-line))))))))

(defn look [ch room]
  (send-msg ch (:name @room))
  (send-msg ch "")
  (send-msg ch (:desc @room))
  (send-msg ch "")
  (send-msg ch (str "    Exits: " (string/join ", " (get-exit-names room)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Commands are stored as a map of triggers to command handlers.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn register-command
  [command handler]
  (swap! command-handlers assoc command handler))

(defn help-handler
  [ch & args]
  (send-msg ch "Oh dear. Don't be silly. Help isn't implemented yet.")
  (send-msg ch "(but seriously, type 'quit' to quit)"))

;; To be improved when multi-user support is added
(defn say-handler
  [ch args]
  (send-msg ch (str "You say, \"" args "\"")))

;; To be improved when multi-user support is added
(defn pose-handler
  [ch args]
  (send-msg ch (str "*** [" args "]")))

(defn look-handler
  [ch & args]
  (let [player (find-player-by-channel ch)]
    (look ch (player-location player))))

(defn walk-handler
  [ch direction]
  (if (nil? direction)
    (send-msg ch "Go where?")
    (let [player (find-player-by-channel ch)
          old-room (player-location player)
          exit (find-exit-by-name old-room direction)]
      (if (nil? exit)
        (send-msg ch "There's no exit in that direction!")
        (let [new-room (find-room (:to exit))]
          (move-player player new-room)
          (look ch new-room))))))

(defn connect-handler
  [ch name]
  (let [player (or (find-player-by-name name)
                   (make-player name))]
    (do
      (if (get @client-channels ch)
        (swap! (get @client-channels ch) assoc :player-id (:id @player)))
      (swap! player assoc :awake true)
      (send-msg ch (str "Welcome, " name "!"))
      player)))

(defn setup-world
  "Builds a very simple starter world. "
  []
  (register-command :help help-handler)
  (register-command :look look-handler)
  (register-command :go walk-handler)
  (register-command :say say-handler)
  (register-command :pose pose-handler)
  (register-command :connect connect-handler)

  (def wizard-den
    (make-room "The Center of the Universe" "The Room at the Center of it All"))
  (def hallway
    (make-room "Hallway" "A long hallway."))
  (def foyer
    (make-room "Foyer" "A Foyer."))
  (def bedroom
    (make-room "Bedroom" "It's a bedroom."))

  (make-exit wizard-den hallway "east")
  (make-exit hallway wizard-den "west")
  (make-exit hallway foyer "east")
  (make-exit foyer hallway "west")
  (make-exit foyer bedroom "north")
  (make-exit bedroom foyer "south"))

(defn- get-args [command]
  (if (not (nil? command))
    (second command)))

(defn- get-handler [command]
  (if (not (nil? command))
    ((first command) @command-handlers)))

(defn dispatch-command
  [ch input]
  (if (not (empty? input))
    (let [command (tokenize-command input)]
      (if (nil? command)
        (send-msg ch "Huh? (Type \"help\" for help)")
        ((get-handler command) ch (get-args command))))))

(defn read-one-line
  "The main MUD input handler"
  [ch]
  (receive-all
   ch
   (fn [input]
     (if (not (nil? input))
       (let [trimmed (string/trim input)]
         (if (= :quit (keyword trimmed))
           (close ch)
           ;; Else, dispatch the command
           (dispatch-command ch trimmed)))))))

(defn channel-connected
  ""
  [ch client-info]
  (log (str "Connection from " client-info))
  (send-msg ch "")
  (send-msg ch "---------------------------------------------------------------")
  (send-msg ch "* Welcome to this Experimental Clojure Mud!                   *")
  (send-msg ch "---------------------------------------------------------------")
  (send-msg ch "")
  (send-msg ch "To connect, type: connect <username>")
  (send-msg ch "Commands are: look, go, quit")
  (send-msg ch "")
  (send-msg ch "Ready.")
  (swap! client-channels assoc ch (atom (PlayerHandle. nil client-info))))

(defn channel-disconnected
  ""
  [ch client-info]
  (log (str "Disconnect from " client-info))
  ;; Ensure the player falls asleep
  (let [player-handle (get @client-channels ch)]
    (if (and player-handle (:player-id @player-handle))
      (let [player (find-player (:player-id @player-handle))]
        (swap! player assoc :awake false))))
  (swap! client-channels dissoc ch))

(defn client-handler [ch client-info]
  (channel-connected ch client-info)
  (on-closed ch (fn [] (channel-disconnected ch client-info)))
  (read-one-line ch))

(defn -main
  [& args]
  (log "Loading configuration...")
  (load-config "config.clj")
  (log "Setting up the world...")
  (setup-world)
  (let [numrooms (count @rooms)]
    (log (str "The world now has " numrooms
              (if (> numrooms 1) " rooms" " room"))))
  (log (str "Booting network, address=" (:bind-address @config)
            ", port=" (:port @config)))
  (try
    (start-tcp-server
     client-handler {:port (:port @config)
                     :host (:bind-address @config)
                     :frame (string :utf-8 :delimiters ["\r\n"])})
    (catch Exception e (str "caught exception: " (.getMessage e)))))
