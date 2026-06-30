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

    public static void main(String[] args) throws IOException {
        // Load existing portfolio from disk
        loadPortfolio();

        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);

        // UI Page Handler
        server.createContext("/", new HtmlHandler());

        // API Handlers
        server.createContext("/api/portfolio", new GetPortfolioHandler());
        server.createContext("/api/add", new AddStockHandler());
        server.createContext("/api/remove", new RemoveStockHandler());
        server.createContext("/api/news", new NewsHandler());
        server.createContext("/api/quote", new QuoteHandler());
        server.createContext("/api/cash", new CashHandler());

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

                totalCost += costBasis;
                totalValue += marketValue;

                holdingsJson.append(String.format(Locale.US,
                    "{\"symbol\":\"%s\",\"quantity\":%.4f,\"averageBuyPrice\":%.4f,\"currentPrice\":%.4f,\"costBasis\":%.4f,\"marketValue\":%.4f,\"profitOrLoss\":%.4f,\"profitOrLossPercentage\":%.4f}",
                    pos.symbol, pos.quantity, pos.averageBuyPrice, currentPrice, costBasis, marketValue, profitOrLoss, profitOrLossPercentage
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

            try {
                URL url = new URL(API_URL + symbol + "&token=" + API_KEY);
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
                        String json = rawJson.toString();

                        double current = extractJsonDouble(json, "\"c\":");
                        double change = extractJsonDouble(json, "\"d\":");
                        double changePercent = extractJsonDouble(json, "\"dp\":");
                        double high = extractJsonDouble(json, "\"h\":");
                        double low = extractJsonDouble(json, "\"l\":");
                        double open = extractJsonDouble(json, "\"o\":");
                        double prevClose = extractJsonDouble(json, "\"pc\":");

                        String resultJson = String.format(Locale.US,
                            "{\"symbol\":\"%s\",\"currentPrice\":%.4f,\"change\":%.4f,\"changePercent\":%.4f,\"high\":%.4f,\"low\":%.4f,\"open\":%.4f,\"prevClose\":%.4f}",
                            symbol, current, change, changePercent, high, low, open, prevClose
                        );
                        sendJsonResponse(exchange, 200, resultJson);
                    }
                } else {
                    sendJsonResponse(exchange, responseCode, "{\"error\": \"Error fetching quote from provider\"}");
                }
            } catch (Exception e) {
                sendJsonResponse(exchange, 500, "{\"error\": \"Exception fetching quote: " + e.getMessage() + "\"}");
            }
        }
    }

    // Helper: Fetch current stock price from Finnhub
    private static double getStockPrice(String symbol) {
        try {
            URL url = new URL(API_URL + symbol + "&token=" + API_KEY);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String response = reader.readLine();
                return extractJsonDouble(response, "\"c\":");
            }
        } catch (Exception e) {
            return 0.0;
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
