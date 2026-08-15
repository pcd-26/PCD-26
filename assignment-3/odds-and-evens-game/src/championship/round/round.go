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
func PlayRound(roundNumber int, players []domain.Player) ([]domain.Player, []domain.MatchResult, error) {
	if len(players) == 0 {
		return nil, nil, fmt.Errorf("round must contain at least one player")
	}
	if len(players)%2 != 0 {
		return nil, nil, fmt.Errorf("round must contain an even number of players: %d", len(players))
	}
	if err := validatePlayers(players, "round"); err != nil {
		return nil, nil, err
	}

	matchCount := len(players) / 2
	outcomes := make(chan matchOutcome)

	for matchIndex := 0; matchIndex < matchCount; matchIndex++ {
		matchNumber := matchIndex + 1
		firstPlayer := players[matchIndex*2]
		secondPlayer := players[matchIndex*2+1]

		go func(matchIndex, matchNumber int, firstPlayer, secondPlayer domain.Player) {
			result, err := match.PlayMatch(roundNumber, matchNumber, firstPlayer, secondPlayer)
			outcomes <- matchOutcome{matchIndex: matchIndex, result: result, err: err}
		}(matchIndex, matchNumber, firstPlayer, secondPlayer)
	}

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
