package championship

import "fmt"

// PlayChampionship resolves a full heads-or-tails championship.
func PlayChampionship(players []Player, tosserFactory CoinTosserFactory) (ChampionshipResult, error) {
	if len(players) == 0 {
		return ChampionshipResult{}, fmt.Errorf("championship must contain at least one player")
	}
	if !isPowerOfTwo(len(players)) {
		return ChampionshipResult{}, fmt.Errorf("number of players must be a power of two: %d", len(players))
	}
	if tosserFactory == nil {
		return ChampionshipResult{}, fmt.Errorf("coin tosser factory must not be nil")
	}

	for _, player := range players {
		if err := validatePlayer(player); err != nil {
			return ChampionshipResult{}, fmt.Errorf("invalid player in championship: %w", err)
		}
	}

	if len(players) == 1 {
		return newChampionshipResult(players[0], nil), nil
	}

	currentPlayers := append([]Player(nil), players...)
	roundResults := make([]RoundResult, 0, log2(len(players)))
	roundNumber := 1

	for len(currentPlayers) > 1 {
		winners, matches, err := PlayRound(roundNumber, currentPlayers, tosserFactory)
		if err != nil {
			return ChampionshipResult{}, err
		}

		roundResults = append(roundResults, newRoundResult(roundNumber, matches, winners))
		currentPlayers = winners
		roundNumber++
	}

	return newChampionshipResult(currentPlayers[0], roundResults), nil
}

func isPowerOfTwo(value int) bool {
	return value > 0 && value&(value-1) == 0
}

func log2(value int) int {
	result := 0
	for value > 1 {
		value >>= 1
		result++
	}
	return result
}
