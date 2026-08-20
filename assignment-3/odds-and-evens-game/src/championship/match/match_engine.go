package match

import (
	"fmt"
	"math/rand"

	"odds-and-evens-game/championship/domain"
)

// PlayMatch resolves a single match between exactly two players.
func PlayMatch(roundNumber, matchNumber int, decideWinnerParity func() domain.Parity, firstPlayer, secondPlayer domain.Player) (domain.MatchResult, error) {
	if decideWinnerParity == nil {
		return domain.MatchResult{}, fmt.Errorf("parity function must not be nil")
	}
	if err := validatePlayer(firstPlayer); err != nil {
		return domain.MatchResult{}, fmt.Errorf("first player is invalid: %w", err)
	}
	if err := validatePlayer(secondPlayer); err != nil {
		return domain.MatchResult{}, fmt.Errorf("second player is invalid: %w", err)
	}

	parity := decideWinnerParity()
	switch parity {
	case domain.Odd:
		return domain.NewMatchResult(roundNumber, matchNumber, firstPlayer, secondPlayer, firstPlayer, parity)
	case domain.Even:
		return domain.NewMatchResult(roundNumber, matchNumber, firstPlayer, secondPlayer, secondPlayer, parity)
	default:
		return domain.MatchResult{}, fmt.Errorf("invalid parity result: %q", parity)
	}
}

// RandomWinnerParity returns a random winning parity.
func RandomWinnerParity() domain.Parity {
	if rand.Intn(2) == 0 {
		return domain.Odd
	}
	return domain.Even
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
