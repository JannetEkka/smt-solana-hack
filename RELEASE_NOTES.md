**SMT World for Android: the Clock In build.** Install the `.apk` below on an Android phone.

1. On the phone, open this page and tap the `.apk` file to download it.
2. Open it. Android asks to allow installs from your browser: allow it, then tap Install.
3. Install Phantom or Solflare from the Play Store and switch it to **Devnet** (Phantom: Settings → Developer Settings → Testnet Mode → Solana Devnet. Solflare: the network switch at the top, or Settings → Network → Devnet).
4. Get free devnet SOL at faucet.solana.com. 1 SOL pays for about 200,000 Clock Ins.
5. Open SMT World → Clock In → Connect wallet → pick a coin → UP or DOWN → Clock in on Solana.

Devnet only: no real money moves. The APK is built from this repo by GitHub Actions and signed with a demo key kept in `signing/` (it protects nothing; a store build would get its own key). `SHA256SUMS.txt` has the checksum.
