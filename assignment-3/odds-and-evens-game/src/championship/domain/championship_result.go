package domain

// ChampionshipResult captures the final outcome of a championship.
type ChampionshipResult struct {
	champion Player
	rounds   []RoundResult
}

// NewChampionshipResult copies the input rounds to keep the result immutable.
func NewChampionshipResult(champion Player, rounds []RoundResult) ChampionshipResult {
	return ChampionshipResult{
		champion: champion,
		rounds:   append([]RoundResult(nil), rounds...),
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
