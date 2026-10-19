#!/bin/zsh
# THE iPHONES' DIARIES, READ ONLY (the author's word 19.10.2026 19:3x: «Разрешаю читать дневники iPhone в папке diag на
# Lauterbourg только для чтения»). Each iPhone ships its diary to Lauterbourg, one folder a device under DIAG; this script only
# lists and reads there -- it writes, moves and deletes nothing. T1 (8BB8BC71…) stays out of analysis (memory: no-t1-analysis).
#
#   scripts/ios-diary.sh list [N]               the newest N folders (default 15): name, last change, files
#   scripts/ios-diary.sh files ID               the files of one folder
#   scripts/ios-diary.sh grep ID PATTERN [N]    the last N lines (default 60) matching an extended regex, every file of the folder
#   scripts/ios-diary.sh tail ID FILE [N]       the last N lines (default 80) of one file
set -eu
HOST=montana-lauterbourg
DIAG=/var/lib/montana-node-wake/diag
id_ok() { [[ "$1" =~ '^[0-9A-Fa-f-]{8,36}$' ]] || { echo "bad folder id" >&2; exit 2; } }
num_ok() { [[ "$1" =~ '^[0-9]{1,5}$' ]] || { echo "bad number" >&2; exit 2; } }
cmd=${1:-list}
case $cmd in
  list)
    n=${2:-15}; num_ok $n
    ssh $HOST "cd $DIAG && ls -t | head -n $n | while read d; do printf '%s  %s  %s files\n' \"\$d\" \"\$(date -r \"\$d\" '+%d.%m %H:%M %Z')\" \"\$(ls \"\$d\" | wc -l)\"; done" ;;
  files)
    id_ok $2
    ssh $HOST "cd $DIAG && d=\$(ls -d $2* | head -n 1) && ls -lt \"\$d\"" ;;
  grep)
    id_ok $2; n=${4:-60}; num_ok $n
    p=$(printf %s "$3" | base64)
    ssh $HOST "cd $DIAG && d=\$(ls -d $2* | head -n 1) && p=\$(printf %s '$p' | base64 -d) && cat \"\$d\"/* 2>/dev/null | grep -a -E -- \"\$p\" | tail -n $n" ;;
  tail)
    id_ok $2; [[ "$3" =~ '^[A-Za-z0-9._-]{1,80}$' ]] || { echo "bad file name" >&2; exit 2; }; n=${4:-80}; num_ok $n
    ssh $HOST "cd $DIAG && d=\$(ls -d $2* | head -n 1) && tail -n $n \"\$d\"/'$3'" ;;
  *) echo "usage: ios-diary.sh list|files|grep|tail …" >&2; exit 2 ;;
esac
