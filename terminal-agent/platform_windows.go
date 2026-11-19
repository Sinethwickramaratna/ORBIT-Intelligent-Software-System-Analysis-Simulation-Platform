//go:build windows

package main

import (
	"context"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"syscall"
	"time"
)

func availableShells() []Shell {
	return detectWindowsShells(probe{
		getenv:   os.Getenv,
		exists:   fileExists,
		lookPath: exec.LookPath,
		wslHasDistro: func() bool {
			// "wsl -l -q" lists the installed distributions (UTF-16); it fails or prints nothing when there are none
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			cmd := exec.CommandContext(ctx, os.Getenv("SystemRoot")+`\System32\wsl.exe`, "-l", "-q")
			cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000} // CREATE_NO_WINDOW
			out, err := cmd.Output()
			if err != nil {
				return false
			}
			return strings.TrimSpace(strings.ReplaceAll(string(out), "\x00", "")) != ""
		},
		goos: "windows",
	})
}

// killTree ends the shell and everything started from it (a dev server, a build, ...).
func killTree(pid int) {
	cmd := exec.Command("taskkill", "/T", "/F", "/PID", strconv.Itoa(pid))
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	_ = cmd.Run()
}

func fileExists(p string) bool {
	st, err := os.Stat(p)
	return err == nil && !st.IsDir()
}
