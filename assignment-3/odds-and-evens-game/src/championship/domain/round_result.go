package domain

// RoundResult captures the ordered results of one championship round.
type RoundResult struct {
	roundNumber int
	matches     []MatchResult
	winners     []Player
}

func NewRoundResult(roundNumber int, matches []MatchResult, winners []Player) RoundResult {
	return RoundResult{
		roundNumber: roundNumber,
		matches:     append([]MatchResult(nil), matches...),
		winners:     append([]Player(nil), winners...),
	}
}

// RoundNumber returns the round number.
func (r RoundResult) RoundNumber() int {
	return r.roundNumber
}

// Matches returns the ordered match results for the round.
func (r RoundResult) Matches() []MatchResult {
	return append([]MatchResult(nil), r.matches...)
}

// Winners returns the ordered winners of the round.
func (r RoundResult) Winners() []Player {
	return append([]Player(nil), r.winners...)
}

// MatchCount returns the number of matches in the round.
func (r RoundResult) MatchCount() int {
	return len(r.matches)
}
