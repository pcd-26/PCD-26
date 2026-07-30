package main

import (
	"os"

	"heads-or-tails-championship/championship"
)

func main() {
	os.Exit(run(os.Args[1:], os.Stdout, os.Stderr, championship.NewRandomCoinTosserFactory()))
}
