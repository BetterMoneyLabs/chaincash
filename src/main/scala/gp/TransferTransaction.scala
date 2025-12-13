package gp

// Transfer is like on-chain UTXO transaction, multiple possible inputs and outputs, and only inputs signed
case class TransferTransaction(inputs: Seq[Note], outputs: Seq[Note])
