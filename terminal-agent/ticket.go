package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"time"
)

// A ticket is issued by the ORBIT backend (POST /api/projects/{id}/terminal/ticket):
//   base64url(json) "." base64url(HMAC-SHA256(key, base64url(json)))
// with key = HMAC-SHA256(JWT_SECRET, "orbit-terminal-ticket-v1") - see TerminalTicketService.java.

const ticketKeyLabel = "orbit-terminal-ticket-v1"
const ticketType = "orbit-terminal"

// Claims of a terminal ticket.
type Claims struct {
	V   int    `json:"v"`
	Typ string `json:"typ"`
	Sub string `json:"sub"`
	Cwd string `json:"cwd"`
	Exp int64  `json:"exp"`
	Jti string `json:"jti"`
}

var (
	errTicketMalformed = errors.New("the terminal ticket is malformed")
	errTicketSignature = errors.New("the terminal ticket signature is wrong")
	errTicketExpired   = errors.New("the terminal ticket has expired")
	errTicketType      = errors.New("this is not a terminal ticket")
	errTicketReused    = errors.New("the terminal ticket was already used")
	errNoSecret        = errors.New("JWT_SECRET is not configured yet (finish the ORBIT setup first)")
)

func signingKey(secret string) []byte {
	m := hmac.New(sha256.New, []byte(secret))
	m.Write([]byte(ticketKeyLabel))
	return m.Sum(nil)
}

// verifyTicket checks signature, type and expiry. It does not mark the ticket as used (see ticketCache).
func verifyTicket(ticket, secret string, now time.Time) (*Claims, error) {
	if secret == "" {
		return nil, errNoSecret
	}
	parts := strings.Split(ticket, ".")
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		return nil, errTicketMalformed
	}
	got, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, errTicketMalformed
	}
	m := hmac.New(sha256.New, signingKey(secret))
	m.Write([]byte(parts[0]))
	if !hmac.Equal(got, m.Sum(nil)) {
		return nil, errTicketSignature
	}
	raw, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return nil, errTicketMalformed
	}
	var c Claims
	if err := json.Unmarshal(raw, &c); err != nil {
		return nil, errTicketMalformed
	}
	if c.Typ != ticketType || c.V != 1 {
		return nil, errTicketType
	}
	if c.Jti == "" || c.Cwd == "" || c.Sub == "" {
		return nil, errTicketMalformed
	}
	if now.Unix() >= c.Exp {
		return nil, errTicketExpired
	}
	return &c, nil
}

// ticketCache remembers used ticket ids until they expire, so every ticket opens at most one terminal.
type ticketCache struct {
	mu   sync.Mutex
	used map[string]int64
}

func newTicketCache() *ticketCache { return &ticketCache{used: map[string]int64{}} }

// claim returns false when the ticket id was used before.
func (t *ticketCache) claim(c *Claims, now time.Time) bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	for id, exp := range t.used {
		if now.Unix() >= exp {
			delete(t.used, id)
		}
	}
	if _, seen := t.used[c.Jti]; seen {
		return false
	}
	t.used[c.Jti] = c.Exp
	return true
}
