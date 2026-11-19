package main

import (
	"bufio"
	"net/url"
	"os"
	"path/filepath"
	"strings"
)

// readSecret returns JWT_SECRET from the environment, else from the ORBIT .env file. It is read again for every
// terminal, so the helper keeps working when the secret is entered in the setup screen after it was started.
func readSecret(envFile string) string {
	if v := strings.TrimSpace(os.Getenv("JWT_SECRET")); v != "" {
		return v
	}
	return readEnvValue(envFile, "JWT_SECRET")
}

// readEnvValue reads KEY=VALUE from a .env file (comments, "export " and surrounding quotes are handled).
func readEnvValue(envFile, key string) string {
	f, err := os.Open(envFile)
	if err != nil {
		return ""
	}
	defer f.Close()
	value := ""
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		line = strings.TrimPrefix(line, "export ")
		eq := strings.IndexByte(line, '=')
		if eq <= 0 || strings.TrimSpace(line[:eq]) != key {
			continue
		}
		v := strings.TrimSpace(line[eq+1:])
		if len(v) >= 2 && (v[0] == '"' && v[len(v)-1] == '"' || v[0] == '\'' && v[len(v)-1] == '\'') {
			v = v[1 : len(v)-1]
		}
		value = strings.TrimSpace(v) // the last definition wins, like dotenv
	}
	return value
}

// originAllowed: only the ORBIT web app may open terminals (a browser always sends Origin with WebSockets, so any
// other web page you visit is refused even before the ticket is checked).
func originAllowed(origin string, allowed []string) bool {
	if origin == "" {
		return false
	}
	u, err := url.Parse(origin)
	if err != nil || u.Host == "" {
		return false
	}
	norm := strings.ToLower(u.Scheme + "://" + u.Host)
	for _, a := range allowed {
		if strings.ToLower(strings.TrimRight(strings.TrimSpace(a), "/")) == norm {
			return true
		}
	}
	return false
}

func splitList(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

// resolveCwd turns the project folder of a ticket into a directory on this computer. A relative folder
// (./projects/app - the default Docker mount) is relative to the ORBIT folder.
func resolveCwd(cwd, root string) (string, error) {
	p := filepath.FromSlash(cwd)
	if !filepath.IsAbs(p) {
		p = filepath.Join(root, p)
	}
	p = filepath.Clean(p)
	st, err := os.Stat(p)
	if err != nil {
		return "", err
	}
	if !st.IsDir() {
		return "", os.ErrNotExist
	}
	return p, nil
}
