package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const testSecret = "orbit-test-secret-0123456789abcdef-XYZ"

// The same vector is checked by TerminalTicketServiceTest (Java) and was computed with Node's crypto.
func TestSigningKeyMatchesBackendVector(t *testing.T) {
	if got := hex.EncodeToString(signingKey(testSecret)); got != "c277c799c1bb3f689636169da73e114a30fb5de812201ec90beb787949e2ecab" {
		t.Fatalf("key %s", got)
	}
	payload := "eyJ2IjoxLCJ0eXAiOiJvcmJpdC10ZXJtaW5hbCIsInN1YiI6InUxIiwiY3dkIjoiRTovUHJvamVjdHMvYXBwIiwiZXhwIjo0MTAyNDQ0ODAwLCJqdGkiOiIwMGZmIn0"
	c, err := verifyTicket(payload+".bBcThKJPDGl5x-u3sG8g8sui2Tso7uXg2uJFi4eKMrQ", testSecret, time.Unix(4102444000, 0))
	if err != nil {
		t.Fatal(err)
	}
	if c.Cwd != "E:/Projects/app" || c.Sub != "u1" || c.Jti != "00ff" {
		t.Fatalf("claims %+v", c)
	}
}

func makeTicket(secret string, c map[string]any) string {
	raw, _ := json.Marshal(c)
	p := base64.RawURLEncoding.EncodeToString(raw)
	m := hmac.New(sha256.New, signingKey(secret))
	m.Write([]byte(p))
	return p + "." + base64.RawURLEncoding.EncodeToString(m.Sum(nil))
}

func claims(exp int64) map[string]any {
	return map[string]any{"v": 1, "typ": "orbit-terminal", "sub": "u", "cwd": "/p", "exp": exp, "jti": "abc"}
}

func TestTicketChecks(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	good := makeTicket(testSecret, claims(now.Unix()+60))
	if _, err := verifyTicket(good, testSecret, now); err != nil {
		t.Fatalf("good ticket refused: %v", err)
	}
	cases := map[string]struct {
		ticket, secret string
		want           error
	}{
		"expired":       {makeTicket(testSecret, claims(now.Unix())), testSecret, errTicketExpired},
		"wrong secret":  {good, testSecret + "x", errTicketSignature},
		"no secret":     {good, "", errNoSecret},
		"tampered":      {strings.Replace(good, good[:5], "eyJ3I", 1), testSecret, errTicketSignature},
		"not a ticket":  {"abc", testSecret, errTicketMalformed},
		"empty":         {"", testSecret, errTicketMalformed},
		"access token":  {makeTicket(testSecret, map[string]any{"v": 1, "typ": "access", "sub": "u", "cwd": "/p", "exp": now.Unix() + 60, "jti": "x"}), testSecret, errTicketType},
		"missing cwd":   {makeTicket(testSecret, map[string]any{"v": 1, "typ": "orbit-terminal", "sub": "u", "exp": now.Unix() + 60, "jti": "x"}), testSecret, errTicketMalformed},
		"other version": {makeTicket(testSecret, map[string]any{"v": 2, "typ": "orbit-terminal", "sub": "u", "cwd": "/p", "exp": now.Unix() + 60, "jti": "x"}), testSecret, errTicketType},
	}
	for name, c := range cases {
		if _, err := verifyTicket(c.ticket, c.secret, now); !errors.Is(err, c.want) {
			t.Errorf("%s: got %v, want %v", name, err, c.want)
		}
	}
}

func TestTicketIsSingleUse(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	cache := newTicketCache()
	c := &Claims{Jti: "one", Exp: now.Unix() + 60}
	if !cache.claim(c, now) {
		t.Fatal("first use refused")
	}
	if cache.claim(c, now.Add(time.Second)) {
		t.Fatal("second use accepted")
	}
	if !cache.claim(&Claims{Jti: "two", Exp: now.Unix() + 60}, now) {
		t.Fatal("another ticket refused")
	}
	cache.claim(&Claims{Jti: "three", Exp: now.Unix() + 60}, now.Add(2*time.Minute)) // prunes expired ids
	if len(cache.used) != 1 {
		t.Fatalf("expired ids kept: %v", cache.used)
	}
}

func TestOriginCheck(t *testing.T) {
	allowed := []string{"http://localhost:3000", "http://127.0.0.1:3000/"}
	for _, ok := range []string{"http://localhost:3000", "http://LOCALHOST:3000", "http://127.0.0.1:3000"} {
		if !originAllowed(ok, allowed) {
			t.Errorf("%s refused", ok)
		}
	}
	for _, bad := range []string{"", "null", "http://evil.example", "http://localhost:3001", "https://localhost:3000", "http://localhost:3000.evil.example"} {
		if originAllowed(bad, allowed) {
			t.Errorf("%s accepted", bad)
		}
	}
}

