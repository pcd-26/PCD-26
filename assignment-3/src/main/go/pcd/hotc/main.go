package main

import (
	"flag"
	"fmt"
	"os"
	"time"

	"pcd/hotc/championship"
)

func main() {
	mFlag := flag.Int("m", 3, "number of rounds (giving 2^m players)")
	flag.Parse()

	fmt.Println("Heads-or-Tails Championship - Go Language")
	fmt.Printf("Configured rounds: %d, players: %d\n\n", *mFlag, 1<<*mFlag)

	start := time.Now()
	championID, err := championship.RunChampionship(*mFlag)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error running championship: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("\nElapsed time: %v\n", time.Since(start))
	fmt.Printf("Championship simulation successfully completed. Winner: Player %d\n", championID)
}
