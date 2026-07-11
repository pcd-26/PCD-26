package championship

// ChampionshipResult captures the final outcome of a championship.
type ChampionshipResult struct {
	champion Player
	rounds   []RoundResult
}

// RoundResult captures the ordered results of one championship round.
type RoundResult struct {
	roundNumber int
	matches     []MatchResult
	winners     []Player
}

func newChampionshipResult(champion Player, rounds []RoundResult) ChampionshipResult {
	return ChampionshipResult{
		champion: champion,
		rounds:   append([]RoundResult(nil), rounds...),
	}
}

func newRoundResult(roundNumber int, matches []MatchResult, winners []Player) RoundResult {
	return RoundResult{
		roundNumber: roundNumber,
		matches:     append([]MatchResult(nil), matches...),
		winners:     append([]Player(nil), winners...),
	}
}

// Champion returns the final champion.
func (c ChampionshipResult) Champion() Player {
	return c.champion
}

// Rounds returns the ordered championship rounds.
func (c ChampionshipResult) Rounds() []RoundResult {
	return append([]RoundResult(nil), c.rounds...)
}

// TotalRounds returns the number of played rounds.
func (c ChampionshipResult) TotalRounds() int {
	return len(c.rounds)
}

// TotalMatches returns the total number of matches played in the championship.
func (c ChampionshipResult) TotalMatches() int {
	total := 0
	for _, round := range c.rounds {
		total += len(round.matches)
	}
	return total
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
