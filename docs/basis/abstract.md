Local Credit, Global Settlement: Enhancing Trust-Based Credit Creation with On-Chain Reserve Contracts
======================================================================================================

In this work, we propose Basis, a framework where both local IOU trust-based currencies as well as 
global ones using blockchain smart contract powered reserves where there is no trust (possibly connecting local trading 
circles) can coexist. Coordination with blockchain is needed only during debt note redemption against a reserve contract. 
Payments are done offchain, so with low fees and no need to use low-security centralized blockchain-like systems with 
high throughput. For coordination and transparency of debt in the system, a tracker service is used, which can not steal money 
from reserves. We provide analysis of trust-minimization in regards with tracker activities. 

Current offchain payment systems backed by on-chain reserves (Lightning Network, Cashu, Fedimint) require full backing 
and so do not allow for credit creation. In contrast, Basis allows for credit creation with no requirements set on 
reserves at the protocol level. Then it can be used for community trading without using blockchain (and possibly, 
without Internet even, just by using a local mesh network), but when trust is insufficient to expand credit, 
on-chain reserves can be established and used. We expect that on a local scale trust-based economic relationships would 
dominate, and on a global scale full coverage with reserves would be needed in most cases. The whole system will use 
limited in disconnected from real-world needs supply blockchain assets only where they are needed (there is no trust), 
while enabling monetary expansion whenever possible (so where peer-to-peer trust can be established), thus allowing to 
create a viable alternative to political money. 

The main payment unit is IOU note, which is signed by the issuer and the tracker. Our design allows for debt transferability,
if issuer agrees on that, so if peer A issued debt to peer B, and peer B pays with it, fully or partially, to peer C, with A also co-signing, and then A owes C. 
We show that this design allows for minimal trust to tracker service. A tracker service is committing its state on the 
blockchain periodically. If a tracker service ceases to exist, it is possible to redeem debt notes against on-chain 
reserves using this committed state. There could be multiple trackers around the world. We consider different designs, from just centralized server, 
to federated control, to rollups and sidechains. 

We provide implementation of reserve contract as well as offchain clients (tracker server and example clients). We show 
an example of group trading over mesh network with occasional Internet connection. Another example shows AI agent 
economies where autonomous agents create credit relationships for services.

