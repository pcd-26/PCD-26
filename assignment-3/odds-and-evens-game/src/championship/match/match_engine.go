package match

import (
	"fmt"
	"math/rand"

	"odds-and-evens-game/championship/domain"
)

// PlayMatch resolves a single match between exactly two players.
func PlayMatch(roundNumber, matchNumber int, toss func() domain.CoinSide, firstPlayer, secondPlayer domain.Player) (domain.MatchResult, error) {
	if toss == nil {
		return domain.MatchResult{}, fmt.Errorf("coin toss function must not be nil")
	}
	if err := validatePlayer(firstPlayer); err != nil {
		return domain.MatchResult{}, fmt.Errorf("first player is invalid: %w", err)
	}
	if err := validatePlayer(secondPlayer); err != nil {
		return domain.MatchResult{}, fmt.Errorf("second player is invalid: %w", err)
	}

	tossedSide := toss()
	switch tossedSide {
	case domain.Heads:
		return domain.NewMatchResult(roundNumber, matchNumber, firstPlayer, secondPlayer, firstPlayer, tossedSide)
	case domain.Tails:
		return domain.NewMatchResult(roundNumber, matchNumber, firstPlayer, secondPlayer, secondPlayer, tossedSide)
	default:
		return domain.MatchResult{}, fmt.Errorf("invalid toss result: %q", tossedSide)
	}
}

// RandomTossSide returns a random coin side.
func RandomTossSide() domain.CoinSide {
	if rand.Intn(2) == 0 {
		return domain.Heads
	}
	return domain.Tails
}

// validatePlayer keeps the match-level validation local to this package.
func validatePlayer(player domain.Player) error {
	// The match engine only accepts fully initialized players.
	if player.ID() <= 0 {
		return fmt.Errorf("player ID must be positive: %d", player.ID())
	}
	if player.Name() == "" {
		return fmt.Errorf("player name must not be empty")
	}
	return nil
}
