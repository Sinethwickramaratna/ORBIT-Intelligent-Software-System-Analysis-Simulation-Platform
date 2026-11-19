//go:build !windows

package main

import "testing"

func TestSessionOfStat(t *testing.T) {
	cases := map[string]int{
		"22 (sleep) S 1 22 21 0 -1 4194560 92 0":         21,
		"40 (my (odd) name) R 39 40 39 34816 40 4194304": 39,
	}
	for line, want := range cases {
		if got, ok := sessionOfStat(line); !ok || got != want {
			t.Errorf("%q: got %d %v, want %d", line, got, ok, want)
		}
	}
	if _, ok := sessionOfStat("garbage"); ok {
		t.Error("garbage accepted")
	}
}
