#!/usr/bin/env bash
# The release check for the 1.20.1 jar: a production Forge server with the reobfuscated jar in it,
# two dev clients running Forge without the mod (as a modpack player's game would), every feature
# driven from the console and compared against what it should print. See CLAUDE.md.
#
#   scripts/loader-test.sh
#
# Needs a display: a server and two game windows for about three minutes. The Forge server is
# installed once into $DC_TEST_DIR (default ~/.cache/deathcounter-loader-test-1.20.1) and reused.
# Exit status is the number of failed checks.
#
# What it cannot see: a non-op being refused /deathsadmin (the console outranks everyone, and so
# does `execute as`), and anything the client draws — the tab list column, clickable coordinates,
# tab completion. Those stay manual, see CLAUDE.md.
set -u

REPO=$(cd "$(dirname "$0")/.." && pwd)
DIR=${DC_TEST_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/deathcounter-loader-test-1.20.1}
# Forge 1.20.1 runs on Java 17, whatever `java` on the PATH is.
JAVA17=${JAVA17:-/usr/lib/jvm/java-17-openjdk}
UA='deathcounter-loader-test (https://github.com/RambonL/mcmod-deathcounter)'

prop() { sed -n "s/^$1=//p" "$REPO/gradle.properties"; }
MC=$(prop minecraft_version)
VERSION=$(prop version)
# build/libs holds the reobfuscated jar, the one Forge can run. build/devlibs does not.
JAR=$REPO/forge/build/libs/deathcounter-forge-$VERSION.jar

# Three numbers, the way the mod prints a position.
POS='-?[0-9]+ -?[0-9]+ -?[0-9]+'

FAILED=0
SRV=
HOLD=
FIFO=
LOG=

fetch() {
	echo "fetching $1"
	curl -fsSL --retry 2 -H "User-Agent: $UA" -o "$1" "$2" || { echo "download failed: $2"; exit 99; }
}

# --- the server ---------------------------------------------------------------------------------

setup_forge() {
	port=25604; mods=mods; cfg=config/deathcounter.json
	start=(env "PATH=$JAVA17/bin:$PATH" sh run.sh nogui)
	local v; v="$MC-$(prop forge_version)"
	if [ ! -f run.sh ]; then
		fetch installer.jar "https://maven.minecraftforge.net/net/minecraftforge/forge/$v/forge-$v-installer.jar"
		"$JAVA17/bin/java" -jar installer.jar --installServer > install.log 2>&1 || { echo "Forge install failed, see $PWD/install.log"; exit 99; }
	fi
}

# --- server and clients -------------------------------------------------------------------------

# Never killed: a server that dies without saving looks exactly like a persistence bug. The console
# gets `stop`, through a FIFO that a sleeping writer keeps open so stdin never sees EOF.
boot() {
	rm -f in.fifo; mkfifo in.fifo; FIFO=$PWD/in.fifo
	sleep 3600 > in.fifo & HOLD=$!
	local before; before=$(grep -c 'Done (' "$LOG" 2>/dev/null); before=${before:-0}
	"${start[@]}" "$@" < in.fifo >> "$LOG" 2>&1 & SRV=$!
	for _ in $(seq 180); do
		[ "$(grep -c 'Done (' "$LOG")" -gt "$before" ] && return 0
		kill -0 "$SRV" 2>/dev/null || return 1
		sleep 1
	done
	return 1
}

halt() {
	local rc=0
	if [ -n "$SRV" ]; then
		kill -0 "$SRV" 2>/dev/null && echo stop > "$FIFO"
		wait "$SRV"; rc=$?
	fi
	[ -n "$HOLD" ] && kill "$HOLD" 2>/dev/null
	SRV=; HOLD=
	return $rc
}

# One Gradle project cache per player: two clients at once would otherwise block each other on the
# project lock. Each client keeps its own game directory, run-1.20.1/alpha and run-1.20.1/bravo.
#
# Gradle keeps its up-to-date state in the project cache, so each client would redo the tasks they
# share into the one forge/build both read from, and the second would rewrite the Minecraft jars
# under the first one's running game. prepare() runs those tasks once per cache, one cache after
# the other, so that by the time the clients start in parallel there is nothing left to redo.
# Skipping them with -x instead does not work: the run's preparation reads their outputs.
prepare() {
	local p
	for p in Alpha Bravo; do
		( cd "$REPO" && ./gradlew -q --project-cache-dir "$DIR/gradle-$p" \
			:forge:downloadAssets ":forge:prepareClient${p}Run" ) || return 1
	done
}

client() {
	( cd "$REPO" && setsid ./gradlew --project-cache-dir "$DIR/gradle-$1" ":forge:runClient$1" \
		-Pjoin="localhost:$port" > "$DIR/$loader/client-$1.log" 2>&1 & )
}

