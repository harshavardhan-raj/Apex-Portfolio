import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class StockPortfolioWebApp {

    private static final String API_KEY = "d0096kpr01qud9qm9ot0d0096kpr01qud9qm9otg";
    private static final String API_URL = "https://finnhub.io/api/v1/quote?symbol=";
    private static final String NEWS_API_URL = "https://finnhub.io/api/v1/news?category=general&token=";
    private static final String COMPANY_NEWS_API_URL = "https://finnhub.io/api/v1/company-news?symbol=";

    // Track holdings: Ticker -> Position
    private static final Map<String, Position> portfolio = new ConcurrentHashMap<>();
    private static double cashBalance = 100000.00;

    static class Position {
        String symbol;
        double quantity;
        double averageBuyPrice;

        Position(String symbol, double quantity, double averageBuyPrice) {
            this.symbol = symbol.toUpperCase();
            this.quantity = quantity;
            this.averageBuyPrice = averageBuyPrice;
        }
    }

    // Generic Cache Entry
    static class CacheEntry<T> {
        final T data;
        final long expiresAt;

        CacheEntry(T data, long ttlMillis) {
            this.data = data;
            this.expiresAt = System.currentTimeMillis() + ttlMillis;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    // Cache instances
    private static final Map<String, CacheEntry<String>> quoteCache = new ConcurrentHashMap<>();
    private static final Map<String, CacheEntry<String>> profileCache = new ConcurrentHashMap<>();
    private static final Map<String, CacheEntry<String>> historyCache = new ConcurrentHashMap<>();

    // Helper: generic URL content fetcher
    private static String fetchUrlContent(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        int responseCode = conn.getResponseCode();
        if (responseCode == 200) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                return response.toString();
            }
        } else {
            throw new IOException("HTTP error code: " + responseCode);
        }
    }

    private static String getCachedQuote(String symbol) {
        CacheEntry<String> entry = quoteCache.get(symbol);
        if (entry != null && !entry.isExpired()) {
            return entry.data;
        }
        return null;
    }

    // Dynamic Symbol Normalization
    private static String normalizeSymbol(String symbol) {
        if (symbol == null) return "";
        String normalized = symbol.toUpperCase().trim();
        if (normalized.isEmpty()) return "";
        if (normalized.contains(".")) {
            return normalized;
        }

        // Fast cache check
        String cached = getCachedQuote(normalized);
        if (cached != null) {
            return normalized;
        }

        // Try querying Finnhub to see if it's a valid US ticker
        try {
            String finnhubResult = fetchQuoteFromFinnhub(normalized);
            double price = extractJsonDouble(finnhubResult, "\"currentPrice\":");
            if (price > 0.0) {
                return normalized; // US ticker
            }
        } catch (Exception ignored) {}

        // Fallback checks for Indian suffixes: try .NS first (NSE)
        try {
            String urlString = "https://query1.finance.yahoo.com/v8/finance/chart/" + URLEncoder.encode(normalized + ".NS", StandardCharsets.UTF_8) + "?range=1d&interval=1d";
            fetchUrlContent(urlString);
            return normalized + ".NS";
        } catch (Exception e1) {
            // Try .BO next (BSE)
            try {
                String urlString = "https://query1.finance.yahoo.com/v8/finance/chart/" + URLEncoder.encode(normalized + ".BO", StandardCharsets.UTF_8) + "?range=1d&interval=1d";
                fetchUrlContent(urlString);
                return normalized + ".BO";
            } catch (Exception e2) {
                // If all fails, keep the original symbol
                return normalized;
            }
        }
    }

    private static String fetchQuote(String symbol) throws IOException {
        String normalized = symbol.toUpperCase().trim();
        if (normalized.contains(".")) {
            return fetchQuoteFromYahoo(normalized);
        }

        // Route plain symbols to Finnhub first, fall back to Yahoo Finance
        try {
            String finnhubResult = fetchQuoteFromFinnhub(normalized);
            double price = extractJsonDouble(finnhubResult, "\"currentPrice\":");
            if (price > 0.0) {
                return finnhubResult;
            }
        } catch (Exception ignored) {}

        // Try Yahoo Finance .NS
        try {
            return fetchQuoteFromYahoo(normalized + ".NS");
        } catch (Exception e1) {
            // Try Yahoo Finance .BO
            try {
                return fetchQuoteFromYahoo(normalized + ".BO");
            } catch (Exception e2) {
                // Try Yahoo Finance plain
                try {
                    return fetchQuoteFromYahoo(normalized);
                } catch (Exception e3) {
                    throw new IOException("Symbol not found on any source: " + symbol);
                }
            }
        }
    }

    private static String fetchQuoteFromFinnhub(String symbol) throws IOException {
        String urlString = API_URL + symbol + "&token=" + API_KEY;
        String rawJson = fetchUrlContent(urlString);

        double current = extractJsonDouble(rawJson, "\"c\":");
        double change = extractJsonDouble(rawJson, "\"d\":");
        double changePercent = extractJsonDouble(rawJson, "\"dp\":");
        double high = extractJsonDouble(rawJson, "\"h\":");
        double low = extractJsonDouble(rawJson, "\"l\":");
        double open = extractJsonDouble(rawJson, "\"o\":");
        double prevClose = extractJsonDouble(rawJson, "\"pc\":");

        return String.format(Locale.US,
            "{\"symbol\":\"%s\",\"currentPrice\":%.4f,\"change\":%.4f,\"changePercent\":%.4f,\"high\":%.4f,\"low\":%.4f,\"open\":%.4f,\"prevClose\":%.4f}",
            symbol, current, change, changePercent, high, low, open, prevClose
        );
    }

    private static String fetchQuoteFromYahoo(String symbol) throws IOException {
        String urlString = "https://query1.finance.yahoo.com/v8/finance/chart/" + URLEncoder.encode(symbol, StandardCharsets.UTF_8) + "?range=1d&interval=1d";
        String rawJson = fetchUrlContent(urlString);

        double current = extractJsonDouble(rawJson, "\"regularMarketPrice\":");
        double prevClose = extractJsonDouble(rawJson, "\"chartPreviousClose\":");
        double high = extractJsonDouble(rawJson, "\"regularMarketDayHigh\":");
        if (high == 0.0) high = extractJsonDouble(rawJson, "\"high\":");
        double low = extractJsonDouble(rawJson, "\"regularMarketDayLow\":");
        if (low == 0.0) low = extractJsonDouble(rawJson, "\"low\":");
        double open = extractJsonDouble(rawJson, "\"open\":");

        double change = current - prevClose;
        double changePercent = prevClose > 0 ? (change / prevClose) * 100 : 0.0;

        return String.format(Locale.US,
            "{\"symbol\":\"%s\",\"currentPrice\":%.4f,\"change\":%.4f,\"changePercent\":%.4f,\"high\":%.4f,\"low\":%.4f,\"open\":%.4f,\"prevClose\":%.4f}",
            symbol, current, change, changePercent, high, low, open, prevClose
        );
    }

    private static String fetchProfile(String symbol) throws IOException {
        String normalized = symbol.toUpperCase().trim();
        if (normalized.contains(".")) {
            return fetchProfileFromYahoo(normalized);
        }

        try {
            String finnhubResult = fetchProfileFromFinnhub(normalized);
            if (finnhubResult != null && !finnhubResult.isEmpty() && !finnhubResult.equals("{}") && finnhubResult.contains("\"name\":")) {
                return finnhubResult;
            }
        } catch (Exception ignored) {}

        // Fallback to Yahoo Finance suffixes
        try {
            return fetchProfileFromYahoo(normalized + ".NS");
        } catch (Exception e1) {
            try {
                return fetchProfileFromYahoo(normalized + ".BO");
            } catch (Exception e2) {
                try {
                    return fetchProfileFromYahoo(normalized);
                } catch (Exception e3) {
                    throw new IOException("Profile not found for: " + symbol);
                }
            }
        }
    }

    private static String fetchProfileFromFinnhub(String symbol) throws IOException {
        String urlString = "https://finnhub.io/api/v1/stock/profile2?symbol=" + symbol + "&token=" + API_KEY;
        return fetchUrlContent(urlString);
    }

    private static String fetchProfileFromYahoo(String symbol) throws IOException {
        String urlString = "https://query1.finance.yahoo.com/v8/finance/chart/" + URLEncoder.encode(symbol, StandardCharsets.UTF_8) + "?range=1d&interval=1d";
        String rawJson = fetchUrlContent(urlString);

        String name = "";
        int index = rawJson.indexOf("\"longName\":\"");
        if (index != -1) {
            int start = index + "\"longName\":\"".length();
            int end = rawJson.indexOf("\"", start);
            if (end != -1) {
                name = rawJson.substring(start, end);
            }
        }
        if (name.isEmpty()) {
            index = rawJson.indexOf("\"shortName\":\"");
            if (index != -1) {
                int start = index + "\"shortName\":\"".length();
                int end = rawJson.indexOf("\"", start);
                if (end != -1) {
                    name = rawJson.substring(start, end);
                }
            }
        }
        if (name.isEmpty()) {
            name = symbol;
        }

        return String.format(Locale.US,
            "{\"ticker\":\"%s\",\"name\":\"%s\",\"marketCapitalization\":0.0,\"logo\":\"\"}",
            symbol, name
        );
    }

    private static String fetchAndCacheQuote(String symbol) throws IOException {
        String resultJson = fetchQuote(symbol);
        quoteCache.put(symbol, new CacheEntry<>(resultJson, 30000)); // Cache for 30 seconds
        return resultJson;
    }

    public static void main(String[] args) throws IOException {
        // Load existing portfolio from disk
        loadPortfolio();

        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);

        // UI Page Handler
        server.createContext("/", new HtmlHandler());
        server.createContext("/button", new ButtonHtmlHandler());

        // API Handlers
        server.createContext("/api/portfolio", new GetPortfolioHandler());
        server.createContext("/api/add", new AddStockHandler());
        server.createContext("/api/remove", new RemoveStockHandler());
        server.createContext("/api/news", new NewsHandler());
        server.createContext("/api/quote", new QuoteHandler());
        server.createContext("/api/cash", new CashHandler());
        server.createContext("/api/profile", new ProfileHandler());
        server.createContext("/api/history", new HistoryHandler());
        server.createContext("/api/ticker", new TickerHandler());

        server.setExecutor(null);
        server.start();
        System.out.println("Stock Portfolio Web App Server started at http://localhost:8000/");
    }

    // Helper to send JSON responses with CORS headers
    private static void sendJsonResponse(HttpExchange exchange, int statusCode, String jsonResponse) throws IOException {
        byte[] bytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // Helper to handle OPTIONS preflight
    private static boolean handleOptions(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
            exchange.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    // Serving the HTML dashboard
    static class HtmlHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            Path htmlPath = Paths.get("portfolio.html");
            if (!Files.exists(htmlPath)) {
                String error = "<html><body><h2>portfolio.html not found! Please make sure it exists.</h2></body></html>";
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(404, error.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(error.getBytes());
                }
                return;
            }
            byte[] html = Files.readAllBytes(htmlPath);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(html);
            }
        }
    }

    // Serving the Button showcase page
    static class ButtonHtmlHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            Path htmlPath = Paths.get("button.html");
            if (!Files.exists(htmlPath)) {
                String error = "<html><body><h2>button.html not found! Please make sure it exists.</h2></body></html>";
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(404, error.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(error.getBytes());
                }
                return;
            }
            byte[] html = Files.readAllBytes(htmlPath);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(html);
            }
        }
    }

    // GET /api/portfolio
    static class GetPortfolioHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\": \"Method not allowed\"}");
                return;
            }

            double totalCost = 0;
            double totalValue = 0;

            StringBuilder holdingsJson = new StringBuilder("[");
            int i = 0;
            int size = portfolio.size();

            for (Position pos : portfolio.values()) {
                double currentPrice = getStockPrice(pos.symbol);
                if (currentPrice == 0 && pos.averageBuyPrice > 0) {
                    currentPrice = pos.averageBuyPrice; // Fallback to cost basis if API fails
                }
                double costBasis = pos.averageBuyPrice * pos.quantity;
                double marketValue = currentPrice * pos.quantity;
                double profitOrLoss = marketValue - costBasis;
                double profitOrLossPercentage = costBasis > 0 ? (profitOrLoss / costBasis) * 100 : 0;

                // Dynamic company name retrieval
                String companyName = pos.symbol;
                try {
                    String profCached = profileCache.get(pos.symbol) != null ? profileCache.get(pos.symbol).data : null;
                    if (profCached == null) {
                        profCached = fetchProfile(pos.symbol);
                    }
                    int nameIdx = profCached.indexOf("\"name\":\"");
                    if (nameIdx != -1) {
                        int nameStart = nameIdx + "\"name\":\"".length();
                        int nameEnd = profCached.indexOf("\"", nameStart);
                        if (nameEnd != -1) {
                            companyName = profCached.substring(nameStart, nameEnd);
                        }
                    }
                } catch (Exception ignored) {}

                totalCost += costBasis;
                totalValue += marketValue;

                holdingsJson.append(String.format(Locale.US,
                    "{\"symbol\":\"%s\",\"name\":\"%s\",\"quantity\":%.4f,\"averageBuyPrice\":%.4f,\"currentPrice\":%.4f,\"costBasis\":%.4f,\"marketValue\":%.4f,\"profitOrLoss\":%.4f,\"profitOrLossPercentage\":%.4f}",
                    pos.symbol, companyName, pos.quantity, pos.averageBuyPrice, currentPrice, costBasis, marketValue, profitOrLoss, profitOrLossPercentage
                ));

                if (i < size - 1) {
                    holdingsJson.append(",");
                }
                i++;
            }
            holdingsJson.append("]");

            double totalProfitOrLoss = totalValue - totalCost;
            double totalProfitOrLossPercentage = totalCost > 0 ? (totalProfitOrLoss / totalCost) * 100 : 0;

            String response = String.format(Locale.US,
                "{\"holdings\":%s,\"totalCost\":%.4f,\"totalValue\":%.4f,\"totalProfitOrLoss\":%.4f,\"totalProfitOrLossPercentage\":%.4f,\"cashBalance\":%.4f,\"netWorth\":%.4f}",
                holdingsJson.toString(), totalCost, totalValue, totalProfitOrLoss, totalProfitOrLossPercentage, cashBalance, (totalValue + cashBalance)
            );

            sendJsonResponse(exchange, 200, response);
        }
    }

    // POST /api/add
    static class AddStockHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\": \"Method not allowed\"}");
                return;
            }

            try {
                Map<String, String> params = parseRequestBody(exchange);
                String symbol = params.get("symbol");
                if (symbol == null || symbol.trim().isEmpty()) {
                    sendJsonResponse(exchange, 400, "{\"error\": \"Symbol is required\"}");
                    return;
                }
                symbol = symbol.trim().toUpperCase();
                symbol = normalizeSymbol(symbol);

                double quantity = Double.parseDouble(params.get("quantity"));
                if (quantity <= 0) {
                    sendJsonResponse(exchange, 400, "{\"error\": \"Quantity must be positive\"}");
                    return;
                }

                double purchasePrice = 0;
                String priceStr = params.get("price");
                if (priceStr != null && !priceStr.trim().isEmpty()) {
                    purchasePrice = Double.parseDouble(priceStr);
                }

                // If buy price is not specified, query current price from Finnhub
                if (purchasePrice <= 0) {
                    purchasePrice = getStockPrice(symbol);
                    if (purchasePrice <= 0) {
                        sendJsonResponse(exchange, 400, "{\"error\": \"Unable to fetch current price. Please specify purchase price manually.\" }");
                        return;
                    }
                }

                double costBasis = quantity * purchasePrice;
                if (cashBalance < costBasis) {
                    sendJsonResponse(exchange, 400, String.format(Locale.US,
                        "{\"error\": \"Insufficient cash balance. Required: $%.2f, Available: $%.2f\"}",
                        costBasis, cashBalance
                    ));
                    return;
                }

                cashBalance -= costBasis;

                // Update portfolio average cost
                Position pos = portfolio.get(symbol);
                if (pos == null) {
                    pos = new Position(symbol, quantity, purchasePrice);
                } else {
                    double oldQty = pos.quantity;
                    double oldAvg = pos.averageBuyPrice;
                    double newQty = oldQty + quantity;
                    double newAvg = ((oldQty * oldAvg) + (quantity * purchasePrice)) / newQty;
                    pos.quantity = newQty;
                    pos.averageBuyPrice = newAvg;
                }
                portfolio.put(symbol, pos);

                // Persist portfolio
                savePortfolio();

                sendJsonResponse(exchange, 200, String.format(Locale.US,
                    "{\"status\":\"success\",\"message\":\"Added stock\",\"symbol\":\"%s\",\"quantity\":%.4f,\"averageBuyPrice\":%.4f}",
                    symbol, pos.quantity, pos.averageBuyPrice
                ));
            } catch (Exception e) {
                sendJsonResponse(exchange, 400, "{\"error\": \"Invalid request: " + e.getMessage() + "\"}");
            }
        }
    }

    // POST /api/remove
    static class RemoveStockHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\": \"Method not allowed\"}");
                return;
            }

            try {
                Map<String, String> params = parseRequestBody(exchange);
                String symbol = params.get("symbol");
                if (symbol == null || symbol.trim().isEmpty()) {
                    sendJsonResponse(exchange, 400, "{\"error\": \"Symbol is required\"}");
                    return;
                }
                symbol = symbol.trim().toUpperCase();
                symbol = normalizeSymbol(symbol);

                Position pos = portfolio.get(symbol);
                if (pos == null) {
                    sendJsonResponse(exchange, 404, "{\"error\": \"Stock not found in portfolio\"}");
                    return;
                }

                // Query current price from Finnhub to determine sale cash proceeds
                double salePrice = getStockPrice(symbol);
                if (salePrice <= 0) {
                    salePrice = pos.averageBuyPrice; // Fallback
                }

                double quantityToRemove = pos.quantity;
                String qtyStr = params.get("quantity");
                if (qtyStr != null && !qtyStr.trim().isEmpty()) {
                    quantityToRemove = Double.parseDouble(qtyStr);
                    if (quantityToRemove <= 0) {
                        sendJsonResponse(exchange, 400, "{\"error\": \"Quantity must be positive\"}");
                        return;
                    }
                }

                // Cap quantity to remove at pos.quantity
                if (quantityToRemove > pos.quantity) {
                    quantityToRemove = pos.quantity;
                }

                double saleProceeds = quantityToRemove * salePrice;
                cashBalance += saleProceeds;

                if (quantityToRemove >= pos.quantity) {
                    portfolio.remove(symbol);
                } else {
                    pos.quantity -= quantityToRemove;
                    portfolio.put(symbol, pos);
                }

                // Persist portfolio
                savePortfolio();

                sendJsonResponse(exchange, 200, "{\"status\":\"success\",\"message\":\"Stock removed/reduced\"}");
            } catch (Exception e) {
                sendJsonResponse(exchange, 400, "{\"error\": \"Invalid request: " + e.getMessage() + "\"}");
            }
        }
    }

    // GET /api/news
    static class NewsHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\": \"Method not allowed\"}");
                return;
            }

            Map<String, String> queryParams = parseQueryString(exchange.getRequestURI().getQuery());
            String symbol = queryParams.get("symbol");

            String urlString;
            if (symbol != null && !symbol.trim().isEmpty()) {
                symbol = symbol.trim().toUpperCase();
                LocalDate today = LocalDate.now();
                LocalDate oneWeekAgo = today.minusDays(7);
                urlString = COMPANY_NEWS_API_URL + symbol + "&from=" + oneWeekAgo.toString() + "&to=" + today.toString() + "&token=" + API_KEY;
            } else {
                urlString = NEWS_API_URL + API_KEY;
            }

            try {
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder rawJson = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            rawJson.append(line);
                        }
                        sendJsonResponse(exchange, 200, rawJson.toString());
                    }
                } else {
                    sendJsonResponse(exchange, responseCode, "{\"error\": \"Error fetching news from provider\"}");
                }
            } catch (Exception e) {
                sendJsonResponse(exchange, 500, "{\"error\": \"Exception fetching news: " + e.getMessage() + "\"}");
            }
        }
    }

    // GET /api/quote?symbol=XYZ
    static class QuoteHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\": \"Method not allowed\"}");
                return;
            }

            Map<String, String> queryParams = parseQueryString(exchange.getRequestURI().getQuery());
            String symbol = queryParams.get("symbol");

            if (symbol == null || symbol.trim().isEmpty()) {
                sendJsonResponse(exchange, 400, "{\"error\": \"Symbol is required\"}");
                return;
            }
            symbol = symbol.trim().toUpperCase();
            symbol = normalizeSymbol(symbol);

            try {
                String cached = getCachedQuote(symbol);
                if (cached != null) {
                    sendJsonResponse(exchange, 200, cached);
                    return;
                }
                String resultJson = fetchAndCacheQuote(symbol);
                sendJsonResponse(exchange, 200, resultJson);
            } catch (Exception e) {
                sendJsonResponse(exchange, 500, "{\"error\": \"Exception fetching quote: " + e.getMessage() + "\"}");
            }
        }
    }

    // Helper: Fetch current stock price from Finnhub
    private static double getStockPrice(String symbol) {
        try {
            String cached = getCachedQuote(symbol);
            if (cached != null) {
                return extractJsonDouble(cached, "\"currentPrice\":");
            }
            String fetched = fetchAndCacheQuote(symbol);
            return extractJsonDouble(fetched, "\"currentPrice\":");
        } catch (Exception e) {
            return 0.0;
        }
    }

    // GET /api/profile?symbol=XYZ
    static class ProfileHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\": \"Method not allowed\"}");
                return;
            }

            Map<String, String> queryParams = parseQueryString(exchange.getRequestURI().getQuery());
            String symbol = queryParams.get("symbol");

            if (symbol == null || symbol.trim().isEmpty()) {
                sendJsonResponse(exchange, 400, "{\"error\": \"Symbol is required\"}");
                return;
            }
            symbol = symbol.trim().toUpperCase();
            symbol = normalizeSymbol(symbol);

            try {
                CacheEntry<String> entry = profileCache.get(symbol);
                if (entry != null && !entry.isExpired()) {
                    sendJsonResponse(exchange, 200, entry.data);
                    return;
                }

                String rawJson = fetchProfile(symbol);

                // Cache profile for 24 hours (24 * 60 * 60 * 1000 ms)
                profileCache.put(symbol, new CacheEntry<>(rawJson, 24L * 60 * 60 * 1000));
                sendJsonResponse(exchange, 200, rawJson);
            } catch (Exception e) {
                sendJsonResponse(exchange, 500, "{\"error\": \"Exception fetching profile: " + e.getMessage() + "\"}");
            }
        }
    }

    // GET /api/history?symbol=XYZ&range=1M
    static class HistoryHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\": \"Method not allowed\"}");
                return;
            }

            Map<String, String> queryParams = parseQueryString(exchange.getRequestURI().getQuery());
            String symbol = queryParams.get("symbol");
            String range = queryParams.get("range"); // 1D, 1W, 1M, 6M, 1Y, 5Y, MAX

            if (symbol == null || symbol.trim().isEmpty()) {
                sendJsonResponse(exchange, 400, "{\"error\": \"Symbol is required\"}");
                return;
            }
            symbol = symbol.trim().toUpperCase();
            symbol = normalizeSymbol(symbol);

            if (range == null || range.trim().isEmpty()) {
                range = "1M";
            }
            range = range.trim().toUpperCase();

            try {
                String cacheKey = symbol + "_" + range;
                CacheEntry<String> entry = historyCache.get(cacheKey);
                if (entry != null && !entry.isExpired()) {
                    sendJsonResponse(exchange, 200, entry.data);
                    return;
                }

                String yfRange;
                String yfInterval;
                long ttl;

                switch (range) {
                    case "1D":
                        yfRange = "1d";
                        yfInterval = "15m";
                        ttl = 5 * 60 * 1000; // 5 mins cache
                        break;
                    case "1W":
                        yfRange = "5d";
                        yfInterval = "30m";
                        ttl = 30 * 60 * 1000; // 30 mins cache
                        break;
                    case "1M":
                        yfRange = "1mo";
                        yfInterval = "1d";
                        ttl = 1 * 3600 * 1000; // 1 hour cache
                        break;
                    case "6M":
                        yfRange = "6mo";
                        yfInterval = "1d";
                        ttl = 2 * 3600 * 1000; // 2 hours cache
                        break;
                    case "1Y":
                        yfRange = "1y";
                        yfInterval = "1d";
                        ttl = 4 * 3600 * 1000; // 4 hours cache
                        break;
                    case "5Y":
                        yfRange = "5y";
                        yfInterval = "1wk";
                        ttl = 12 * 3600 * 1000; // 12 hours cache
                        break;
                    case "MAX":
                    default:
                        yfRange = "max";
                        yfInterval = "1mo";
                        ttl = 24L * 3600 * 1000; // 24 hours cache
                        break;
                }

                String urlString = "https://query1.finance.yahoo.com/v8/finance/chart/" + URLEncoder.encode(symbol, StandardCharsets.UTF_8) +
                                   "?range=" + yfRange +
                                   "&interval=" + yfInterval;
                String rawJson = fetchUrlContent(urlString);

                // Convert Yahoo Finance JSON to Finnhub compatible candle format
                List<Double> t = extractJsonArray(rawJson, "\"timestamp\":");
                List<Double> o = extractJsonArray(rawJson, "\"open\":");
                List<Double> h = extractJsonArray(rawJson, "\"high\":");
                List<Double> l = extractJsonArray(rawJson, "\"low\":");
                List<Double> c = extractJsonArray(rawJson, "\"close\":");
                List<Double> v = extractJsonArray(rawJson, "\"volume\":");

                int minSize = t.size();
                minSize = Math.min(minSize, o.size());
                minSize = Math.min(minSize, h.size());
                minSize = Math.min(minSize, l.size());
                minSize = Math.min(minSize, c.size());
                minSize = Math.min(minSize, v.size());

                StringBuilder sbC = new StringBuilder("[");
                StringBuilder sbH = new StringBuilder("[");
                StringBuilder sbL = new StringBuilder("[");
                StringBuilder sbO = new StringBuilder("[");
                StringBuilder sbT = new StringBuilder("[");
                StringBuilder sbV = new StringBuilder("[");

                int validCount = 0;
                for (int i = 0; i < minSize; i++) {
                    Double closeVal = c.get(i);
                    Double openVal = o.get(i);
                    Double highVal = h.get(i);
                    Double lowVal = l.get(i);
                    Double volVal = v.get(i);
                    Double timeVal = t.get(i);

                    if (closeVal == null || openVal == null || highVal == null || lowVal == null || volVal == null || timeVal == null) {
                        continue;
                    }

                    if (validCount > 0) {
                        sbC.append(",");
                        sbH.append(",");
                        sbL.append(",");
                        sbO.append(",");
                        sbT.append(",");
                        sbV.append(",");
                    }

                    sbC.append(String.format(Locale.US, "%.4f", closeVal));
                    sbH.append(String.format(Locale.US, "%.4f", highVal));
                    sbL.append(String.format(Locale.US, "%.4f", lowVal));
                    sbO.append(String.format(Locale.US, "%.4f", openVal));
                    sbT.append(String.format(Locale.US, "%d", timeVal.longValue()));
                    sbV.append(String.format(Locale.US, "%d", volVal.longValue()));

                    validCount++;
                }

                sbC.append("]");
                sbH.append("]");
                sbL.append("]");
                sbO.append("]");
                sbT.append("]");
                sbV.append("]");

                String status = validCount > 0 ? "ok" : "no_data";
                String formattedJson = String.format(Locale.US,
                    "{\"c\":%s,\"h\":%s,\"l\":%s,\"o\":%s,\"s\":\"%s\",\"t\":%s,\"v\":%s}",
                    sbC.toString(), sbH.toString(), sbL.toString(), sbO.toString(), status, sbT.toString(), sbV.toString()
                );

                historyCache.put(cacheKey, new CacheEntry<>(formattedJson, ttl));
                sendJsonResponse(exchange, 200, formattedJson);
            } catch (Exception e) {
                sendJsonResponse(exchange, 500, "{\"error\": \"Exception fetching history: " + e.getMessage() + "\"}");
            }
        }
    }

    // GET /api/ticker?symbols=^GSPC,^NSEI,...
    static class TickerHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\": \"Method not allowed\"}");
                return;
            }

            Map<String, String> queryParams = parseQueryString(exchange.getRequestURI().getQuery());
            String symbolsStr = queryParams.get("symbols");
            if (symbolsStr == null || symbolsStr.trim().isEmpty()) {
                symbolsStr = "^NSEI,^BSESN,^NSEBANK,^GSPC,^DJI,^IXIC,^FTSE,^N225";
            }

            String[] symbols = symbolsStr.split(",");
            StringBuilder json = new StringBuilder("[");

            for (int i = 0; i < symbols.length; i++) {
                String sym = symbols[i].trim().toUpperCase();
                if (sym.isEmpty()) continue;

                try {
                    String cached = getCachedQuote(sym);
                    if (cached == null) {
                        cached = fetchAndCacheQuote(sym);
                    }
                    json.append(cached);
                } catch (Exception e) {
                    json.append(String.format(Locale.US,
                        "{\"symbol\":\"%s\",\"currentPrice\":0.0,\"change\":0.0,\"changePercent\":0.0,\"high\":0.0,\"low\":0.0,\"open\":0.0,\"prevClose\":0.0}",
                        sym
                    ));
                }

                if (i < symbols.length - 1) {
                    json.append(",");
                }
            }
            json.append("]");

            sendJsonResponse(exchange, 200, json.toString());
        }
    }

    // Helper: Safely extract float/double values from simple JSON
    private static double extractJsonDouble(String json, String key) {
        try {
            int index = json.indexOf(key);
            if (index == -1) return 0.0;
            int start = index + key.length();
            // Skip whitespaces
            while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
                start++;
            }
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-' || json.charAt(end) == '+')) {
                end++;
            }
            return Double.parseDouble(json.substring(start, end));
        } catch (Exception e) {
            return 0.0;
        }
    }

    // Helper: Safely extract arrays from simple JSON
    private static List<Double> extractJsonArray(String json, String key) {
        List<Double> list = new ArrayList<>();
        int index = json.indexOf(key);
        if (index == -1) return list;
        int start = json.indexOf("[", index + key.length());
        if (start == -1) return list;
        int end = json.indexOf("]", start);
        if (end == -1) return list;
        String content = json.substring(start + 1, end).trim();
        if (content.isEmpty()) return list;
        String[] tokens = content.split(",");
        for (String t : tokens) {
            try {
                String tokenVal = t.trim();
                if (tokenVal.equals("null")) {
                    list.add(null);
                } else {
                    list.add(Double.parseDouble(tokenVal));
                }
            } catch (Exception e) {
                list.add(null);
            }
        }
        return list;
    }

    // Helper: Parse request body (JSON or Form URL encoded)
    private static Map<String, String> parseRequestBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = is.read(buffer)) != -1) {
            bos.write(buffer, 0, len);
        }
        String body = bos.toString(StandardCharsets.UTF_8).trim();
        Map<String, String> map = new HashMap<>();
        if (body.isEmpty()) return map;

        if (body.startsWith("{")) {
            // Simple JSON parsing
            body = body.substring(1, body.length() - 1).trim();
            String[] pairs = body.split(",");
            for (String pair : pairs) {
                String[] kv = pair.split(":");
                if (kv.length >= 2) {
                    String key = kv[0].replace("\"", "").trim();
                    // Concat back values in case of colons inside value
                    String val = Arrays.stream(kv).skip(1).collect(Collectors.joining(":")).replace("\"", "").trim();
                    map.put(key, val);
                }
            }
        } else {
            // URL Encoded
            String[] pairs = body.split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=");
                if (kv.length > 0) {
                    String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                    String val = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
                    map.put(key, val);
                }
            }
        }
        return map;
    }

    // Helper: Parse query string from URL
    private static Map<String, String> parseQueryString(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=");
            try {
                String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                String val = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
                map.put(key, val);
            } catch (Exception ignored) {}
        }
        return map;
    }

    // Save portfolio positions to portfolio.json
    private static synchronized void savePortfolio() {
        try {
            StringBuilder json = new StringBuilder("{\n");
            json.append(String.format(Locale.US, "  \"cashBalance\": %.4f,\n", cashBalance));
            json.append("  \"holdings\": [\n");
            int size = portfolio.size();
            int i = 0;
            for (Position p : portfolio.values()) {
                json.append(String.format(Locale.US,
                    "    {\"symbol\": \"%s\", \"quantity\": %.4f, \"averageBuyPrice\": %.4f}",
                    p.symbol, p.quantity, p.averageBuyPrice
                ));
                if (i < size - 1) {
                    json.append(",\n");
                }
                i++;
            }
            json.append("\n  ]\n}");
            Files.writeString(Paths.get("portfolio.json"), json.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Error saving portfolio: " + e.getMessage());
        }
    }

    // Load portfolio positions from portfolio.json
    private static synchronized void loadPortfolio() {
        portfolio.clear();
        cashBalance = 100000.00; // Reset to default
        Path path = Paths.get("portfolio.json");
        if (!Files.exists(path)) {
            return;
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (content.isEmpty() || content.equals("[]") || content.equals("{}")) {
                return;
            }

            if (content.startsWith("{")) {
                // Parse cashBalance
                int cashIndex = content.indexOf("\"cashBalance\":");
                if (cashIndex != -1) {
                    int valStart = cashIndex + "\"cashBalance\":".length();
                    while (valStart < content.length() && (Character.isWhitespace(content.charAt(valStart)) || content.charAt(valStart) == ':')) {
                        valStart++;
                    }
                    int valEnd = valStart;
                    while (valEnd < content.length() && (Character.isDigit(content.charAt(valEnd)) || content.charAt(valEnd) == '.' || content.charAt(valEnd) == '-' || content.charAt(valEnd) == '+')) {
                        valEnd++;
                    }
                    try {
                        cashBalance = Double.parseDouble(content.substring(valStart, valEnd));
                    } catch (Exception ignored) {}
                }

                // Extract holdings array
                int holdingsIndex = content.indexOf("\"holdings\":");
                if (holdingsIndex != -1) {
                    int arrayStart = content.indexOf("[", holdingsIndex);
                    int arrayEnd = content.indexOf("]", arrayStart);
                    if (arrayStart != -1 && arrayEnd != -1) {
                        content = content.substring(arrayStart + 1, arrayEnd).trim();
                    } else {
                        content = "";
                    }
                }
            } else if (content.startsWith("[")) {
                // Old array format
                content = content.substring(1);
                if (content.endsWith("]")) {
                    content = content.substring(0, content.length() - 1);
                }
            }

            // Now parse holdings contents
            if (content.isEmpty()) return;
            String[] objects = content.split("\\},\\s*\\{");
            for (String obj : objects) {
                obj = obj.replace("{", "").replace("}", "").trim();
                if (obj.isEmpty()) continue;

                String symbol = "";
                double qty = 0;
                double avgPrice = 0;

                String[] pairs = obj.split(",");
                for (String pair : pairs) {
                    String[] kv = pair.split(":");
                    if (kv.length != 2) continue;
                    String key = kv[0].replace("\"", "").trim();
                    String value = kv[1].replace("\"", "").trim();
                    if (key.equalsIgnoreCase("symbol")) {
                        symbol = value.toUpperCase();
                    } else if (key.equalsIgnoreCase("quantity")) {
                        qty = Double.parseDouble(value);
                    } else if (key.equalsIgnoreCase("averageBuyPrice")) {
                        avgPrice = Double.parseDouble(value);
                    }
                }
                if (!symbol.isEmpty() && qty > 0) {
                    portfolio.put(symbol, new Position(symbol, qty, avgPrice));
                }
            }
        } catch (Exception e) {
            System.err.println("Error loading portfolio: " + e.getMessage());
        }
    }

    // POST /api/cash
    static class CashHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\": \"Method not allowed\"}");
                return;
            }
            try {
                Map<String, String> params = parseRequestBody(exchange);
                double amount = Double.parseDouble(params.get("amount"));
                cashBalance += amount;
                if (cashBalance < 0) cashBalance = 0; // Prevent negative balance
                savePortfolio();
                sendJsonResponse(exchange, 200, String.format(Locale.US,
                    "{\"status\":\"success\",\"cashBalance\":%.4f}", cashBalance
                ));
            } catch (Exception e) {
                sendJsonResponse(exchange, 400, "{\"error\": \"Invalid request: " + e.getMessage() + "\"}");
            }
        }
    }
}
