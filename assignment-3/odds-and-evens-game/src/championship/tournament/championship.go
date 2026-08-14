package tournament

import (
	"fmt"

	"odds-and-evens-game/championship/domain"
	"odds-and-evens-game/championship/round"
)

// PlayChampionship resolves a full heads-or-tails championship.
func PlayChampionship(players []domain.Player, tosserFactory round.CoinTosserFactory) (domain.ChampionshipResult, error) {
	// Reject empty or malformed brackets immediately.
	if len(players) == 0 {
		return domain.ChampionshipResult{}, fmt.Errorf("championship must contain at least one player")
	}
	if !isPowerOfTwo(len(players)) {
		return domain.ChampionshipResult{}, fmt.Errorf("number of players must be a power of two: %d", len(players))
	}
	if tosserFactory == nil {
		return domain.ChampionshipResult{}, fmt.Errorf("coin tosser factory must not be nil")
	}

	if err := validatePlayers(players, "championship"); err != nil {
		return domain.ChampionshipResult{}, err
	}

	// A single player wins without playing any round.
	if len(players) == 1 {
		return domain.NewChampionshipResult(players[0], nil), nil
	}

	// Keep a private copy so the caller cannot mutate the bracket while we run.
	currentPlayers := append([]domain.Player(nil), players...)
	// Pre-size the round list: a power-of-two bracket always has log2(n) rounds.
	roundResults := make([]domain.RoundResult, 0, log2(len(players)))
	roundNumber := 1

	// Each round halves the number of active players.
	for len(currentPlayers) > 1 {
		winners, matches, err := round.PlayRound(roundNumber, currentPlayers, tosserFactory)
		if err != nil {
			return domain.ChampionshipResult{}, err
		}

		// Store the round result before moving to the next bracket.
		roundResults = append(roundResults, domain.NewRoundResult(roundNumber, matches, winners))
		currentPlayers = winners
		roundNumber++
	}

	// The last remaining player is the champion.
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
