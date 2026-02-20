[Local Credit, Global Settlement: Enhancing Trust-Based Credit Creation with On-Chain Reserve Contracts

In this work, we propose Basis, a framework where both local IOU trust-based based currencies as well as 
global ones using blockchain smart contract powered reserves where there is no trust (possibly connecting local trading 
circles) can coexist. Coordination with blockchain is needed only during debt note redemption against reserve contract. 
Payments are done off-chain, so with low fees and no need to use low-security centralized blockchain-like systems with 
high throughput. For coordination and transparency of debt in the system, a tracker is used, which can not steal money 
from reserves. We provide analysis of trust-minimization in regards with tracker activities. 

Current offchain payment systems backed by on-chain reserves (Lightning Network, Cashu, Fedimint) require full backing 
and so do not allow for credit creation. In opposite, Basis allowing for credit creation with no any requirements set on 
reserves at the protocol level. Then it can be used for community trading without using blockchain (and possibly, 
without Internet even, by using a local mesh network just), but when trust is not enough to trade on or expand credit, 
on-chain reserves can be established and used. We expect that on local scale trust-based economic relationships would 
dominate, and on global scale full coverage with reserves would be needed in most cases. The whole system will use 
limited in disconnected from real-world needs supply blockchain assets only where they are needed (there is no trust), 
while doing monetary expansion whenever possible (so where peer-to-peer trust can be established), thus allowing to 
create viable alternative to political money. 

A main payment unit is IOU note, which is signed by issuer, and also tracker. Our design allows for debt transferrability, 
if issuer agrees, so if peer A issued debt to peer B, peer B pay with it, fully or partially, to peer C, and then A owes C. 
We show that this design allows for minimal trust to tracker service. A tracker service is committing its state on the 
blockchain periodically. If a tracker service ceased to exist, it is possible to redeem debt notes against on-chain 
reserves using this committed state. There could be multiple trackers around the world. We consider different designs, from just centralized server, 
to federated control, to rollups and sidechains. 

We provide implementation of reserve contract as well as offchain clients (tracker server and example clients). We show 
example of group trading over mesh network with occasional Internet connection. Another example shows AI agent 
economies where autonomous agents create credit relationships for services.

