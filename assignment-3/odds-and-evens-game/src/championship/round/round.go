package round

import (
	"fmt"

	"odds-and-evens-game/championship/domain"
	"odds-and-evens-game/championship/match"
)

type matchOutcome struct {
	matchIndex int
	result     domain.MatchResult
	err        error
}

// PlayRound resolves one championship round concurrently.
func PlayRound(roundNumber int, players []domain.Player, toss func() domain.CoinSide) ([]domain.Player, []domain.MatchResult, error) {
	// The round needs a coin toss function to resolve each match.
	if toss == nil {
		return nil, nil, fmt.Errorf("coin toss function must not be nil")
	}
	// A round cannot run without players.
	if len(players) == 0 {
		return nil, nil, fmt.Errorf("round must contain at least one player")
	}
	// Players must be paired two by two.
	if len(players)%2 != 0 {
		return nil, nil, fmt.Errorf("round must contain an even number of players: %d", len(players))
	}
	// Reject malformed players before starting any goroutine.
	if err := validatePlayers(players, "round"); err != nil {
		return nil, nil, err
	}

	// Each pair of players becomes one match.
	matchCount := len(players) / 2
	// The coordinator receives one outcome for each started match.
	outcomes := make(chan matchOutcome)

	// Start all matches in parallel.
	for matchIndex := 0; matchIndex < matchCount; matchIndex++ {
		matchNumber := matchIndex + 1
		firstPlayer := players[matchIndex*2]
		secondPlayer := players[matchIndex*2+1]

		// Capture the loop values so each goroutine works on the right pair.
		go func(matchIndex, matchNumber int, firstPlayer, secondPlayer domain.Player) {
			// Resolve one match and send its outcome back to the coordinator.
			result, err := match.PlayMatch(roundNumber, matchNumber, toss, firstPlayer, secondPlayer)
			outcomes <- matchOutcome{matchIndex: matchIndex, result: result, err: err}
		}(matchIndex, matchNumber, firstPlayer, secondPlayer)
	}

	// Collect all outcomes before deciding whether the round succeeded.
	orderedResults := make([]domain.MatchResult, matchCount)
	var firstErr error
	for received := 0; received < matchCount; received++ {
		outcome := <-outcomes
		// Store each result in its original bracket position.
		orderedResults[outcome.matchIndex] = outcome.result
		// Keep the first error, but still wait for every goroutine to finish.
		if outcome.err != nil && firstErr == nil {
			firstErr = outcome.err
		}
	}

	// If one match failed, stop the round and return that error.
	if firstErr != nil {
		return nil, nil, firstErr
	}

	// Extract the winners in the same order as the original matches.
	winners := make([]domain.Player, matchCount)
	for matchIndex, result := range orderedResults {
		winners[matchIndex] = result.Winner()
	}

	return winners, orderedResults, nil
}

// validatePlayers keeps the round validation rules close to the concurrency logic.
func validatePlayers(players []domain.Player, scope string) error {
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
