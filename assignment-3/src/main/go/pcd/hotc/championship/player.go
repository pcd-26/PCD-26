package championship

import (
	"fmt"
	"math/rand"
	"sync"
)

// Player runs the main event loop for a player goroutine.
// It listens on the requestChan for game requests from a referee.
// For each request, it makes a random choice (Heads or Tails), sends it back,
// and awaits the result (Win, Lose, Tie).
// If the player loses, it terminates the goroutine.
// If the channel is closed, the player exits.
func Player(id int, requestChan <-chan Request, wg *sync.WaitGroup) {
	defer wg.Done()
	for req := range requestChan {
		// Player makes a random choice: Heads (0) or Tails (1)
		choice := Choice(rand.Intn(2))
		
		// Send choice to the referee
		req.ResponseChan <- choice

		// Await the match outcome from the referee
		result := <-req.FeedbackChan
		switch result {
		case Win:
			// Player won and advances to the next round, stays active
		case Lose:
			// Player is eliminated, exits the goroutine
			return
		case Tie:
			// Tie occurred, player stays in loop to play again
		}
	}
	// The channel is closed when the tournament ends (e.g., for the champion)
	fmt.Printf("[System] Player %d thread exiting naturally.\n", id)
}
