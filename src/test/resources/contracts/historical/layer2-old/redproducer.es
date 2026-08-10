{
 // contract which is producing redemption contract boxes
 // it holds redemption contract tokens and releasing it
 // one token can be released when the contract is invoked
 // if an output with the token bound to the redemption contract
 //
 // TOKENS
 // #0 - contract NFT
 // #1 - redemption request token

 val redeemScriptHash = fromBase58("")

 val selfOutput = OUTPUTS(0)
 val redemptionRequestOutput = OUTPUTS(1)

 val redemptionRequestTokenId = SELF.tokens(1)._1
 val redemptionRequestTokensInSelf = SELF.tokens(1)._2

 val selfOutCorrect = selfOutput.value >= SELF.value && selfOutput.tokens(0) == SELF.tokens(0) &&
                        selfOutput.tokens(1)._1 == redemptionRequestTokenId &&
                        selfOutput.tokens(1)._2 == redemptionRequestTokensInSelf - 1

 val redemptionTokenCorrect = redemptionRequestOutput.tokens(0)._1 == redemptionRequestTokenId &&
                                redemptionRequestOutput.tokens(0)._2 == 1

 val properCollateral = redemptionRequestOutput.value == 2000000000

 val properDeadline = redemptionRequestOutput.R8[Int].get >= HEIGHT + 720 // one day for contestation

 val properRedeemPosition = redemptionRequestOutput.R6[(Long, Coll[Byte])].get._1 <= redemptionRequestOutput.R5[Long].get

 val redeemR7 = redemptionRequestOutput.R7[(Long, Boolean)].get

 val redeemR7Correct = redeemR7._1 == -1 && redeemR7._2 == false

 val redemptionScriptCorrect = blake2b256(redemptionRequestOutput.propositionBytes) == redeemScriptHash

 val redemptionRequestCorrect = redemptionTokenCorrect && properCollateral && properDeadline &&
                                properRedeemPosition && redeemR7Correct && redemptionScriptCorrect

 sigmaProp(selfOutCorrect && redemptionRequestCorrect)

}