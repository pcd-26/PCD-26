package domain

import "fmt"

// Parity identifies the winning outcome of a match.
type Parity string

const (
	// Odd means the odd player wins the match.
	Odd Parity = "odd"
	// Even means the even player wins the match.
	Even Parity = "even"
)

// ParseParity validates and converts a textual parity to a Parity value.
func ParseParity(value string) (Parity, error) {
	switch Parity(value) {
	case Odd, Even:
		return Parity(value), nil
	default:
		return "", fmt.Errorf("invalid parity: %q", value)
	}
}
