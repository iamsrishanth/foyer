#!/system/bin/sh
# Stops any previous Foyer input helper. toybox pkill has no working -f here, so find the PIDs by
# command line and kill them explicitly. Prints how many listeners are left (0 = clean).
for p in $(ps -A -o PID,ARGS | grep -E 'foyer-inputd\.sh|foyer-input-handle\.sh|nc .*-p 8486' \
           | grep -v grep | awk '{print $1}'); do
  kill "$p" 2>/dev/null
done
sleep 0.4
ps -A -o PID,ARGS | grep -E 'nc .*-p 8486' | grep -v grep | wc -l
