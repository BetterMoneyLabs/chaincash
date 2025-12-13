package gp

// redeem is special kind of transaction, with a single input and single output,
// and output being signed along with the input
case class RedeemTransaction(input: Note, output: Note)
