#!/usr/bin/sh

# Starts the scheduler server a port 8087

sudo -E PATH=$PATH zsh -c "java --enable-native-access=ALL-UNNAMED -cp target/taskcontrol-0.1-SNAPSHOT-jar-with-dependencies.jar me.bechberger.taskcontrol.SchedulerServer $@"
