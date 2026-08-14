package domain_test

import (
	"testing"

	"odds-and-evens-game/championship/domain"
)

func TestNewPlayerValid(t *testing.T) {
	player, err := domain.NewPlayer(1, "Alice")
	if err != nil {
		t.Fatalf("expected valid player, got error: %v", err)
	}
	if player.ID() != 1 {
		t.Fatalf("unexpected player ID: %d", player.ID())
	}
	if player.Name() != "Alice" {
		t.Fatalf("unexpected player name: %q", player.Name())
	}
}

func TestNewPlayerInvalidID(t *testing.T) {
	if _, err := domain.NewPlayer(0, "Alice"); err == nil {
		t.Fatal("expected error for non-positive player ID")
	}
}

func TestNewPlayerEmptyName(t *testing.T) {
	if _, err := domain.NewPlayer(1, ""); err == nil {
		t.Fatal("expected error for empty player name")
	}
}
