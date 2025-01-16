Taskcontrol
===========

Ever wanted to control which tasks are scheduled on your system via a neat API?
This project is for you!

## Usage

```sh
./scheduler.sh
```

Then use the following APIs:

```
GET localhost:PORT/task/{id} to get the status of a task
GET localhost:PORT/task/{id}?stopping=true|false to stop or resume a task
GET localhost:PORT/task/{id}?lotteryPriority=N positive priority for the LotteryScheduler (larger the better)
GET localhost:PORT/task/plan/{id}?plan=s10,r10 to set the plan for a task (e.g. 10s running, 10s stopped)
GET localhost:PORT/task/plan/{id} to get the current plan for a task
GET localhost:PORT/plans the current plans as JSON

The same for taskGroup (process)
```

Be aware that stopping a task for more than 30s will kill the scheduler.

You can select multiple schedulers via `./scheduler.sh` or set the server port:

```sh
Usage: scheduler [-hV] [-p=<port>] [-s=<schedulerType>]
A FIFO scheduler with a rest API to stop tasks
  -h, --help          Show this help message and exit.
  -p, --port=<port>   The port to listen on
  -s, --scheduler=<schedulerType>
                      The scheduler to use: fifo, lottery
  -V, --version       Print version information and exit.
```

Currently only the FIFO scheduler is implemented.

## Example

Start the scheduler at the default port 8087:
```sh
./scheduler.sh
```

In another terminal start a task, e.g. the Ticker Java program which prints `Tick n` every second:
```sh
java samples/Ticker.java &
```
Now take the PID of the Ticker program and stop it via the API:
```sh
curl "localhost:8087/taskGroup/$(pgrep -f Ticker)?stopping=true"
```
It should now stop being rescheduled and therefore stop emitting `Tick n`.

You can resume it via:
```
curl "localhost:8087/taskGroup/$(pgrep -f Ticker)?stopping=false"
```

You can also set a sequence of stopping and running times via:
```
curl "localhost:8087/taskGroup/plan/$(pgrep -f Ticker)?plan=10s,5r,10s,5s"
```

This will stop the tasks of the task group for 10s, run it for 5s, stop it for 10s and stop it for 5s.

## Java Example

See [Main.java](src/main/java/me/bechberger/taskcontrol/Main.java) for an example on how to use the scheduler in Java.

```java
Thread clockThread = new ClockThread();
clockThread.start();
ThreadControl threadControl = new ThreadControl();
threadControl.stopThread(clockThread);
System.out.println("ClockThread status: " + threadControl.getThreadStatus(clockThread));
Thread.sleep(10000);
threadControl.resumeThread(clockThread);
```

## Implementation

The scheduler consists of two parts:

- The scheduler server
  - runs with root privileges
  - starts the scheduler
  - opens a server with an HTTP API (implemented with Javalin)
- The client in Java
  - allows to directly interact with the scheduler on Java thread level

The schedulers basically check every time they want to schedule a
task whether the task is stopped or not:

![Scheduler](img/stoppable_scheduler.png)

## Install

Install a 6.12 (or later) kernel, on Ubuntu use [mainline](https://github.com/bkw777/mainline) if you're on Ubuntu 24.10 or older.

You should also have installed:

- `libbpf-dev`
- clang
- Java 23

Now you just have to build the taskcontrol via:

```sh
mvn package
```

License
=======
GPLv2