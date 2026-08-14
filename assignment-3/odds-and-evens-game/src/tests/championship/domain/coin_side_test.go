package domain_test

import (
	"testing"

	"odds-and-evens-game/championship/domain"
)

func TestParseCoinSideValid(t *testing.T) {
	tests := []struct {
		input string
		want  domain.CoinSide
	}{
		{input: "heads", want: domain.Heads},
		{input: "tails", want: domain.Tails},
	}

	for _, tt := range tests {
		got, err := domain.ParseCoinSide(tt.input)
		if err != nil {
			t.Fatalf("expected valid coin side %q, got error: %v", tt.input, err)
		}
		if got != tt.want {
			t.Fatalf("unexpected coin side for %q: got %q want %q", tt.input, got, tt.want)
		}
	}
}

func TestParseCoinSideInvalid(t *testing.T) {
	if _, err := domain.ParseCoinSide("edge"); err == nil {
		t.Fatal("expected error for invalid coin side")
	}
}
