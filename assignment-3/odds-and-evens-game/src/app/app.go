package app

import (
	"flag"
	"fmt"
	"io"
	"strings"

	"odds-and-evens-game/championship"
)

// Run executes the CLI with a random coin tosser factory.
func Run(args []string, stdout, stderr io.Writer, tosserFactory championship.CoinTosserFactory) int {
	return RunWithFactory(args, stdout, stderr, tosserFactory)
}

// RunWithFactory executes the CLI using the provided coin tosser factory.
func RunWithFactory(args []string, stdout, stderr io.Writer, tosserFactory championship.CoinTosserFactory) int {
	// Step 1: read and validate the command-line input.
	playersCount, err := ParsePlayersCount(args)
	if err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}

	// Step 2: build the tournament players.
	players, err := BuildPlayers(playersCount)
	if err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}

	// Step 3: run the championship logic.
	result, err := championship.PlayChampionship(players, tosserFactory)
	if err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}

	// Step 4: print the final report.
	if err := RenderChampionship(stdout, result); err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}

	return 0
}

// ParsePlayersCount validates the CLI arguments and extracts the player count.
func ParsePlayersCount(args []string) (int, error) {
	// Create a private flag parser so this code does not touch the global flags.
	flagSet := flag.NewFlagSet("odds-and-evens-game", flag.ContinueOnError)
	flagSet.SetOutput(io.Discard)

	// Read the -players flag from the input slice.
	players := flagSet.Int("players", 0, "number of players")
	if err := flagSet.Parse(args); err != nil {
		return 0, err
	}

	// Reject extra positional arguments.
	if flagSet.NArg() != 0 {
		return 0, fmt.Errorf("unexpected arguments: %s", strings.Join(flagSet.Args(), " "))
	}

	// Enforce a positive player count.
	if *players <= 0 {
		return 0, fmt.Errorf("-players must be positive")
	}

	// The tournament only works with a power-of-two bracket.
	if !IsPowerOfTwo(*players) {
		return 0, fmt.Errorf("-players must be a power of two")
	}

	return *players, nil
}

// BuildPlayers creates the canonical list of players for the tournament.
func BuildPlayers(count int) ([]championship.Player, error) {
	players := make([]championship.Player, count)

	// Players are generated in bracket order: Player-1, Player-2, and so on.
	for i := 1; i <= count; i++ {
		player, err := championship.NewPlayer(i, fmt.Sprintf("Player-%d", i))
		if err != nil {
			return nil, err
		}
		players[i-1] = player
	}

	return players, nil
}

// RenderChampionship writes a textual summary of the tournament.
func RenderChampionship(out io.Writer, result championship.ChampionshipResult) error {
	// Print each round in order.
	for _, round := range result.Rounds() {
		if _, err := fmt.Fprintf(out, "Round %d\n\n", round.RoundNumber()); err != nil {
			return err
		}

		// Print the matches inside the round in their original order.
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

	// Print the final winner at the end.
	if _, err := fmt.Fprintf(out, "Champion: %s\n", result.Champion().Name()); err != nil {
		return err
	}

	return nil
}

// IsPowerOfTwo returns true when value is a positive power of two.
func IsPowerOfTwo(value int) bool {
	return value > 0 && value&(value-1) == 0
}
