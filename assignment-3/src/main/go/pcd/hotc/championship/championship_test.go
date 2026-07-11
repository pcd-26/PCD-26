package championship

import (
	"fmt"
	"testing"
)

// TestRunChampionshipValid tests that the championship runs successfully
// and returns a valid player ID for various tournament sizes (m = 1, 2, 3, 4).
func TestRunChampionshipValid(t *testing.T) {
	rounds := []int{1, 2, 3, 4}
	for _, m := range rounds {
		t.Run(fmt.Sprintf("Rounds_%d", m), func(t *testing.T) {
			numPlayers := 1 << m
			winnerID, err := RunChampionship(m)
			if err != nil {
				t.Fatalf("Expected no error for m = %d, got %v", m, err)
			}
			if winnerID < 1 || winnerID > numPlayers {
				t.Errorf("Expected winner ID to be between 1 and %d, got %d", numPlayers, winnerID)
			}
		})
	}
}

// TestRunChampionshipInvalid tests that the championship correctly returns an error
// when given an invalid number of rounds (e.g. m < 1).
func TestRunChampionshipInvalid(t *testing.T) {
	invalidRounds := []int{0, -1, -5}
	for _, m := range invalidRounds {
		t.Run(fmt.Sprintf("InvalidRounds_%d", m), func(t *testing.T) {
			_, err := RunChampionship(m)
			if err == nil {
				t.Errorf("Expected error for m = %d, got nil", m)
			}
		})
	}
}
