package tournament

import (
	"fmt"

	"odds-and-evens-game/championship/domain"
	"odds-and-evens-game/championship/round"
)

// PlayChampionship resolves a full heads-or-tails championship.
func PlayChampionship(players []domain.Player) (domain.ChampionshipResult, error) {
	if len(players) == 0 {
		return domain.ChampionshipResult{}, fmt.Errorf("championship must contain at least one player")
	}
	if !isPowerOfTwo(len(players)) {
		return domain.ChampionshipResult{}, fmt.Errorf("number of players must be a power of two: %d", len(players))
	}
	if err := validatePlayers(players, "championship"); err != nil {
		return domain.ChampionshipResult{}, err
	}

	if len(players) == 1 {
		return domain.NewChampionshipResult(players[0], nil), nil
	}

	currentPlayers := append([]domain.Player(nil), players...)
	roundResults := make([]domain.RoundResult, 0, log2(len(players)))
	roundNumber := 1

	for len(currentPlayers) > 1 {
		winners, matches, err := round.PlayRound(roundNumber, currentPlayers)
		if err != nil {
			return domain.ChampionshipResult{}, err
		}

		roundResults = append(roundResults, domain.NewRoundResult(roundNumber, matches, winners))
		currentPlayers = winners
		roundNumber++
	}

	return domain.NewChampionshipResult(currentPlayers[0], roundResults), nil
}

// isPowerOfTwo is the small integer check used by the tournament gatekeeper.
func isPowerOfTwo(value int) bool {
	return value > 0 && value&(value-1) == 0
}

// log2 returns the number of rounds needed for a power-of-two player count.
func log2(value int) int {
	result := 0
	for value > 1 {
		value >>= 1
		result++
	}
	return result
}

// validatePlayers keeps the player checks local to the tournament orchestration.
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
