package championship

import "fmt"

// CoinSide identifies the side tossed in a match.
type CoinSide string

const (
	// Heads represents the heads side.
	Heads CoinSide = "heads"
	// Tails represents the tails side.
	Tails CoinSide = "tails"
)

// ParseCoinSide validates and converts a textual side to a CoinSide value.
func ParseCoinSide(value string) (CoinSide, error) {
	switch CoinSide(value) {
	case Heads, Tails:
		return CoinSide(value), nil
	default:
		return "", fmt.Errorf("invalid coin side: %q", value)
	}
}
