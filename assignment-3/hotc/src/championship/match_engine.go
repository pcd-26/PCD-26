package championship

import (
	"fmt"
	"math/rand"
	"time"
)

// CoinTosser abstracts a coin toss source.
type CoinTosser interface {
	Toss() CoinSide
}

// RandomCoinTosser produces random tosses for production use.
type RandomCoinTosser struct {
	rng *rand.Rand
}

// NewRandomCoinTosser creates an independent random toss generator.
func NewRandomCoinTosser() RandomCoinTosser {
	return RandomCoinTosser{
		rng: rand.New(rand.NewSource(time.Now().UnixNano())),
	}
}

// Toss returns a random coin side.
func (r RandomCoinTosser) Toss() CoinSide {
	if r.rng.Intn(2) == 0 {
		return Heads
	}
	return Tails
}

// FixedCoinTosser always returns the same toss result.
type FixedCoinTosser struct {
	side CoinSide
}

// NewFixedCoinTosser creates a deterministic tosser for tests.
func NewFixedCoinTosser(side CoinSide) FixedCoinTosser {
	return FixedCoinTosser{side: side}
}

// Toss returns the configured side.
func (f FixedCoinTosser) Toss() CoinSide {
	return f.side
}

// PlayMatch resolves a single match between exactly two players.
func PlayMatch(roundNumber, matchNumber int, tosser CoinTosser, firstPlayer, secondPlayer Player) (MatchResult, error) {
	if tosser == nil {
		return MatchResult{}, fmt.Errorf("coin tosser must not be nil")
	}
	if err := validatePlayer(firstPlayer); err != nil {
		return MatchResult{}, fmt.Errorf("first player is invalid: %w", err)
	}
	if err := validatePlayer(secondPlayer); err != nil {
		return MatchResult{}, fmt.Errorf("second player is invalid: %w", err)
	}

	tossedSide := tosser.Toss()
	switch tossedSide {
	case Heads:
		return NewMatchResult(roundNumber, matchNumber, firstPlayer, secondPlayer, firstPlayer, tossedSide)
	case Tails:
		return NewMatchResult(roundNumber, matchNumber, firstPlayer, secondPlayer, secondPlayer, tossedSide)
	default:
		return MatchResult{}, fmt.Errorf("invalid toss result: %q", tossedSide)
	}
}

func validatePlayer(player Player) error {
	if player.ID() <= 0 {
		return fmt.Errorf("player ID must be positive: %d", player.ID())
	}
	if player.Name() == "" {
		return fmt.Errorf("player name must not be empty")
	}
	return nil
}
