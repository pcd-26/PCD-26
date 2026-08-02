package championship

import "fmt"

// CoinTosserFactory creates a coin tosser for a specific match.
type CoinTosserFactory func(roundNumber, matchNumber int, firstPlayer, secondPlayer Player) CoinTosser

// NewRandomCoinTosserFactory creates a factory that gives each match its own random tosser.
func NewRandomCoinTosserFactory() CoinTosserFactory {
	return func(roundNumber, matchNumber int, firstPlayer, secondPlayer Player) CoinTosser {
		return NewRandomCoinTosser()
	}
}

type matchOutcome struct {
	matchIndex int
	result     MatchResult
	err        error
}

// PlayRound resolves one championship round concurrently.
func PlayRound(round int, players []Player, tosserFactory CoinTosserFactory) ([]Player, []MatchResult, error) {
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

	matchCount := len(players) / 2
	// The coordinator owns this channel and receives exactly one outcome per match.
	// It is intentionally never closed: the coordinator already knows the exact message count.
	outcomes := make(chan matchOutcome)

	for matchIndex := 0; matchIndex < matchCount; matchIndex++ {
		matchNumber := matchIndex + 1
		firstPlayer := players[matchIndex*2]
		secondPlayer := players[matchIndex*2+1]
		tosser := tosserFactory(round, matchNumber, firstPlayer, secondPlayer)

		go func(matchIndex, matchNumber int, firstPlayer, secondPlayer Player, tosser CoinTosser) {
			// Each match goroutine sends exactly one outcome to the coordinator.
			if tosser == nil {
				outcomes <- matchOutcome{matchIndex: matchIndex, err: fmt.Errorf("coin tosser must not be nil")}
				return
			}

			result, err := PlayMatch(round, matchNumber, tosser, firstPlayer, secondPlayer)
			outcomes <- matchOutcome{matchIndex: matchIndex, result: result, err: err}
		}(matchIndex, matchNumber, firstPlayer, secondPlayer, tosser)
	}

	orderedResults := make([]MatchResult, matchCount)
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

	winners := make([]Player, matchCount)
	for matchIndex, result := range orderedResults {
		winners[matchIndex] = result.Winner()
	}

	return winners, orderedResults, nil
}
