Take Lightning network, for example. Using ChainCash terminology, an on-chain reserve there made by a peer A allows 
only for payments between peers A and B set in advance. Which is perfect from trust assumptions point of view, but results 
in capital inefficiency, quite centralized overall network topology (with few hubs dominating in liquidity flows) etc.

As an alternative, we propose to use the same reserve for potentially unbounded number of peers, even more, and allow 
for interactions to happen with no reserve deployed on-chain yet even. Then, when A offers an IOU note backed by A's 
reserve to B as a mean of payment, B needs to know the full state of A's debt to get an idea of collateralizatoon. 

That is the first task of the tracker - it tracks state of debt for any issuer (having on-chain reserve or not) and 
report it publicly. There is another role - as A can always issue debt to self (a generated identity A') and then 
redeeem before others, to prevent this, tracker should not witness new debt which is violating collateralization of 
previous debt holders (and only witnessed debt is redeemable while tracker is online, emergency exit against 
last witnessed state is possible when tracker is going offline only).
