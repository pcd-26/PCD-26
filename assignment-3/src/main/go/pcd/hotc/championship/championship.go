package championship

import (
	"fmt"
	"math"
	"sync"
)

// RunChampionship runs the Heads-or-Tails championship with N = 2^m players.
// It initializes the player goroutines, orchestrates the tournament through m rounds,
// pairs the remaining players concurrently in each round, waits for all matches to complete,
// and finally prints the champion. It also ensures all goroutines are properly cleaned up.
func RunChampionship(m int) (int, error) {
	if m < 1 {
		return 0, fmt.Errorf("number of rounds m must be at least 1, got %d", m)
	}

	numPlayers := int(math.Pow(2, float64(m)))
	fmt.Printf("[Championship] Starting tournament with %d players (%d rounds)...\n", numPlayers, m)

	var wg sync.WaitGroup

	// Create and start player goroutines
	players := make([]PlayerInfo, numPlayers)
	for i := 0; i < numPlayers; i++ {
		players[i] = PlayerInfo{
			ID:      i + 1,
			ReqChan: make(chan Request),
		}
		wg.Add(1)
		go Player(players[i].ID, players[i].ReqChan, &wg)
	}

	// Tournament round-by-round loop
	for round := 1; round <= m; round++ {
		numMatches := len(players) / 2
		fmt.Printf("\n================ ROUND %d (%d Matches) ================\n", round, numMatches)

		winnerChan := make(chan PlayerInfo, numMatches)

		// Spawn matches concurrently
		for i := 0; i < len(players); i += 2 {
			go RunMatch(players[i], players[i+1], winnerChan)
		}

		// Wait for all matches in the current round to finish and collect the winners
		winners := make([]PlayerInfo, numMatches)
		for j := 0; j < numMatches; j++ {
			winners[j] = <-winnerChan
		}

		// Print the status of the round completion
		fmt.Printf("\n--- Round %d completed. Winners: ", round)
		for idx, w := range winners {
			if idx > 0 {
				fmt.Print(", ")
			}
			fmt.Printf("Player %d", w.ID)
		}
		fmt.Println()

		// Winners advance to the next round
		players = winners
	}

	// At the end of the m rounds, there is exactly one player left
	champion := players[0]
	fmt.Printf("\n======================================================\n")
	fmt.Printf("🏆 CHAMPIONSHIP ENDED! The champion is Player %d! 🏆\n", champion.ID)
	fmt.Printf("======================================================\n")

	// Clean up the champion goroutine by closing its channel
	close(champion.ReqChan)

	// Wait for all player goroutines to exit (both eliminated and champion)
	wg.Wait()

	return champion.ID, nil
}
