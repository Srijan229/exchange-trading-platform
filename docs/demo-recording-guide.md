# Demo recording guide

Use this guide to record a short walkthrough of the actual dashboard. The platform uses virtual balances and practice prices; it does not connect to real markets. Loading a scenario resets the separate scenario database, leaving the main exchange database intact.

## Before recording

1. Start Docker Desktop. From the repository root, run `docker compose up --build` and open `http://localhost:3000`.
2. Wait for the dashboard's **Live connection** indicator. Open a PowerShell window in the repository root for the optional restart step.
3. Start a screen recording that captures the dashboard and, during the restart, the PowerShell window. Do not include personal notifications or unrelated windows.

## Suggested walkthrough: about two minutes

| Time | Show | Explain |
| --- | --- | --- |
| 0:00–0:15 | The market selector, order book, account overview, and event timeline. | “This paper trading platform has two futures markets, a matching engine, shared collateral, and an account risk engine.” |
| 0:15–0:30 | Under **Scenario testing**, load **Liquidation recovery**. | “Alice starts with $100 and buys ten BTC contracts at $50.” |
| 0:30–0:45 | Select **Drop mark to $45**, then show Alice's equity, maintenance margin, position, and reduce-only order. | “The price drop leaves $50 in equity against $54 in maintenance margin, triggering liquidation.” |
| 0:45–1:00 | Select **Add first buyer**, then show the five remaining contracts and the partially filled liquidation order. | “Five contracts are closed; the remaining order and position are persisted.” |
| 1:00–1:20 | In PowerShell, run `docker compose kill demo-backend` followed by `docker compose up -d demo-backend`. Wait for **Live connection**, then return to the scenario. | “Only the backend restarts. PostgreSQL keeps the first fill and remaining exposure.” |
| 1:20–1:30 | Select **Add second buyer** and show the position, cash balance, and trade history. | “The remaining five contracts close. Alice ends with $50 and two liquidation fills, without opening a reverse position.” |
| 1:30–1:50 | Load **Shared collateral** and select **Submit simultaneous orders**. Show the reservation and open order. | “Each $350 order needs $70 against one $100 account; one market accepts an order and the other rejects it.” |
| 1:50–2:00 | Select **Cancel & try other market**. | “Cancelling releases the reservation so an order in the other market can be accepted.” |

## Screenshots to capture

- **Trading workspace:** show the selected market, order book, order entry, account overview, and event timeline.
- **Partial liquidation:** show Alice's five-contract position and the remaining reduce-only order after the first buyer.
- **Shared collateral:** show Alice's $100 cash, $70 reserved, and the one accepted order.

Store images under `docs/images/` and add Markdown links to the README only after checking that the captured numbers match the scenario. A recording can be linked from the README after it has been uploaded to a location you choose.
