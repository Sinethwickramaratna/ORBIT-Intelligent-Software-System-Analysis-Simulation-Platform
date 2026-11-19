// ORBIT terminal helper: runs the terminals of ORBIT's bottom panel on the user's own computer.
//
// The ORBIT backend may run in Docker, where it could only offer a Linux shell without the user's tools. This small
// program runs on the computer itself (start.cmd / start.sh build and start it), finds the shells installed there
// (cmd, PowerShell, Git Bash, WSL, bash, zsh, ...) and connects them to the browser over a WebSocket.
//
// Safety: it listens on 127.0.0.1 only; terminals open only with a one-time ticket that the backend signs for a
// signed-in user's own project (the terminal starts in that project folder); and only the ORBIT web app's origin may
// connect.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/aymanbagabas/go-pty"
	"github.com/coder/websocket"
)

const version = "1.0.0"

type server struct {
	envFile string
	root    string
	origins []string
	tickets *ticketCache
	shells  func() []Shell

	mu       sync.Mutex
	sessions map[*session]struct{}
}

func main() {
	listen := flag.String("listen", envOr("ORBIT_TERMINAL_LISTEN", "127.0.0.1:8091"), "address to listen on (keep it on 127.0.0.1)")
	root := flag.String("root", ".", "the ORBIT folder (holds .env; relative project folders are relative to it)")
	origins := flag.String("origins", "", "comma-separated web app origins allowed to open terminals "+
		"(default: ORBIT_TERMINAL_ORIGINS from the environment or .env, else http://localhost:3000)")
	logFile := flag.String("log", "", "write the log to this file instead of the console (it is restarted above 1 MB)")
	flag.Parse()

	if *logFile != "" {
		if st, err := os.Stat(*logFile); err == nil && st.Size() > 1<<20 {
			_ = os.Remove(*logFile)
		}
		if f, err := os.OpenFile(*logFile, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600); err == nil {
			log.SetOutput(f)
			defer f.Close()
		}
	}

	absRoot, err := filepath.Abs(*root)
	if err != nil {
		log.Fatal(err)
	}
	envFile := filepath.Join(absRoot, ".env")
	allowed := *origins
	if allowed == "" {
		allowed = envOr("ORBIT_TERMINAL_ORIGINS", readEnvValue(envFile, "ORBIT_TERMINAL_ORIGINS"))
	}
	if allowed == "" {
		allowed = "http://localhost:3000,http://127.0.0.1:3000"
	}
	s := &server{
		envFile:  envFile,
		root:     absRoot,
		origins:  splitList(allowed),
		tickets:  newTicketCache(),
		shells:   availableShells,
		sessions: map[*session]struct{}{},
	}
	host, _, _ := net.SplitHostPort(*listen)
	if ip := net.ParseIP(host); host != "localhost" && (ip == nil || !ip.IsLoopback()) {
		log.Printf("WARNING: listening on %s - terminals should only be reachable from this computer (use 127.0.0.1)", *listen)
	}
	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		log.Fatalf("cannot listen on %s: %v (is the ORBIT terminal helper already running?)", *listen, err)
	}
	srv := &http.Server{Handler: s.routes(), ReadHeaderTimeout: 10 * time.Second}
	log.Printf("ORBIT terminal helper %s on http://%s (%s/%s), ORBIT folder %s, origins %v", version, *listen, runtime.GOOS, runtime.GOARCH, absRoot, s.origins)

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-stop
		s.closeAll()
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_ = srv.Shutdown(ctx)
	}()
	if err := srv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}

func envOr(key, def string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return def
}

func (s *server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", s.cors(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, map[string]any{"ok": true, "version": version, "os": runtime.GOOS, "arch": runtime.GOARCH})
	}))
	mux.HandleFunc("/shells", s.cors(func(w http.ResponseWriter, r *http.Request) {
		list := s.shells()
		def := ""
		if len(list) > 0 {
			def = list[0].ID
		}
		writeJSON(w, map[string]any{"default": def, "shells": list})
	}))
	mux.HandleFunc("/terminal", s.terminal)
	return mux
}

// cors answers the ORBIT web app only; other origins get no CORS headers, so their pages cannot read the answer.
func (s *server) cors(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if o := r.Header.Get("Origin"); o != "" {
			if !originAllowed(o, s.origins) {
				http.Error(w, "origin not allowed", http.StatusForbidden)
				return
			}
			w.Header().Set("Access-Control-Allow-Origin", o)
			w.Header().Set("Vary", "Origin")
			// Chrome's Private Network Access preflight for pages that call localhost
			w.Header().Set("Access-Control-Allow-Private-Network", "true")
		}
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		if r.Method != http.MethodGet {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		h(w, r)
	}
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(v)
}

// ---- one terminal = one WebSocket = one shell process attached to a pseudo-terminal ----

type session struct {
	pty  pty.Pty
	cmd  *pty.Cmd
	once sync.Once
}

func (ss *session) close() {
	ss.once.Do(func() {
		if ss.cmd != nil && ss.cmd.Process != nil {
			killTree(ss.cmd.Process.Pid)
		}
		_ = ss.pty.Close()
	})
}

func (s *server) closeAll() {
	s.mu.Lock()
	defer s.mu.Unlock()
	for ss := range s.sessions {
		ss.close()
	}
}

