package main

import "testing"

func TestApplicationName(t *testing.T) {
	if applicationName != "Heads-or-Tails Championship" {
		t.Fatalf("unexpected application name: %q", applicationName)
	}
}
