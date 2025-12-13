# ChainCash Server 

This document is describing general ideas behind ChainCash payment server, a piece of software doing client-side 
validation of ChainCash notes. 

## Client-Side Validation

ChainCash is allowing to create money with different levels of collateralization, different types of collateral and 
trust and so on. It is up to a user then what to accept and what not. A user is identified with ChainCash server, which 
is deciding whether to accept notes offered as a mean of payment or not. In practice, there could be many users and 
services behind a single ChainCash server. Thus we talk about client-side validation further, where is a client is a 
ChainCash server with its individual settings. The client could be thought as a self-sovereign bank. 

## Acceptance Predicate

[ChainCash whitepaper](https://github.com/kushti/chaincash/blob/master/paper/chaincash.pdf) defines client-side 
acceptance predicate, and we are going to details here.

Acceptance predicate should be defined via [TOML](https://toml.io/en/) based settings. 
It should be possible to define following data the predicate is built on:

* whitelist (for current holder)
* blacklist (for current holder)
* collateralization level

## Prototype

Some prototype code (on-chain data tracking, persistence, transaction builders) in Scala can be found in [offchain](https://github.com/kushti/chaincash/tree/master/src/main/scala/chaincash/offchain)
folder. 

## Implementation

ChainCash server will be implemented in Rust and implement following functionality:

* support for one or multiple keys (accounts)
* ability to create reserves for key and withdraw from them
* ability to create new notes against reserves and propose them for payments, and to redeem notes
* API to accept (or reject) payments, provide data for the state of accounts and the state of the whole system

## ChainCash Improvement Proposals

Evolution of contracts is done via ChainCash improvement proposals (CCIPs). CCIP life cycle is about:

* proposal stage when an author is proposing a new CCIP with new contracts (offchain protocol, sidechain)
  for public review 
* discussions stage which is leading to acceptance or rejection of the CCIP
* implementation in case of acceptance

Old ChainCash servers after new CCIP implementation continue to work, just can not recognize new types of notes. A 
server should indicate which CCIPs it is supporting.
