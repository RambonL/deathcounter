#!/usr/bin/env bash
# The release check for the universal jar: a production server per loader with the jar in it, two
# dev clients, every feature driven from the console and compared against what it should print.
# Afterwards each loader boots the worlds the others wrote. See TESTING.md.
#
#   scripts/loader-test.sh                    all three loaders
#   scripts/loader-test.sh paper              one of them
#   scripts/loader-test.sh fabric neoforge    any subset; the world check runs across those given
#
# Needs a display, and room: the loaders run side by side, each with a server and two Gradle dev
# clients, so six game windows and some 15 GB of memory for about four minutes. Servers are
# downloaded once into $DC_TEST_DIR (default ~/.cache/deathcounter-loader-test) and reused. Each
# loader's output is printed when all are through; it is in <dir>/<loader>/result.txt while they
# run. Exit status is the number of failed checks.
#
# What it cannot see: a non-op being refused /deathsadmin (the console outranks everyone, and so
# does `execute as`), and anything the client draws — the tab list column, clickable coordinates,
# tab completion. Those stay on the manual list in TESTING.md.
set -u

REPO=$(cd "$(dirname "$0")/.." && pwd)
DIR=${DC_TEST_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/deathcounter-loader-test}
FABRIC_INSTALLER=1.1.2
UA='deathcounter-loader-test (https://github.com/RambonL/mcmod-deathcounter)'

prop() { sed -n "s/^$1=//p" "$REPO/gradle.properties"; }
MC=$(prop minecraft_version)
VERSION=$(prop version)
JAR=$REPO/build/libs/deathcounter-$VERSION.jar

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

# --- one function per loader: where things go, how it starts, and how to get it -----------------

setup_fabric() {
	port=25601; mods=mods; cfg=config/deathcounter.json
	start=(java -Xmx2G -jar fabric-server.jar nogui)
	local api="fabric-api-$(prop fabric_api_version).jar"
	mkdir -p mods
	[ -f fabric-server.jar ] || fetch fabric-server.jar \
		"https://meta.fabricmc.net/v2/versions/loader/$MC/$(prop loader_version)/$FABRIC_INSTALLER/server/jar"
	[ -f "mods/$api" ] || fetch "mods/$api" \
		"https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$(prop fabric_api_version)/$api"
}

setup_neoforge() {
	port=25602; mods=mods; cfg=config/deathcounter.json
	start=(sh run.sh nogui)
	local v; v=$(prop neoforge_version)
	if [ ! -f run.sh ]; then
		fetch installer.jar "https://maven.neoforged.net/releases/net/neoforged/neoforge/$v/neoforge-$v-installer.jar"
		java -jar installer.jar --installServer > install.log 2>&1 || { echo "NeoForge install failed, see $PWD/install.log"; exit 99; }
	fi
}