# The brackets keep the pattern from matching the shell that runs pkill.
close_clients() { pkill -f "[q]uickPlayMultiplayer localhost:$port"; sleep 2; }

trap 'halt; [ -n "${port:-}" ] && close_clients' EXIT
trap 'exit 130' INT TERM

# --- checks -------------------------------------------------------------------------------------

lines() { wc -l < "$LOG"; }

# await <from-line> <seconds> <regex>... — waits until the log past that line holds every regex.
# The matched stretch of log is left in OUT, colour codes stripped.
await() {
	local from=$1 tries=$(( $2 * 5 )) re ok; shift 2
	for _ in $(seq "$tries"); do
		sleep 0.2
		OUT=$(tail -n "+$((from + 1))" "$LOG" | sed -E 's/\x1b\[[0-9;]*m//g')
		ok=1
		for re; do
			case $re in '!'*) ;; *) grep -Eq -- "$re" <<< "$OUT" || ok=0 ;; esac
		done
		[ $ok = 1 ] && return 0
	done
	return 1
}

ok()    { echo "  ok    $1"; }
note()  { echo "  note  $1"; }
fail()  { echo "  FAIL  $1"; FAILED=$((FAILED + 1)); }
say()   { echo "$1" > "$FIFO"; }

# expect <what> <command> <regex>... — runs a console command and checks what it prints. A regex
# with a leading ! must not appear; give at least one that must, or there is nothing to wait for.
expect() {
	local what=$1 cmd=$2 from re bad=; shift 2
	from=$(lines)
	say "$cmd"
	await "$from" 6 "$@" || bad="missing output"
	for re; do
		case $re in '!'*) grep -Eq -- "${re#!}" <<< "$OUT" && bad="unwanted: ${re#!}" ;; esac
	done
	if [ -z "$bad" ]; then ok "$what"; else
		fail "$what — $bad"
		echo "        > $cmd"; sed 's/^/        | /' <<< "$OUT" | tail -8
	fi
}

# is_alive <player> — @e only selects the living, where a name or @a also finds the corpse.
is_alive() {
	local from; from=$(lines)
	say "execute if entity @e[type=minecraft:player,name=$1]"
	await "$from" 2 'Test (passed|failed)' && grep -q 'Test passed' <<< "$OUT"
}

# alive <player> — waits for the client to have respawned. Until then `kill` reports "Killed" and
# nothing dies. How long that takes is the client's and the server's business, so it is asked for
# rather than slept through.
alive() {
	for _ in $(seq 40); do
		is_alive "$1" && { sleep 0.5; return 0; }
		sleep 0.5
	done
	fail "$1 did not respawn"
	return 1
}

# die <player> <n> <command> [regex] — one death, and the count it has to bring.
die() {
	local who=$1 n=$2 cmd=$3 from; shift 3
	for _ in 1 2; do
		alive "$who" || return
		# The invulnerability after a respawn swallows `damage`, though not `kill`.
		case $cmd in damage*) sleep 4 ;; esac
		from=$(lines)
		say "$cmd"
		if await "$from" 8 "$who — death #$n( at $POS.*)?\$" "$@"; then ok "death #$n of $who: $cmd"; return; fi
		# On Paper a `kill` straight after a death by `damage` can report "Killed" and kill nobody,
		# alive or not; why is not known. Once more then — but only if vanilla saw no death either.
		# A death vanilla announced and we did not count is exactly what this is for.
		grep -q "$who was killed" <<< "$OUT" && break
	done
	fail "death #$n of $who: $cmd"
	sed 's/^/        | /' <<< "$OUT" | tail -5
}

# --- the pass -----------------------------------------------------------------------------------

