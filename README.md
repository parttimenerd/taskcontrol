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
GET localhost:8087/help prints you this help
GET localhost:8087/task/{id} to get the status of a task
GET localhost:8087/task/{id}?stopping=true|false to stop or resume a task
GET localhost:8087/taskGroup/{id} to get the status of a task group (i.e. process)
GET localhost:8087/taskGroup/{id}?stopping=true|false to stop or resume a task group
```

## Install

Install a 6.12 kernel

On Ubuntu:

```sh
sudo add-apt-repository ppa:canonical-kernel-team/unstable
```

And replace `oracular` with `plucky` in `/etc/apt/sources.list.d/canonical-kernel-team-ubuntu-unstable-oracular.sources`.
Then run:

```sh
sudo apt update
sudo apt install linux-cloud-tools-6.12.0-3-generic
```

Get a current version of bpftool by downloading it from [GitHub](https://github.com/libbpf/bpftool/releases)
and storing the location of the binary in `$PROJECT/.bpftool.path`.

You should also have install:

- `libbpf-dev`
- clang
- Java 23

License
=======
GPLv2