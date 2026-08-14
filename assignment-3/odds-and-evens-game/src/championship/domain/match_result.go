package domain

import "fmt"

// MatchResult captures the outcome of a single match.
type MatchResult struct {
	roundNumber  int
	matchNumber  int
	firstPlayer  Player
	secondPlayer Player
	winner       Player
	tossedSide   CoinSide
}

// NewMatchResult creates a validated match result.
func NewMatchResult(roundNumber, matchNumber int, firstPlayer, secondPlayer, winner Player, tossedSide CoinSide) (MatchResult, error) {
	if roundNumber <= 0 {
		return MatchResult{}, fmt.Errorf("round number must be positive: %d", roundNumber)
	}
	if matchNumber <= 0 {
		return MatchResult{}, fmt.Errorf("match number must be positive: %d", matchNumber)
	}
	if tossedSide != Heads && tossedSide != Tails {
		return MatchResult{}, fmt.Errorf("invalid coin side: %q", tossedSide)
	}
	if winner.ID() != firstPlayer.ID() && winner.ID() != secondPlayer.ID() {
		return MatchResult{}, fmt.Errorf("winner must be one of the match players")
	}

	return MatchResult{
		roundNumber:  roundNumber,
		matchNumber:  matchNumber,
		firstPlayer:  firstPlayer,
		secondPlayer: secondPlayer,
		winner:       winner,
		tossedSide:   tossedSide,
	}, nil
}

// RoundNumber returns the round index.
func (m MatchResult) RoundNumber() int {
	return m.roundNumber
}

// MatchNumber returns the match index.
func (m MatchResult) MatchNumber() int {
	return m.matchNumber
}

// FirstPlayer returns the first player.
func (m MatchResult) FirstPlayer() Player {
	return m.firstPlayer
}

// SecondPlayer returns the second player.
func (m MatchResult) SecondPlayer() Player {
	return m.secondPlayer
}

// Winner returns the winner.
func (m MatchResult) Winner() Player {
	return m.winner
}

// TossedSide returns the coin side tossed for the match.
func (m MatchResult) TossedSide() CoinSide {
	return m.tossedSide
}
