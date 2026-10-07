# Regenerates the reference bytes in SolanaWireTest.messageMatchesIndependentReference.
# pip install solders && python3 docs/golden_memo_message.py
# Independent reference: the same two-memo Clock In message, built with solders (Rust solana-sdk bindings).
import hashlib
from solders.pubkey import Pubkey
from solders.hash import Hash
from solders.instruction import CompiledInstruction
from solders.message import Message

payer = Pubkey.from_string("9zRcCvqFV9jVDLCPPAhUC17NwJUZypHaPB5YcM6xEhJw")
registry = Pubkey(hashlib.sha256(b"SMT Clock In registry v1").digest())
memo_v2 = Pubkey.from_string("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr")
memo_v1 = Pubkey.from_string("Memo1UhkJRfHyvLMcVucJwxXeuD728EqVDDwQDxFMNo")
bh = Hash.from_string("EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N")
memo = "SMT Clock In v1 | BTC | me UP @ 62345.12 binance | SMT DOWN 36% WAIT | grade +4h"
print("registry", registry)
msg = Message.new_with_compiled_instructions(
    1, 0, 3,
    [payer, registry, memo_v2, memo_v1],
    bh,
    [CompiledInstruction(2, memo.encode(), bytes([0])),
     CompiledInstruction(3, b"SMT Clock In", bytes([1]))],
)
print(bytes(msg).hex())
