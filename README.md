# SMT World: Clock In

An Android app for [Solana Mobile's Clock In hackathon](https://solanamobile.com/blog/clock-in-the-solana-mobile-hackathon). Once a day you call one coin UP or DOWN for the next 4 hours. Your call and the AI's call go on Solana devnet in one transaction, and both get graded against the real price 4 hours later.

The AI is [Smart Money Trading (SMT)](https://smt-weex-trading-bot.jannet-ekka.workers.dev/), a multi-persona crypto trading agent. Six personas (order flow, whales & on-chain, sentiment, technical, regime, catalyst) vote, and a judge turns the votes into a call with a written reason. You make your call first; SMT's call and its reasons are revealed after yours is on chain.

**Download:** the APK is on the [Releases page](../../releases). Install steps are below.

## How a Clock In works

1. Connect a wallet (Phantom or Solflare, set to Devnet) through **Mobile Wallet Adapter**.
2. Pick a coin (BTC, ETH, SOL, BNB, XRP, LTC, ADA, DOGE) and tap **UP** or **DOWN**.
3. Tap **Clock in on Solana**. The app reads the current price and SMT's current call, then asks your wallet to sign and send one transaction with an SPL Memo like this:

   ```
   SMT Clock In v1 | BTC | me UP @ 62345.12 binance | SMT DOWN 36% WAIT | grade +4h
   ```

4. SMT's call is revealed: its direction, its conviction, the judge's reason, and how each persona voted.
5. 4 hours later the app grades both calls against the price then, from the same public source as the entry price, and sends a notification.

Your streak counts the days in a row you've clocked in. It's rebuilt from the chain (`getSignaturesForAddress` returns each transaction's memo), so it survives a reinstall or a new phone. The **My calls** tab shows every call with its grade and a link to Solana Explorer, plus a running score: you vs SMT.

## Why the record is on chain

Today an AI agent's track record is whatever its operator's logs say. Here each call is stamped by the network at the moment it was made, the user's call and the agent's call in the same transaction, so neither can be written after the market moves. The memo is plain text, and it carries the entry price and its source, so anyone can re-check a grade on Explorer without this app.

## What brings you back

- A daily streak, kept on chain.
- A grade 4 hours after each call, with a notification.
- A 9:00 reminder if you haven't clocked in (you can switch it off).
- A new SMT call every day, with its reasons, to argue with.

## Install on an Android phone

1. On the phone, open the [latest release](../../releases/latest) and tap the `.apk` to download it.
2. Open the file. Android asks to allow installs from your browser: allow it, then tap **Install**.
3. Install **Phantom** or **Solflare** and switch it to **Devnet**. In Phantom: Settings → Developer Settings → Testnet Mode → Solana Devnet. In Solflare: the network switch at the top, or Settings → Network → Devnet.
4. Get free devnet SOL at [faucet.solana.com](https://faucet.solana.com). A Clock In costs about 0.000005 SOL.
5. Open **SMT World** → **Clock In** → **Connect wallet**.

Devnet only. No real money moves at any point.

## Built during the hackathon

Everything in this repository was written for Clock In, in October 2026: the Android app, the memo transaction, the on-chain history and streak, grading, notifications and the Fire TV support.

Two things it uses already existed and are public:
- **SMT World's web app**, shown as is in the **SMT World** tab.
- **SMT's public decisions feed** (`decisions.json` on the same site), which is where the app reads SMT's call and reasons.

The trading agent itself, its parameters and its research are not in this repo.

## Fire TV and TVs

The same APK runs on Fire TV and Android TV. Every control can be reached with a remote's arrow keys and shows a gold focus ring. There's a TV launcher banner. Nothing depends on Google Play Services. When no Mobile Wallet Adapter wallet is installed (a TV never has one), the wallet features hide themselves, and the app shows SMT's calls and SMT World instead.

## Tech

- Kotlin, Jetpack Compose, Material 3. Min Android 7.0 (API 24), target API 35.
- [Mobile Wallet Adapter](https://docs.solanamobile.com/android-native/overview) `clientlib-ktx` 2.0.8 for connect and `signAndSendTransactions`.
- The memo transaction is built by hand in [`MemoTransaction.kt`](app/src/main/java/io/github/jannetekka/smtworld/solana/MemoTransaction.kt): one SPL Memo instruction, with the wallet as fee payer and signer. A unit test compares its bytes with the same message built by [solders](https://github.com/kevinheavey/solders), the Rust `solana-sdk`'s Python bindings ([`docs/golden_memo_message.py`](docs/golden_memo_message.py)).
- Devnet JSON-RPC for blockhash, balance and history. Prices from Binance's public market-data API, with CoinGecko when Binance refuses (it answers HTTP 451 in some countries).
- WorkManager for the daily reminder and the grade check.

## Build and test

```bash
./gradlew testDebugUnitTest        # unit tests (wire format, memo, streak, grading, feed)
./gradlew assembleRelease          # app/build/outputs/apk/release/smt-world-<version>-release.apk
DEVNET_E2E=1 ./gradlew testDebugUnitTest --tests '*DevnetRoundTrip*'   # simulates on devnet, then sends if the test key is funded
```

GitHub Actions builds and tests every push and attaches the APK to a release for each `v*` tag. The release APK is signed with a demo key in [`signing/`](signing/). It's there so CI can build an installable APK, and it protects nothing; a store build would use a new private key.

## Limits

- Devnet only.
- When SMT is sitting out (WAIT), the app grades its **lean**, the conviction-weighted sum of its personas' votes, and labels it as a lean. If the votes cancel out, or SMT's feed is more than 6 hours old, SMT isn't scored for that call.
- History reads the wallet's latest 200 transactions.
- Grades use Binance's 1-minute candle at the 4-hour mark, or CoinGecko's nearest point when Binance can't be reached. The source is stored with each grade.

## Disclosure

Built by Jannet Ekka with Claude Code (Anthropic's coding agent), which wrote most of the code under her direction.
