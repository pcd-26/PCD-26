package championship

import (
	"odds-and-evens-game/championship/domain"
	"odds-and-evens-game/championship/match"
	"odds-and-evens-game/championship/round"
	"odds-and-evens-game/championship/tournament"
)

type CoinSide = domain.CoinSide

const (
	Heads = domain.Heads
	Tails = domain.Tails
)

type Player = domain.Player
type MatchResult = domain.MatchResult
type RoundResult = domain.RoundResult
type ChampionshipResult = domain.ChampionshipResult
type CoinTosser = match.CoinTosser
type RandomCoinTosser = match.RandomCoinTosser
type FixedCoinTosser = match.FixedCoinTosser
type CoinTosserFactory = round.CoinTosserFactory

func ParseCoinSide(value string) (CoinSide, error) {
	return domain.ParseCoinSide(value)
}

func NewPlayer(id int, name string) (Player, error) {
	return domain.NewPlayer(id, name)
}

func NewMatchResult(roundNumber, matchNumber int, firstPlayer, secondPlayer, winner Player, tossedSide CoinSide) (MatchResult, error) {
	return domain.NewMatchResult(roundNumber, matchNumber, firstPlayer, secondPlayer, winner, tossedSide)
}

func NewRoundResult(roundNumber int, matches []MatchResult, winners []Player) RoundResult {
	return domain.NewRoundResult(roundNumber, matches, winners)
}

func NewChampionshipResult(champion Player, rounds []RoundResult) ChampionshipResult {
	return domain.NewChampionshipResult(champion, rounds)
}

func NewRandomCoinTosser() *RandomCoinTosser {
	return match.NewRandomCoinTosser()
}

func NewFixedCoinTosser(side CoinSide) FixedCoinTosser {
	return match.NewFixedCoinTosser(side)
}

func NewRandomCoinTosserFactory() CoinTosserFactory {
	return round.NewRandomCoinTosserFactory()
}

func PlayMatch(roundNumber, matchNumber int, tosser CoinTosser, firstPlayer, secondPlayer Player) (MatchResult, error) {
	return match.PlayMatch(roundNumber, matchNumber, tosser, firstPlayer, secondPlayer)
}

func PlayRound(roundNumber int, players []Player, tosserFactory CoinTosserFactory) ([]Player, []MatchResult, error) {
	return round.PlayRound(roundNumber, players, tosserFactory)
}

func PlayChampionship(players []Player, tosserFactory CoinTosserFactory) (ChampionshipResult, error) {
	return tournament.PlayChampionship(players, tosserFactory)
}
