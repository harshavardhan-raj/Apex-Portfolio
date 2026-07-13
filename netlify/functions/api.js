const FINNHUB_KEY = "d0096kpr01qud9qm9ot0d0096kpr01qud9qm9otg";

exports.handler = async (event, context) => {
    // Enable CORS
    const headers = {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Headers": "Content-Type",
        "Access-Control-Allow-Methods": "GET, POST, OPTIONS"
    };

    if (event.httpMethod === "OPTIONS") {
        return { statusCode: 204, headers };
    }

    const path = event.path.replace(/\/\.netlify\/functions\/api/, "").replace(/\/api/, "");
    const query = event.queryStringParameters || {};

    try {
        if (path.startsWith("/quote")) {
            const symbol = (query.symbol || "").toUpperCase();
            if (!symbol) {
                return { statusCode: 400, headers, body: JSON.stringify({ error: "Symbol required" }) };
            }
            const data = await fetchQuote(symbol);
            return { statusCode: 200, headers, body: JSON.stringify(data) };
        } 
        
        else if (path.startsWith("/history")) {
            const symbol = (query.symbol || "").toUpperCase();
            const range = (query.range || "1M").toUpperCase();
            if (!symbol) {
                return { statusCode: 400, headers, body: JSON.stringify({ error: "Symbol required" }) };
            }
            const data = await fetchHistory(symbol, range);
            return { statusCode: 200, headers, body: JSON.stringify(data) };
        } 
        
        else if (path.startsWith("/news")) {
            const category = query.category || "general";
            const symbol = query.symbol;
            let url = "";
            if (symbol) {
                url = `https://finnhub.io/api/v1/company-news?symbol=${encodeURIComponent(symbol)}&token=${FINNHUB_KEY}`;
            } else {
                url = `https://finnhub.io/api/v1/news?category=${encodeURIComponent(category)}&token=${FINNHUB_KEY}`;
            }
            const resp = await fetch(url);
            const data = await resp.json();
            return { statusCode: 200, headers, body: JSON.stringify(data) };
        } 
        
        else if (path.startsWith("/ticker")) {
            const symbols = (query.symbols || "").split(",");
            const quotes = await Promise.all(symbols.map(async (sym) => {
                try {
                    return await fetchQuote(sym);
                } catch (e) {
                    return { symbol: sym, currentPrice: 0, change: 0, changePercent: 0 };
                }
            }));
            return { statusCode: 200, headers, body: JSON.stringify(quotes) };
        } 
        
        else if (path.startsWith("/profile")) {
            const symbol = (query.symbol || "").toUpperCase();
            if (!symbol) {
                return { statusCode: 400, headers, body: JSON.stringify({ error: "Symbol required" }) };
            }
            const data = await fetchProfile(symbol);
            return { statusCode: 200, headers, body: JSON.stringify(data) };
        }

        return { statusCode: 404, headers, body: JSON.stringify({ error: "Endpoint not found" }) };

    } catch (err) {
        console.error("API handler error:", err);
        return { statusCode: 500, headers, body: JSON.stringify({ error: err.message }) };
    }
};

async function fetchQuote(symbol) {
    if (symbol.includes(".")) {
        return fetchQuoteFromYahoo(symbol);
    }
    try {
        const finn = await fetchQuoteFromFinnhub(symbol);
        if (finn && finn.currentPrice > 0) return finn;
    } catch (e) {}

    // Fallbacks
    try { return await fetchQuoteFromYahoo(symbol + ".NS"); } catch (e) {}
    try { return await fetchQuoteFromYahoo(symbol + ".BO"); } catch (e) {}
    return await fetchQuoteFromYahoo(symbol);
}

async function fetchQuoteFromFinnhub(symbol) {
    const url = `https://finnhub.io/api/v1/quote?symbol=${encodeURIComponent(symbol)}&token=${FINNHUB_KEY}`;
    const resp = await fetch(url);
    const raw = await resp.json();
    return {
        symbol,
        currentPrice: raw.c || 0,
        change: raw.d || 0,
        changePercent: raw.dp || 0,
        high: raw.h || 0,
        low: raw.l || 0,
        open: raw.o || 0,
        prevClose: raw.pc || 0
    };
}

