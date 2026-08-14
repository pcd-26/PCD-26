package main

import (
	"flag"
	"fmt"
	"io"
	"strings"

	"odds-and-evens-game/championship"
)

func run(args []string, stdout, stderr io.Writer, tosserFactory championship.CoinTosserFactory) int {
	return runWithFactory(args, stdout, stderr, tosserFactory)
}

func runWithFactory(args []string, stdout, stderr io.Writer, tosserFactory championship.CoinTosserFactory) int {
	playersCount, err := parsePlayersCount(args)
	if err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}

	players, err := buildPlayers(playersCount)
	if err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}

	result, err := championship.PlayChampionship(players, tosserFactory)
	if err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}

	if err := renderChampionship(stdout, result); err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}

	return 0
}

func parsePlayersCount(args []string) (int, error) {
	flagSet := flag.NewFlagSet("odds-and-evens-game", flag.ContinueOnError)
	flagSet.SetOutput(io.Discard)

	players := flagSet.Int("players", 0, "number of players")
	if err := flagSet.Parse(args); err != nil {
		return 0, err
	}
	if flagSet.NArg() != 0 {
		return 0, fmt.Errorf("unexpected arguments: %s", strings.Join(flagSet.Args(), " "))
	}
	if *players <= 0 {
		return 0, fmt.Errorf("-players must be positive")
	}
	if !isPowerOfTwo(*players) {
		return 0, fmt.Errorf("-players must be a power of two")
	}

	return *players, nil
}

func buildPlayers(count int) ([]championship.Player, error) {
	players := make([]championship.Player, count)
	for i := 1; i <= count; i++ {
		player, err := championship.NewPlayer(i, fmt.Sprintf("Player-%d", i))
		if err != nil {
			return nil, err
		}
		players[i-1] = player
	}

	return players, nil
}

func renderChampionship(out io.Writer, result championship.ChampionshipResult) error {
	for _, round := range result.Rounds() {
		if _, err := fmt.Fprintf(out, "Round %d\n\n", round.RoundNumber()); err != nil {
			return err
		}

		for _, match := range round.Matches() {
			if _, err := fmt.Fprintf(
				out,
				"Match %d: %s [%s] vs %s [%s]\n",
				match.MatchNumber(),
				match.FirstPlayer().Name(),
				championship.Heads,
				match.SecondPlayer().Name(),
				championship.Tails,
			); err != nil {
				return err
			}
			if _, err := fmt.Fprintf(out, "Toss: %s\n", match.TossedSide()); err != nil {
				return err
			}
			if _, err := fmt.Fprintf(out, "Winner: %s\n\n", match.Winner().Name()); err != nil {
				return err
			}
		}
	}

	if _, err := fmt.Fprintf(out, "Champion: %s\n", result.Champion().Name()); err != nil {
		return err
	}

	return nil
}

func isPowerOfTwo(value int) bool {
	return value > 0 && value&(value-1) == 0
}
