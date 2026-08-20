package domain_test

import (
	"testing"

	"odds-and-evens-game/championship/domain"
)

// The parser should accept the two legal parity values.
func TestParseParityValid(t *testing.T) {
	tests := []struct {
		input string
		want  domain.Parity
	}{
		{input: "odd", want: domain.Odd},
		{input: "even", want: domain.Even},
	}

	for _, tt := range tests {
		got, err := domain.ParseParity(tt.input)
		if err != nil {
			t.Fatalf("expected valid parity %q, got error: %v", tt.input, err)
		}
		if got != tt.want {
			t.Fatalf("unexpected parity for %q: got %q want %q", tt.input, got, tt.want)
		}
	}
}

// Any other value must fail fast.
func TestParseParityInvalid(t *testing.T) {
	if _, err := domain.ParseParity("edge"); err == nil {
		t.Fatal("expected error for invalid parity")
	}
}