func TestReadEnvValue(t *testing.T) {
	dir := t.TempDir()
	env := filepath.Join(dir, ".env")
	_ = os.WriteFile(env, []byte("# c\nJWT_SECRET=\nDB=x\nexport JWT_SECRET = \"s3cret value\" \nOTHER=1\n"), 0o600)
	if got := readEnvValue(env, "JWT_SECRET"); got != "s3cret value" {
		t.Fatalf("got %q", got)
	}
	if got := readEnvValue(filepath.Join(dir, "missing"), "JWT_SECRET"); got != "" {
		t.Fatalf("missing file gave %q", got)
	}
	t.Setenv("JWT_SECRET", "from-env")
	if got := readSecret(env); got != "from-env" {
		t.Fatalf("environment should win, got %q", got)
	}
}

func TestResolveCwd(t *testing.T) {
	root := t.TempDir()
	_ = os.MkdirAll(filepath.Join(root, "projects", "app"), 0o755)
	_ = os.WriteFile(filepath.Join(root, "file.txt"), []byte("x"), 0o600)
	if got, err := resolveCwd("./projects/app", root); err != nil || got != filepath.Join(root, "projects", "app") {
		t.Fatalf("relative: %q %v", got, err)
	}
	abs := filepath.Join(root, "projects")
	if got, err := resolveCwd(filepath.ToSlash(abs), "/elsewhere"); err != nil || got != abs {
		t.Fatalf("absolute: %q %v", got, err)
	}
	if _, err := resolveCwd("./missing", root); err == nil {
		t.Fatal("missing folder accepted")
	}
	if _, err := resolveCwd("./file.txt", root); err == nil {
		t.Fatal("a file accepted as folder")
	}
}

func fakeFS(files ...string) func(string) bool {
	set := map[string]bool{}
	for _, f := range files {
		set[strings.ToLower(f)] = true
	}
	return func(p string) bool { return set[strings.ToLower(p)] }
}

func ids(shells []Shell) string {
	var out []string
	for _, s := range shells {
		out = append(out, s.ID)
	}
	return strings.Join(out, ",")
}

func TestWindowsShellDetection(t *testing.T) {
	env := map[string]string{"SystemRoot": `C:\Windows`, "ComSpec": `C:\Windows\system32\cmd.exe`, "ProgramFiles": `C:\Program Files`}
	full := probe{
		getenv: func(k string) string { return env[k] },
		exists: fakeFS(`C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe`, `C:\Windows\system32\cmd.exe`,
			`C:\Program Files\PowerShell\7\pwsh.exe`, `D:\Tools\Git\bin\bash.exe`, `C:\Windows\System32\wsl.exe`),
		lookPath: func(n string) (string, error) {
			if n == "git.exe" {
				return `D:\Tools\Git\cmd\git.exe`, nil
			}
			return "", errors.New("not found")
		},
		wslHasDistro: func() bool { return true },
	}
	got := detectWindowsShells(full)
	if ids(got) != "powershell,pwsh,cmd,git-bash,wsl" {
		t.Fatalf("got %s", ids(got))
	}
	if got[3].Path != `D:\Tools\Git\bin\bash.exe` || got[3].Env[0] != "CHERE_INVOKING=1" {
		t.Fatalf("git bash %+v", got[3])
	}
	if a := got[4].CwdArgs(`E:\p`); strings.Join(a, " ") != `--cd E:\p` {
		t.Fatalf("wsl args %v", a)
	}

	// a plain Windows: no PowerShell 7, no Git, WSL installed without a distribution
	plain := full
	plain.exists = fakeFS(`C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe`, `C:\Windows\system32\cmd.exe`, `C:\Windows\System32\wsl.exe`)
	plain.lookPath = func(string) (string, error) { return "", errors.New("no") }
	plain.wslHasDistro = func() bool { return false }
	if got := ids(detectWindowsShells(plain)); got != "powershell,cmd" {
		t.Fatalf("plain: %s", got)
	}
}

func TestUnixShellDetection(t *testing.T) {
	p := probe{
		getenv:    func(k string) string { return map[string]string{"SHELL": "/bin/zsh"}[k] },
		exists:    fakeFS("/bin/sh", "/bin/bash", "/usr/bin/bash", "/bin/zsh", "/usr/bin/fish"),
		etcShells: "# /etc/shells\n/bin/sh\n/usr/bin/bash\n/bin/bash\n/bin/zsh\n/usr/bin/fish\n/usr/bin/tmux\n/bin/missing\n",
		goos:      "darwin",
	}
	got := detectUnixShells(p)
	if ids(got) != "zsh,sh,bash,fish" {
		t.Fatalf("got %s", ids(got))
	}
	if got[0].Args[0] != "-l" {
		t.Fatal("macOS shells should be login shells")
	}
	for _, s := range got {
		if s.ID == "bash" && s.Path != "/bin/bash" {
			t.Fatalf("bash should be the /bin copy, got %s", s.Path)
		}
	}
	p.goos = "linux"
	if got := detectUnixShells(p); len(got[0].Args) != 0 {
		t.Fatal("Linux shells start without -l")
	}
}