pass() {
	loader=$1
	cd "$DIR/$loader" || exit 99
	"setup_$loader"
	mkdir -p "$mods"; rm -f "$mods"/deathcounter-*.jar; cp "$JAR" "$mods/"
	rm -rf world world.x "$cfg" client-*.log
	echo eula=true > eula.txt
	# white-list off, so a server.properties left over with it on cannot turn the dev clients away.
	printf '%s\n' "server-port=$port" online-mode=false white-list=false 'level-type=minecraft\:flat' > server.properties
	LOG=console.log; : > "$LOG"

	boot || { fail "server does not start, see $PWD/$LOG"; halt; return; }
	ok "server starts"
	[ -f "$cfg" ] && ok "$cfg created" || fail "$cfg not created"

	local from; from=$(lines)
	client Alpha; client Bravo
	await "$from" 400 'Alpha joined the game' 'Bravo joined the game' || { fail "clients did not join"; halt; close_clients; return; }
	sleep 6

	# Peaceful keeps slimes on the flat world from adding deaths of their own.
	say "difficulty peaceful"; say "gamerule doImmediateRespawn true"
	expect "objective exists" "scoreboard objectives list" '\[Deaths\]'
	expect "empty leaderboard" "deaths top" 'Nobody has died yet\.'
	expect "player with no deaths" "execute as Alpha run deaths" 'Alpha has never died\.'

	# The first death also settles where our line lands relative to vanilla's.
	from=$(lines)
	die Alpha 1 "kill Alpha" 'Alpha was killed'
	local ours theirs
	ours=$(grep -n 'Alpha — death #1' <<< "$OUT" | head -1 | cut -d: -f1)
	theirs=$(grep -n 'Alpha was killed' <<< "$OUT" | head -1 | cut -d: -f1)
	[ "${ours:-0}" -gt "${theirs:-0}" ] || note "\"death #N\" is printed above vanilla's death message (console-caused death)"

	die Alpha 2 "damage Alpha 1000 minecraft:lava" 'tried to swim in lava'
	die Alpha 3 "damage Alpha 1000 minecraft:fall" 'hit the ground too hard'
	die Alpha 4 "damage Alpha 1000 minecraft:magic" 'killed by magic'
	die Alpha 5 "damage Alpha 1000 minecraft:player_attack by Bravo" 'slain by Bravo'
	local n
	for n in 6 7 8 9 10 11 12 13; do die Alpha "$n" "kill Alpha"; done
	die Bravo 1 "kill Bravo"
	die Bravo 2 "kill Bravo"

	expect "leaderboard" "deaths top" '1\. Alpha — 13 deaths' '2\. Bravo — 2 deaths'
	expect "breakdown by cause" "deaths Alpha" 'Alpha — 13 deaths' '9× generic kill' '1× lava' '1× fall' '1× magic' '1× player'
	expect "own total as a player" "execute as Bravo run deaths" 'Bravo — 2 deaths'

	expect "score follows the count" "scoreboard players get Alpha deathcounter" 'Alpha has 13 \[Deaths\]'
	say "scoreboard players set Alpha deathcounter 99"
	die Alpha 14 "kill Alpha"
	expect "falsified score overwritten on death" "scoreboard players get Alpha deathcounter" 'Alpha has 14 \[Deaths\]'

	expect "SELF: default"               "deathsadmin config coords"                  'coordVisibility = SELF'
	expect "SELF: own coordinates"       "execute as Alpha run deaths last"           "#14 .* Alpha was killed  $POS\$"
	expect "SELF: not another player's"  "execute as Bravo run deaths last Alpha"     '#14 .* Alpha was killed$' "!was killed  $POS"
	expect "SELF: not the console"       "deaths last Alpha"                          '#14 .* Alpha was killed$' "!was killed  $POS"
	expect "SELF: deathsadmin always"    "deathsadmin last Alpha"                     "#14 .* Alpha was killed  $POS\$"

	expect "history page 1"              "execute as Alpha run deaths history"        '#14 ' '#5 ' 'Page 1/2 — next: /deaths history Alpha 2' '!#4 '
	expect "history page 2"              "deaths history Alpha 2"                     '#4 ' '#1 ' 'Page 2/2' '!#5 '
	expect "a page past the end is the last" "deaths history Alpha 99"                '#1 ' 'Page 2/2'

	expect "PUBLIC: set"                 "deathsadmin config coords public"           'coordVisibility is now PUBLIC'
	expect "PUBLIC: another player's"    "execute as Bravo run deaths last Alpha"     "#14 .* Alpha was killed  $POS\$"
	# The nether roof: bedrock underfoot, no lava and nothing to suffocate in, so the only death up
	# there is ours. It is Bravo's last one on purpose: on Paper 26.3 build 159 a player killed in
	# the nether never respawns with immediate_respawn on, with or without this plugin installed,
	# and nothing below needs Bravo alive.
	alive Bravo; say "execute in minecraft:the_nether run tp Bravo 8 128 8"; sleep 3
	die Bravo 3 "kill Bravo" "Bravo — death #3 at $POS \\(the_nether\\)\$"
	expect "nether death carries the dimension" "deaths last Bravo"                   "#3 .* Bravo was killed  $POS \\(the_nether\\)\$"
	sleep 5; is_alive Bravo || note "a player killed in the nether does not respawn"
	expect "HIDDEN: set"                 "deathsadmin config coords hidden"           'coordVisibility is now HIDDEN'
	expect "HIDDEN: not even one's own"  "execute as Alpha run deaths last"           '#14 .* Alpha was killed$' "!was killed  $POS"
	expect "HIDDEN: deathsadmin always"  "deathsadmin last Alpha"                     "#14 .* Alpha was killed  $POS\$"
	[ "$(tr -d '\n ' < "$cfg")" = '{"coordVisibility":"HIDDEN"}' ] && ok "config file written" || fail "config file not written: $(cat "$cfg")"

	echo '{"coordVisibility":"SELF"}' > "$cfg"
	expect "reload after a hand edit"    "deathsadmin config reload"                  'Config reloaded, coordVisibility = SELF'
	echo '{"coordVisibility":"NOPE"}' > "$cfg"
	expect "broken file keeps the value" "deathsadmin config reload"                  'no valid coordVisibility, keeping SELF' 'Config reloaded, coordVisibility = SELF'
	say "deathsadmin config coords self"

	expect "tp across dimensions"        "execute as Alpha run deathsadmin tp Bravo 3" 'Teleported to death #3 of Bravo'
	sleep 2
	expect "tp arrived"                  "data get entity Alpha Dimension"            'minecraft:the_nether'
	expect "tp refused for the console"  "deathsadmin tp Bravo 3"                     'A player is required'
	expect "tp refused for a bad number" "execute as Alpha run deathsadmin tp Bravo 50" 'Bravo has only 3 deaths'
	say "execute in minecraft:overworld run tp Alpha 0 -60 0"; sleep 2

	expect "reset preview"               "deathsadmin reset Bravo"                    'Would wipe 3 deaths of Bravo'
	expect "preview wipes nothing"       "deaths Bravo"                               'Bravo — 3 deaths'
	expect "reset confirm"               "deathsadmin reset Bravo confirm"            'Wiped 3 deaths of Bravo'
	expect "reset: gone"                 "deaths Bravo"                               'Bravo has never died\.'
	expect "reset: score back to 0"      "scoreboard players get Bravo deathcounter"  'Bravo has 0 \[Deaths\]'

	# The import goes by the statistics files on disk to know who has ever played, and Bravo's is
	# only written on a save. The server thread answers nothing until that is through.
	from=$(lines); say "save-all"
	await "$from" 120 'Saved the game' || fail "save-all did not finish"
	expect "import preview"              "deathsadmin import"                         'Import preview' '\+3 Bravo' '!Alpha'
	expect "preview imports nothing"     "deaths Bravo"                               'Bravo has never died\.'
	expect "import confirm"              "deathsadmin import confirm"                 'Imported from vanilla statistics' '\+3 Bravo'
	expect "imported deaths have no time" "deathsadmin history Bravo"                 '#3 \?\?-\?\? \?\?:\?\?  Bravo died' '#1 '
	expect "imported deaths have no place" "execute as Alpha run deathsadmin tp Bravo 1" 'Death #1 was imported and has no location'
	expect "import: score follows"       "scoreboard players get Bravo deathcounter"  'Bravo has 3 \[Deaths\]'
	expect "second import finds nothing" "deathsadmin import"                         'Nothing to import'

	say "kick Bravo"; sleep 2
	expect "offline player lookup"       "deaths Bravo"                               'Bravo — 3 deaths'
	expect "offline player on the board" "deaths top"                                 '1\. Alpha — 14 deaths' '2\. Bravo — 3 deaths'

	# Wiped now and not after the restart, where it would race the client coming back in.
	say "scoreboard players reset Alpha deathcounter"; sleep 1
	halt && ok "clean stop" || fail "server exit status $?"
	close_clients

	# The rejoining client is started first: it takes longer to come up than the server does.
	from=$(lines); client Alpha
	boot || { fail "server does not restart, see $PWD/$LOG"; halt; return; }
	expect "restart: deaths survive"     "deaths top"                                 '1\. Alpha — 14 deaths' '2\. Bravo — 3 deaths'
	expect "restart: history survives"   "deathsadmin last Alpha"                     "#14 .* Alpha was killed  $POS\$"
	expect "restart: config survives"    "deathsadmin config coords"                  'coordVisibility = SELF'
	if await "$from" 400 'Alpha joined the game'; then
		sleep 3
		expect "score re-synced on join" "scoreboard players get Alpha deathcounter" 'Alpha has 14 \[Deaths\]'
	else
		fail "client did not rejoin"
	fi
	halt && ok "clean stop" || fail "server exit status $?"
	close_clients

	# Anything of ours that blew up without a check noticing.
	if grep -Eq 'Command exception|Error loading saved data|NoSuchMethodError|rambonl\.deathcounter.*Exception' "$LOG"; then
		fail "errors in $PWD/$LOG"
		grep -E 'Command exception|Error loading saved data|NoSuchMethodError' "$LOG" | sort | uniq -c | sed 's/^/        | /' | head -5
	else
		ok "no errors of ours in the log"
	fi
}

# --- main ---------------------------------------------------------------------------------------

mkdir -p "$DIR/forge"

echo "building $(basename "$JAR")"
( cd "$REPO" && ./gradlew -q build ) || { echo "build failed"; exit 99; }
prepare || { echo "preparing the clients failed"; exit 99; }

pass forge
echo; echo "== summary: $VERSION on Forge $MC"
echo "  $FAILED failed"
exit "$FAILED"
