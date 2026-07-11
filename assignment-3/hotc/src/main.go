package main

import (
	"os"

	"hotc/championship"
)

func main() {
	os.Exit(run(os.Args[1:], os.Stdout, os.Stderr, championship.NewRandomCoinTosserFactory()))
}
