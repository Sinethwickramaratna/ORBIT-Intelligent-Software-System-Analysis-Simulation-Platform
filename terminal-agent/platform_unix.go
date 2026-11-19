//go:build !windows

package main

import (
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"
)

func availableShells() []Shell {
	etc, _ := os.ReadFile("/etc/shells")
	return detectUnixShells(probe{
		getenv:    os.Getenv,
		exists:    fileExists,
		lookPath:  exec.LookPath,
		etcShells: string(etc),
		goos:      runtime.GOOS,
	})
}

// killTree ends the shell and everything started in it. The shell runs as a session leader, so every process of the
// terminal - also background jobs, which interactive shells put in process groups of their own - is in its session:
// first SIGHUP to all of them (like closing a terminal window), then SIGKILL to what is left.
func killTree(pid int) {
	signalSession(pid, syscall.SIGHUP)
	go func() {
		time.Sleep(1500 * time.Millisecond)
		signalSession(pid, syscall.SIGKILL)
	}()
}

// signalSession sends sig to every process whose session id is sid.
func signalSession(sid int, sig syscall.Signal) {
	_ = syscall.Kill(-sid, sig) // the shell's own process group
	if pids, ok := sessionPidsFromProc(sid); ok {
		for _, p := range pids {
			_ = syscall.Kill(p, sig)
		}
		return
	}
	// no /proc (macOS, BSD): pkill selects by session id there
	name := "HUP"
	if sig == syscall.SIGKILL {
		name = "KILL"
	}
	_ = exec.Command("pkill", "-"+name, "-s", strconv.Itoa(sid)).Run()
}

// sessionPidsFromProc lists the processes of a session from /proc (Linux); ok=false when there is no /proc.
func sessionPidsFromProc(sid int) ([]int, bool) {
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return nil, false
	}
	var out []int
	for _, e := range entries {
		pid, err := strconv.Atoi(e.Name())
		if err != nil {
			continue
		}
		raw, err := os.ReadFile("/proc/" + e.Name() + "/stat")
		if err != nil {
			continue
		}
		if s, ok := sessionOfStat(string(raw)); ok && s == sid && pid != os.Getpid() {
			out = append(out, pid)
		}
	}
	return out, true
}

// sessionOfStat reads the session id (field 6) of a /proc/<pid>/stat line; the command name (field 2) may contain
// spaces and parentheses, so the fields are counted after its last ')'.
func sessionOfStat(stat string) (int, bool) {
	i := strings.LastIndexByte(stat, ')')
	if i < 0 {
		return 0, false
	}
	fields := strings.Fields(stat[i+1:]) // state ppid pgrp session ...
	if len(fields) < 4 {
		return 0, false
	}
	s, err := strconv.Atoi(fields[3])
	return s, err == nil
}

func fileExists(p string) bool {
	st, err := os.Stat(p)
	return err == nil && !st.IsDir()
}
