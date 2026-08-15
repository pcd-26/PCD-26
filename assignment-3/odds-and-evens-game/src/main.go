package main

import (
	"os"

	"odds-and-evens-game/app"
	"odds-and-evens-game/championship/round"
)

func main() {
	os.Exit(app.Run(os.Args[1:], os.Stdout, os.Stderr, round.NewRandomCoinTosserFactory()))
}
