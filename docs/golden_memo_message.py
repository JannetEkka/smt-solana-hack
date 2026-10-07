# Regenerates the reference bytes in SolanaWireTest.messageMatchesIndependentReference.
# pip install solders && python3 docs/golden_memo_message.py
# Independent reference: build the same memo message with solders (Rust solana-sdk bindings).
from solders.pubkey import Pubkey
from solders.hash import Hash
from solders.instruction import Instruction, AccountMeta
from solders.message import Message
payer = Pubkey.from_string("9zRcCvqFV9jVDLCPPAhUC17NwJUZypHaPB5YcM6xEhJw")
memo_prog = Pubkey.from_string("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr")
bh = Hash.from_string("EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N")
memo = "SMT Clock In v1 | BTC | me UP @ 62345.12 binance | SMT DOWN 36% WAIT | grade +4h"
ix = Instruction(memo_prog, memo.encode(), [AccountMeta(payer, True, True)])
msg = Message.new_with_blockhash([ix], payer, bh)
print(bytes(msg).hex())
