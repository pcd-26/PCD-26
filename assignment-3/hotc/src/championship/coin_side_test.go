package championship

import "testing"

func TestParseCoinSideValid(t *testing.T) {
	tests := []struct {
		input string
		want  CoinSide
	}{
		{input: "heads", want: Heads},
		{input: "tails", want: Tails},
	}

	for _, tt := range tests {
		got, err := ParseCoinSide(tt.input)
		if err != nil {
			t.Fatalf("expected valid coin side %q, got error: %v", tt.input, err)
		}
		if got != tt.want {
			t.Fatalf("unexpected coin side for %q: got %q want %q", tt.input, got, tt.want)
		}
	}
}

func TestParseCoinSideInvalid(t *testing.T) {
	if _, err := ParseCoinSide("edge"); err == nil {
		t.Fatal("expected error for invalid coin side")
	}
}
