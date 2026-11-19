package main

import (
	"path"
	"sort"
	"strings"
)

// Shell is one terminal type the user can pick (like VS Code's terminal profiles).
type Shell struct {
	ID   string   `json:"id"`
	Name string   `json:"name"`
	Path string   `json:"path"`
	Args []string `json:"-"`
	// Env is added to the environment of the shell (e.g. keep Git Bash in the project folder).
	Env []string `json:"-"`
	// CwdArgs are put before Args when the shell needs the folder as an argument (WSL).
	CwdArgs func(cwd string) []string `json:"-"`
}

// probe answers questions about the computer; the real one is in shells_windows.go / shells_unix.go, tests fake it.
type probe struct {
	getenv   func(string) string
	exists   func(string) bool
	lookPath func(string) (string, error)
	// wslHasDistro reports whether WSL is installed with at least one Linux distribution.
	wslHasDistro func() bool
	// etcShells is the content of /etc/shells (Unix).
	etcShells string
	goos      string
}

// detectWindowsShells lists cmd, Windows PowerShell, PowerShell 7, Git Bash, WSL and MSYS2 / Cygwin bash when present.
// The first entry is the default (PowerShell, as in VS Code; cmd when PowerShell is missing).
func detectWindowsShells(p probe) []Shell {
	var shells []Shell
	add := func(s Shell) {
		for _, x := range shells {
			if strings.EqualFold(x.Path, s.Path) || x.ID == s.ID {
				return
			}
		}
		shells = append(shells, s)
	}
	sysRoot := p.getenv("SystemRoot")
	if sysRoot == "" {
		sysRoot = `C:\Windows`
	}
	first := func(candidates ...string) string {
		for _, c := range candidates {
			if c != "" && p.exists(c) {
				return c
			}
		}
		return ""
	}
	look := func(name string) string {
		if p.lookPath == nil {
			return ""
		}
		if found, err := p.lookPath(name); err == nil {
			return found
		}
		return ""
	}

	if ps := first(sysRoot + `\System32\WindowsPowerShell\v1.0\powershell.exe`); ps != "" {
		add(Shell{ID: "powershell", Name: "Windows PowerShell", Path: ps, Args: []string{"-NoLogo"}})
	}
	pf := p.getenv("ProgramFiles")
	if pwsh := first(look("pwsh.exe"), pf+`\PowerShell\7\pwsh.exe`, pf+`\PowerShell\7-preview\pwsh.exe`); pwsh != "" {
		add(Shell{ID: "pwsh", Name: "PowerShell 7", Path: pwsh, Args: []string{"-NoLogo"}})
	}
	if cmd := first(p.getenv("ComSpec"), sysRoot+`\System32\cmd.exe`); cmd != "" {
		add(Shell{ID: "cmd", Name: "Command Prompt", Path: cmd})
	}
	// Git Bash: next to git.exe on the PATH (…\Git\cmd\git.exe -> …\Git\bin\bash.exe) or in the usual install folders
	gitBash := ""
	if git := look("git.exe"); git != "" {
		gitBash = first(winDir(winDir(git)) + `\bin\bash.exe`)
	}
	if gitBash == "" {
		gitBash = first(pf+`\Git\bin\bash.exe`, p.getenv("ProgramFiles(x86)")+`\Git\bin\bash.exe`,
			p.getenv("LocalAppData")+`\Programs\Git\bin\bash.exe`)
	}
	if gitBash != "" {
		add(Shell{ID: "git-bash", Name: "Git Bash", Path: gitBash, Args: []string{"--login", "-i"}, Env: []string{"CHERE_INVOKING=1"}})
	}
	if wsl := first(sysRoot + `\System32\wsl.exe`); wsl != "" && p.wslHasDistro != nil && p.wslHasDistro() {
		add(Shell{ID: "wsl", Name: "WSL", Path: wsl, CwdArgs: func(cwd string) []string { return []string{"--cd", cwd} }})
	}
	if msys := first(`C:\msys64\usr\bin\bash.exe`); msys != "" {
		add(Shell{ID: "msys2", Name: "MSYS2 Bash", Path: msys, Args: []string{"--login", "-i"}, Env: []string{"CHERE_INVOKING=1", "MSYSTEM=UCRT64"}})
	}
	if cyg := first(`C:\cygwin64\bin\bash.exe`); cyg != "" {
		add(Shell{ID: "cygwin", Name: "Cygwin Bash", Path: cyg, Args: []string{"--login", "-i"}, Env: []string{"CHERE_INVOKING=1"}})
	}
	return shells
}

// winDir is filepath.Dir for Windows paths on any OS (so the detection can be tested anywhere).
func winDir(p string) string {
	i := strings.LastIndexAny(p, `\/`)
	if i <= 0 {
		return p
	}
	return p[:i]
}

var unixShellNames = map[string]string{
	"bash": "bash", "zsh": "zsh", "fish": "fish", "sh": "sh", "dash": "dash", "ksh": "ksh", "tcsh": "tcsh", "csh": "csh",
	"pwsh": "PowerShell", "nu": "Nushell", "elvish": "Elvish", "xonsh": "Xonsh", "ash": "ash", "mksh": "mksh",
}

// detectUnixShells lists the shells of /etc/shells that exist (one per name), with $SHELL first as the default.
func detectUnixShells(p probe) []Shell {
	var shells []Shell
	seen := map[string]bool{}
	add := func(full string) {
		name := path.Base(full)
		label, known := unixShellNames[name]
		if !known || seen[name] || !p.exists(full) {
			return
		}
		seen[name] = true
		var args []string
		// macOS terminals start login shells (as Terminal.app and VS Code do), Linux ones interactive shells
		if p.goos == "darwin" && (name == "bash" || name == "zsh" || name == "fish" || name == "sh") {
			args = []string{"-l"}
		}
		shells = append(shells, Shell{ID: name, Name: label, Path: full, Args: args})
	}
	if def := p.getenv("SHELL"); def != "" {
		add(def)
	}
	var listed []string
	for _, line := range strings.Split(p.etcShells, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		listed = append(listed, line)
	}
	// prefer the /usr/bin or /bin copy over others with the same name, in a stable order
	sort.SliceStable(listed, func(i, j int) bool { return len(listed[i]) < len(listed[j]) })
	for _, l := range listed {
		add(l)
	}
	for _, fallback := range []string{"/bin/bash", "/bin/zsh", "/bin/sh"} {
		add(fallback)
	}
	if p.lookPath != nil {
		if pwsh, err := p.lookPath("pwsh"); err == nil {
			add(pwsh)
		}
	}
	return shells
}
