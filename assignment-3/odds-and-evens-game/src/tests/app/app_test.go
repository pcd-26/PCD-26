package app_test

import (
	"bytes"
	"strings"
	"testing"

	"odds-and-evens-game/app"
	"odds-and-evens-game/championship/domain"
)

// These tests exercise the public CLI helpers from the outside.
func TestParsePlayersCountValid(t *testing.T) {
	count, err := app.ParsePlayersCount([]string{"-players", "8"})
	if err != nil {
		t.Fatalf("expected valid players flag, got error: %v", err)
	}
	if count != 8 {
		t.Fatalf("unexpected players count: got %d want %d", count, 8)
	}
}

// This checks the validation for zero, which must be rejected.
func TestParsePlayersCountRejectsZero(t *testing.T) {
	_, err := app.ParsePlayersCount([]string{"-players", "0"})
	if err == nil {
		t.Fatal("expected error for zero players")
	}
}

// This checks the validation for non power-of-two player counts.
func TestParsePlayersCountRejectsNonPowerOfTwo(t *testing.T) {
	_, err := app.ParsePlayersCount([]string{"-players", "3"})
	if err == nil {
		t.Fatal("expected error for non power-of-two players")
	}
}

// This verifies the full CLI flow with a deterministic parity hook.
func TestRunFormatsEightPlayerChampionship(t *testing.T) {
	stdout := &bytes.Buffer{}
	stderr := &bytes.Buffer{}

	exitCode := app.Run([]string{"-players", "8"}, stdout, stderr, func() domain.Parity { return domain.Odd })
	if exitCode != 0 {
		t.Fatalf("expected zero exit code, got %d, stderr=%s", exitCode, stderr.String())
	}

	output := stdout.String()
	if !strings.Contains(output, "Round 1") || !strings.Contains(output, "Round 2") || !strings.Contains(output, "Round 3") {
		t.Fatalf("expected three rounds in output, got:\n%s", output)
	}
	if strings.Count(output, "Match ") != 7 {
		t.Fatalf("expected seven matches in output, got:\n%s", output)
	}
	if strings.Count(output, "Winner:") != 7 {
		t.Fatalf("expected seven winners in output, got:\n%s", output)
	}
	if strings.Count(output, "Parity: odd") != 7 {
		t.Fatalf("expected odd parity output for every match, got:\n%s", output)
	}
	if !strings.Contains(output, "Champion:") {
		t.Fatalf("expected champion line in output, got:\n%s", output)
	}
}

// This verifies that invalid input is reported as a CLI error.
func TestRunRejectsInvalidInput(t *testing.T) {
	stdout := &bytes.Buffer{}
	stderr := &bytes.Buffer{}

	exitCode := app.Run([]string{"-players", "3"}, stdout, stderr, func() domain.Parity { return domain.Odd })
	if exitCode == 0 {
		t.Fatal("expected non-zero exit code")
	}
	if !strings.Contains(stderr.String(), "power of two") {
		t.Fatalf("expected validation error, got stderr=%q", stderr.String())
	}
}