// clientMessage: text frames from the browser. Keystrokes may also come as binary frames.
type clientMessage struct {
	Type string `json:"type"` // "input" | "resize"
	Data string `json:"data"`
	Cols int    `json:"cols"`
	Rows int    `json:"rows"`
}

func (s *server) terminal(w http.ResponseWriter, r *http.Request) {
	if !originAllowed(r.Header.Get("Origin"), s.origins) {
		http.Error(w, "origin not allowed", http.StatusForbidden)
		return
	}
	q := r.URL.Query()
	claims, err := verifyTicket(q.Get("ticket"), readSecret(s.envFile), time.Now())
	if err != nil {
		http.Error(w, err.Error(), http.StatusUnauthorized)
		return
	}
	if !s.tickets.claim(claims, time.Now()) {
		http.Error(w, errTicketReused.Error(), http.StatusUnauthorized)
		return
	}
	cwd, err := resolveCwd(claims.Cwd, s.root)
	if err != nil {
		http.Error(w, "the project folder "+claims.Cwd+" does not exist on this computer", http.StatusNotFound)
		return
	}
	shells := s.shells()
	var shell *Shell
	for i := range shells {
		if shells[i].ID == q.Get("shell") || (q.Get("shell") == "" && i == 0) {
			shell = &shells[i]
			break
		}
	}
	if shell == nil {
		http.Error(w, "that shell is not available on this computer", http.StatusNotFound)
		return
	}
	cols, rows := clamp(atoi(q.Get("cols"), 80), 2, 1000), clamp(atoi(q.Get("rows"), 24), 2, 500)

	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true}) // origin checked above
	if err != nil {
		return
	}
	conn.SetReadLimit(1 << 20)
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()

	p, err := pty.New()
	if err != nil {
		_ = conn.Close(websocket.StatusInternalError, "cannot create a pseudo-terminal: "+err.Error())
		return
	}
	_ = p.Resize(cols, rows)
	args := append([]string{}, shell.Args...)
	if shell.CwdArgs != nil {
		args = append(shell.CwdArgs(cwd), args...)
	}
	cmd := p.Command(shell.Path, args...)
	cmd.Dir = cwd
	cmd.Env = append(os.Environ(), "TERM=xterm-256color", "COLORTERM=truecolor", "TERM_PROGRAM=ORBIT")
	cmd.Env = append(cmd.Env, shell.Env...)
	if err := cmd.Start(); err != nil {
		_ = p.Close()
		_ = conn.Close(websocket.StatusInternalError, "cannot start "+shell.Name+": "+err.Error())
		return
	}
	ss := &session{pty: p, cmd: cmd}
	s.mu.Lock()
	s.sessions[ss] = struct{}{}
	s.mu.Unlock()
	defer func() {
		ss.close()
		s.mu.Lock()
		delete(s.sessions, ss)
		s.mu.Unlock()
	}()
	log.Printf("terminal %d: %s in %s", cmd.Process.Pid, shell.Name, cwd)

	ready, _ := json.Marshal(map[string]any{"type": "ready", "shell": shell.ID, "name": shell.Name, "cwd": cwd, "pid": cmd.Process.Pid})
	_ = conn.Write(ctx, websocket.MessageText, ready)

	// shell output -> browser (binary frames; the browser decodes UTF-8 across frame borders)
	outDone := make(chan struct{})
	go func() {
		defer close(outDone)
		buf := make([]byte, 32*1024)
		for {
			n, err := p.Read(buf)
			if n > 0 {
				if werr := conn.Write(ctx, websocket.MessageBinary, buf[:n]); werr != nil {
					return
				}
			}
			if err != nil {
				return
			}
		}
	}()
	// shell exit -> tell the browser and close
	exited := make(chan int, 1)
	go func() {
		code := 0
		if err := cmd.Wait(); err != nil {
			code = -1
			if cmd.ProcessState != nil {
				code = cmd.ProcessState.ExitCode()
			}
		}
		exited <- code
	}()
	// browser -> shell
	inErr := make(chan error, 1)
	go func() {
		for {
			typ, data, err := conn.Read(ctx)
			if err != nil {
				inErr <- err
				return
			}
			if typ == websocket.MessageBinary {
				_, _ = p.Write(data)
				continue
			}
			var m clientMessage
			if json.Unmarshal(data, &m) != nil {
				continue
			}
			switch m.Type {
			case "input":
				_, _ = io.WriteString(p, m.Data)
			case "resize":
				_ = p.Resize(clamp(m.Cols, 2, 1000), clamp(m.Rows, 2, 500))
			}
		}
	}()

	select {
	case code := <-exited:
		select { // let the last output through
		case <-outDone:
		case <-time.After(300 * time.Millisecond):
		}
		msg, _ := json.Marshal(map[string]any{"type": "exit", "code": code})
		_ = conn.Write(ctx, websocket.MessageText, msg)
		_ = conn.Close(websocket.StatusNormalClosure, "shell exited")
		log.Printf("terminal %d: shell exited (%d)", cmd.Process.Pid, code)
	case <-inErr: // tab closed / project closed / browser gone: end the shell and what runs in it
		log.Printf("terminal %d: closed by the browser", cmd.Process.Pid)
	}
}

func atoi(s string, def int) int {
	if n, err := strconv.Atoi(s); err == nil {
		return n
	}
	return def
}

func clamp(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}
