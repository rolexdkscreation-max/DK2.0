R=/home/user/workspace/DK2.0/app/src/main/java/com/dk/assistant
export JAVA_HOME=/tmp/jdk PATH=/tmp/jdk/bin:/tmp/kotlinc/bin:$PATH
cd /tmp/bench; rm -rf src2; mkdir src2; { echo "package com.dk.assistant"; sed -n '/^object TurnEnd {/,/^}/p' $R/LiveReliable.kt; } > src2/TurnEndX.kt; cp src/EndGateX.kt $R/VoiceTurn.kt src2/
kotlinc src2/*.kt -d new.jar 2>&1 | grep error; cd h; kotlinc Bench.kt NewPol.kt -cp ../new.jar -d bench2.jar 2>&1 | grep error
java -Dstdout.encoding=UTF-8 -cp ../new.jar:bench2.jar:/tmp/kotlinc/lib/kotlin-stdlib.jar com.dk.assistant.BenchKt ${CASES:-../cases.tsv} "$@"
