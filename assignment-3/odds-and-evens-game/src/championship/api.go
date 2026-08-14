package championship

import (
	"odds-and-evens-game/championship/domain"
	"odds-and-evens-game/championship/match"
	"odds-and-evens-game/championship/round"
	"odds-and-evens-game/championship/tournament"
)

// CoinSide is the public alias for the domain coin side type.
type CoinSide = domain.CoinSide

const (
	// Heads means the first player wins the toss.
	Heads = domain.Heads
	// Tails means the second player wins the toss.
	Tails = domain.Tails
)

// Player is the public alias for a tournament participant.
type Player = domain.Player

// MatchResult is the public alias for a single match outcome.
type MatchResult = domain.MatchResult

// RoundResult is the public alias for the results of one round.
type RoundResult = domain.RoundResult

// ChampionshipResult is the public alias for the final tournament outcome.
type ChampionshipResult = domain.ChampionshipResult

// CoinTosser is the public alias for a coin-toss source.
type CoinTosser = match.CoinTosser

// RandomCoinTosser is the public alias for the production tosser.
type RandomCoinTosser = match.RandomCoinTosser

// FixedCoinTosser is the public alias for the deterministic test tosser.
type FixedCoinTosser = match.FixedCoinTosser

// CoinTosserFactory builds a tosser for each match.
type CoinTosserFactory = round.CoinTosserFactory

// ParseCoinSide validates a textual coin side.
func ParseCoinSide(value string) (CoinSide, error) {
	return domain.ParseCoinSide(value)
}

// NewPlayer creates a validated player through the domain package.
func NewPlayer(id int, name string) (Player, error) {
	return domain.NewPlayer(id, name)
}

// NewMatchResult creates a validated match result through the domain package.
func NewMatchResult(roundNumber, matchNumber int, firstPlayer, secondPlayer, winner Player, tossedSide CoinSide) (MatchResult, error) {
	return domain.NewMatchResult(roundNumber, matchNumber, firstPlayer, secondPlayer, winner, tossedSide)
}

// NewRoundResult creates an immutable round result snapshot.
func NewRoundResult(roundNumber int, matches []MatchResult, winners []Player) RoundResult {
	return domain.NewRoundResult(roundNumber, matches, winners)
}

// NewChampionshipResult creates the final immutable tournament snapshot.
func NewChampionshipResult(champion Player, rounds []RoundResult) ChampionshipResult {
	return domain.NewChampionshipResult(champion, rounds)
}

// NewRandomCoinTosser returns a production-ready random tosser.
func NewRandomCoinTosser() *RandomCoinTosser {
	return match.NewRandomCoinTosser()
}

// NewFixedCoinTosser returns a deterministic tosser for tests.
func NewFixedCoinTosser(side CoinSide) FixedCoinTosser {
	return match.NewFixedCoinTosser(side)
}

// NewRandomCoinTosserFactory builds a fresh random tosser per match.
func NewRandomCoinTosserFactory() CoinTosserFactory {
	return round.NewRandomCoinTosserFactory()
}

// PlayMatch resolves a single match between two players.
func PlayMatch(roundNumber, matchNumber int, tosser CoinTosser, firstPlayer, secondPlayer Player) (MatchResult, error) {
	return match.PlayMatch(roundNumber, matchNumber, tosser, firstPlayer, secondPlayer)
}

// PlayRound resolves one round and returns the winners plus ordered match results.
func PlayRound(roundNumber int, players []Player, tosserFactory CoinTosserFactory) ([]Player, []MatchResult, error) {
	return round.PlayRound(roundNumber, players, tosserFactory)
}

// PlayChampionship resolves the full tournament.
func PlayChampionship(players []Player, tosserFactory CoinTosserFactory) (ChampionshipResult, error) {
	return tournament.PlayChampionship(players, tosserFactory)
}