async function fetchQuoteFromYahoo(symbol) {
    const url = `https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(symbol)}?range=1d&interval=1d`;
    const resp = await fetch(url, {
        headers: { "User-Agent": "Mozilla/5.0" }
    });
    const raw = await resp.json();
    const result = raw?.chart?.result?.[0];
    const meta = result?.meta;
    const currentPrice = meta?.regularMarketPrice || 0;
    const prevClose = meta?.chartPreviousClose || 0;
    const change = currentPrice - prevClose;
    const changePercent = prevClose > 0 ? (change / prevClose) * 100 : 0;
    
    const quote = result?.indicators?.quote?.[0];
    const high = meta?.regularMarketDayHigh || quote?.high?.[0] || 0;
    const low = meta?.regularMarketDayLow || quote?.low?.[0] || 0;
    const open = quote?.open?.[0] || 0;

    return {
        symbol,
        currentPrice,
        change,
        changePercent,
        high,
        low,
        open,
        prevClose
    };
}

async function fetchProfile(symbol) {
    if (symbol.includes(".")) {
        return fetchProfileFromYahoo(symbol);
    }
    try {
        const finn = await fetchProfileFromFinnhub(symbol);
        if (finn && finn.name) return finn;
    } catch (e) {}

    try { return await fetchProfileFromYahoo(symbol + ".NS"); } catch (e) {}
    try { return await fetchProfileFromYahoo(symbol + ".BO"); } catch (e) {}
    return await fetchProfileFromYahoo(symbol);
}

async function fetchProfileFromFinnhub(symbol) {
    const url = `https://finnhub.io/api/v1/stock/profile2?symbol=${encodeURIComponent(symbol)}&token=${FINNHUB_KEY}`;
    const resp = await fetch(url);
    const raw = await resp.json();
    return {
        ticker: symbol,
        name: raw.name || symbol,
        marketCapitalization: raw.marketCapitalization || 0,
        logo: raw.logo || ""
    };
}

async function fetchProfileFromYahoo(symbol) {
    const url = `https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(symbol)}?range=1d&interval=1d`;
    const resp = await fetch(url, {
        headers: { "User-Agent": "Mozilla/5.0" }
    });
    const raw = await resp.json();
    const result = raw?.chart?.result?.[0];
    const meta = result?.meta;
    const name = meta?.longName || meta?.shortName || symbol;
    return {
        ticker: symbol,
        name,
        marketCapitalization: 0,
        logo: ""
    };
}

async function fetchHistory(symbol, range) {
    let yfRange = "1mo";
    let yfInterval = "1d";

    switch (range) {
        case "1D":
            yfRange = "1d";
            yfInterval = "15m";
            break;
        case "1W":
            yfRange = "5d";
            yfInterval = "30m";
            break;
        case "1M":
            yfRange = "1mo";
            yfInterval = "1d";
            break;
        case "6M":
            yfRange = "6mo";
            yfInterval = "1d";
            break;
        case "1Y":
            yfRange = "1y";
            yfInterval = "1d";
            break;
        case "5Y":
            yfRange = "5y";
            yfInterval = "1wk";
            break;
        case "MAX":
            yfRange = "max";
            yfInterval = "1mo";
            break;
    }

    const url = `https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(symbol)}?range=${yfRange}&interval=${yfInterval}`;
    const resp = await fetch(url, {
        headers: { "User-Agent": "Mozilla/5.0" }
    });
    const raw = await resp.json();
    const result = raw?.chart?.result?.[0];
    
    if (!result) {
        return { s: "no_data" };
    }

    const timestamps = result?.timestamp || [];
    const quote = result?.indicators?.quote?.[0] || {};
    const open = quote?.open || [];
    const high = quote?.high || [];
    const low = quote?.low || [];
    const close = quote?.close || [];
    const volume = quote?.volume || [];

    // Filter out null values
    const t = [];
    const o = [];
    const h = [];
    const l = [];
    const c = [];
    const v = [];

    for (let i = 0; i < timestamps.length; i++) {
        if (timestamps[i] != null && open[i] != null && high[i] != null && low[i] != null && close[i] != null && volume[i] != null) {
            t.push(timestamps[i]);
            o.push(open[i]);
            h.push(high[i]);
            l.push(low[i]);
            c.push(close[i]);
            v.push(volume[i]);
        }
    }

    return { s: "ok", t, o, h, l, c, v };
}
