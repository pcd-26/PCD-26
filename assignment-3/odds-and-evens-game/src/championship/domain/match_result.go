package domain

import "fmt"

// MatchResult captures the outcome of a single match.
type MatchResult struct {
	roundNumber  int
	matchNumber  int
	firstPlayer  Player
	secondPlayer Player
	winner       Player
	parity       Parity
}

// NewMatchResult creates a validated match result.
func NewMatchResult(roundNumber, matchNumber int, firstPlayer, secondPlayer, winner Player, parity Parity) (MatchResult, error) {
	if roundNumber <= 0 {
		return MatchResult{}, fmt.Errorf("round number must be positive: %d", roundNumber)
	}
	if matchNumber <= 0 {
		return MatchResult{}, fmt.Errorf("match number must be positive: %d", matchNumber)
	}
	if parity != Odd && parity != Even {
		return MatchResult{}, fmt.Errorf("invalid parity: %q", parity)
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
		parity:       parity,
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

// Parity returns the winning parity produced by the match.
func (m MatchResult) Parity() Parity {
	return m.parity
}
