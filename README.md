# Apex Portfolio Manager 

A premium, state-of-the-art quantitative assets tracker and single-page dashboard featuring clean architecture, glassmorphic UI aesthetics, and mock Cash accounts integration.

##  Features

- **Premium Glassmorphic Dashboard**: A clean, modern UI optimized with typography (`Inter` & `Plus Jakarta Sans`) and smooth hover animations.
- **Cash Accounts & Net Worth Tracking**:
  - Live **Net Worth** indicator (`Cash Balance + Holdings Market Value`).
  - Live mock **Cash Balance** container with an interactive cash deposit modal.
  - Purchases deduct transaction cost from cash.
  - Sells/Closes refund transaction proceeds back to cash at current market prices.
- **Weighted Average Cost Basis**: Automatically calculates your weighted cost average on positions when purchasing additional shares.
- **Search & Watchlist**: Local browser-stored watchlist integrated with real-time quote lookup streams.
- **Intelligence Timeline Feed**: Dual-tab timeline tracking general market news and symbol-specific portfolio news.

##  Architecture

- **Backend**: Lightweight Java HTTP server (`StockPortfolioWebApp.java`) managing position state persistence, cash accounting, and API proxy routing.
- **Frontend**: Single-Page Application (`portfolio.html`) powered by plain Vanilla Javascript and CSS with Chart.js visualization.
- **Persistence**: Positions and cash states are auto-saved to local `portfolio.json`.

##  How to Run

1. Make sure you have the Java Development Kit (JDK) installed.
2. Compile and start the backend:
   ```bash
   javac StockPortfolioWebApp.java
   java StockPortfolioWebApp
   ```
3. Open your browser and navigate to:
   ```
   http://localhost:8000
   ```
