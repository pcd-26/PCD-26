package round

import (
	"fmt"

	"odds-and-evens-game/championship/domain"
	"odds-and-evens-game/championship/match"
)

// CoinTosserFactory creates a coin tosser for a specific match.
type CoinTosserFactory func(roundNumber, matchNumber int, firstPlayer, secondPlayer domain.Player) match.CoinTosser

// NewRandomCoinTosserFactory creates a factory that gives each match its own random tosser.
func NewRandomCoinTosserFactory() CoinTosserFactory {
	return func(roundNumber, matchNumber int, firstPlayer, secondPlayer domain.Player) match.CoinTosser {
		return match.NewRandomCoinTosser()
	}
}

type matchOutcome struct {
	matchIndex int
	result     domain.MatchResult
	err        error
}

// PlayRound resolves one championship round concurrently.
func PlayRound(roundNumber int, players []domain.Player, tosserFactory CoinTosserFactory) ([]domain.Player, []domain.MatchResult, error) {
	// Basic input validation comes first.
	if len(players) == 0 {
		return nil, nil, fmt.Errorf("round must contain at least one player")
	}
	if len(players)%2 != 0 {
		return nil, nil, fmt.Errorf("round must contain an even number of players: %d", len(players))
	}
	if tosserFactory == nil {
		return nil, nil, fmt.Errorf("coin tosser factory must not be nil")
	}

	if err := validatePlayers(players, "round"); err != nil {
		return nil, nil, err
	}

	// Create one worker per match.
	matchCount := len(players) / 2
	// The coordinator owns this channel and receives exactly one outcome per match.
	// It is intentionally never closed: the coordinator already knows the exact message count.
	outcomes := make(chan matchOutcome)

	// Start all matches in parallel.
	for matchIndex := 0; matchIndex < matchCount; matchIndex++ {
		matchNumber := matchIndex + 1
		firstPlayer := players[matchIndex*2]
		secondPlayer := players[matchIndex*2+1]
		tosser := tosserFactory(roundNumber, matchNumber, firstPlayer, secondPlayer)

		go func(matchIndex, matchNumber int, firstPlayer, secondPlayer domain.Player, tosser match.CoinTosser) {
			// Each match goroutine sends exactly one outcome to the coordinator.
			if tosser == nil {
				outcomes <- matchOutcome{matchIndex: matchIndex, err: fmt.Errorf("coin tosser must not be nil")}
				return
			}

			// Resolve the match and report its result.
			result, err := match.PlayMatch(roundNumber, matchNumber, tosser, firstPlayer, secondPlayer)
			outcomes <- matchOutcome{matchIndex: matchIndex, result: result, err: err}
		}(matchIndex, matchNumber, firstPlayer, secondPlayer, tosser)
	}

	// Collect every outcome before deciding whether the round succeeded.
	orderedResults := make([]domain.MatchResult, matchCount)
	var firstErr error
	for received := 0; received < matchCount; received++ {
		outcome := <-outcomes
		orderedResults[outcome.matchIndex] = outcome.result
		if outcome.err != nil && firstErr == nil {
			firstErr = outcome.err
		}
	}

	if firstErr != nil {
		return nil, nil, firstErr
	}

	// Extract the winners in the same order as the matches.
	winners := make([]domain.Player, matchCount)
	for matchIndex, result := range orderedResults {
		winners[matchIndex] = result.Winner()
	}

	return winners, orderedResults, nil
}

// validatePlayers keeps the round validation rules close to the concurrency logic.
func validatePlayers(players []domain.Player, scope string) error {
	// Every player must be valid before the round starts.
	for _, player := range players {
		if player.ID() <= 0 {
			return fmt.Errorf("invalid player in %s: player ID must be positive: %d", scope, player.ID())
		}
		if player.Name() == "" {
			return fmt.Errorf("invalid player in %s: player name must not be empty", scope)
		}
	}

	return nil
}