setup_paper() {
	port=25603; mods=plugins; cfg=plugins/DeathCounter/deathcounter.json
	start=(java -Xmx2G -jar paper.jar nogui)
	# paper_version is the dev bundle, 26.3.build.159-beta; the server build is the number in it.
	local build; build=$(prop paper_version | sed -E 's/.*build\.([0-9]+).*/\1/')
	if [ ! -f "paper-$build.jar" ]; then
		local url; url=$(curl -fsSL -H "User-Agent: $UA" "https://fill.papermc.io/v3/projects/paper/versions/$MC/builds/$build" \
			| grep -o '"url":"[^"]*"' | head -1 | cut -d'"' -f4)
		[ -n "$url" ] || { echo "no download for Paper $MC build $build"; exit 99; }
		fetch "paper-$build.jar" "$url"
	fi
	ln -sf "paper-$build.jar" paper.jar
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

# One game directory and one Gradle project cache per loader and player: three Alphas run at once,
# and they would otherwise share options.txt and block each other on the project lock. The
# options come from the dev client's directory, so a fresh one does not stop at the first-run
# screens.
#
# -x: Gradle keeps its up-to-date state in the project cache, so each of these six would redo
# compileJava and processResources into the one fabric/build they all read from, and a client
# starting meanwhile finds a half-written fabric.mod.json. The build at the top already made both.
client() {
	local game="$DIR/$loader/game-$1" dev
	dev="$REPO/run/$(tr '[:upper:]' '[:lower:]' <<< "$1")/options.txt"
	mkdir -p "$game"
	[ -f "$game/options.txt" ] || [ ! -f "$dev" ] || cp "$dev" "$game/"
	( cd "$REPO" && setsid ./gradlew --project-cache-dir "$DIR/gradle-$loader-$1" ":fabric:runClient$1" \
		-x :fabric:compileJava -x :fabric:processResources \
		--args="--quickPlayMultiplayer localhost:$port --gameDir $game" > "$DIR/$loader/client-$1.log" 2>&1 & )
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
	# white-list: a fresh 26.3 server writes true and turns the dev clients away.
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
	say "difficulty peaceful"; say "gamerule immediate_respawn true"
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

# --- worlds across loaders ----------------------------------------------------------------------

# worlds <dst> <src>... — dst boots a copy of the world each src left behind and has to find 14
# and 3 in it.
worlds() {
	local dst=$1 src; shift
	loader=$dst
	cd "$DIR/$dst" || exit 99
	"setup_$dst"
	for src; do
		[ -d "$DIR/$src/world/data/deathcounter" ] || { fail "$src left no world to hand on"; continue; }
		rm -rf world.x; cp -r "../$src/world" world.x; rm -f world.x/session.lock
		LOG="world-from-$src.log"; : > "$LOG"
		if boot --world world.x; then
			expect "world written by $src loads" "deaths top" '1\. Alpha — 14 deaths' '2\. Bravo — 3 deaths'
		elif [ "$src" = paper ]; then
			# Paper stores a world in its own layout, and that is not ours to convert back.
			note "does not boot a world written by paper: $(grep -Eo 'Overworld settings missing|Failed to load datapacks' "$LOG" | head -1)"
		else
			fail "does not boot the world written by $src, see $PWD/$LOG"
		fi
		halt
		rm -rf world.x
	done
}

# --- main ---------------------------------------------------------------------------------------

# The script runs itself once per loader; these are the two things such a child does.
case ${1:-} in
	--pass)   pass "$2"; exit "$FAILED" ;;
	--worlds) shift; worlds "$@"; exit "$FAILED" ;;
esac

[ $# -gt 0 ] || set -- fabric neoforge paper
for l; do
	case $l in fabric|neoforge|paper) ;; *) echo "unknown loader: $l (fabric, neoforge, paper)"; exit 99 ;; esac
	mkdir -p "$DIR/$l"; rm -f "$DIR/$l/result.txt" "$DIR/$l/worlds.txt"
done

echo "building $(basename "$JAR")"
( cd "$REPO" && ./gradlew -q build ) || { echo "build failed"; exit 99; }

# Children stop their servers through the console when they are told to go.
trap 'kill $(jobs -p) 2>/dev/null; wait' EXIT

echo "running $* side by side, output in $DIR/<loader>/result.txt"
for l; do "$0" --pass "$l" > "$DIR/$l/result.txt" 2>&1 & done
wait
for l; do echo; echo "== $l"; cat "$DIR/$l/result.txt"; done

if [ $# -gt 1 ]; then
	for l; do
		others=(); for o; do [ "$o" = "$l" ] || others+=("$o"); done
		"$0" --worlds "$l" "${others[@]}" > "$DIR/$l/worlds.txt" 2>&1 &
	done
	wait
	for l; do echo; echo "== $l on the others' worlds"; cat "$DIR/$l/worlds.txt"; done
fi

echo; echo "== summary: $VERSION on $*"
for l; do
	for f in result worlds; do
		[ -f "$DIR/$l/$f.txt" ] && grep -E '^  (FAIL|note) ' "$DIR/$l/$f.txt" | sed "s/^  \(....\)  /  \1  $l: /"
	done
done > "$DIR/summary.txt"
cat "$DIR/summary.txt"
FAILED=$(grep -c '^  FAIL' "$DIR/summary.txt")
echo "  $FAILED failed"
exit "$FAILED"
