package com.sparrowwallet.sparrow.net;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.SparrowWallet;
import com.sparrowwallet.sparrow.event.ExchangeRatesUpdatedEvent;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.tern.http.client.HttpResponseException;
import javafx.concurrent.ScheduledService;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import org.apache.commons.lang3.time.DateUtils;
import org.girod.javafx.svgimage.SVGImage;
import org.girod.javafx.svgimage.SVGLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

public enum ExchangeSource {
    NONE("None", null) {
        @Override
        public List<Currency> getSupportedCurrencies() {
            return Collections.emptyList();
        }

        @Override
        public Double getExchangeRate(Currency currency) {
            return null;
        }

        @Override
        public Map<Date, Double> getHistoricalExchangeRates(Currency currency, Date start, Date end) {
            return Collections.emptyMap();
        }
    },
    COINBASE("Coinbase", "No historical rates") {
        @Override
        public List<Currency> getSupportedCurrencies() {
            return getRates().data.rates.keySet().stream().filter(code -> isValidISO4217Code(code.toUpperCase(Locale.ROOT)))
                    .map(code -> Currency.getInstance(code.toUpperCase(Locale.ROOT))).collect(Collectors.toList());
        }

        @Override
        public Double getExchangeRate(Currency currency) {
            String currencyCode = currency.getCurrencyCode();
            OptionalDouble optRate = getRates().data.rates.entrySet().stream().filter(rate -> currencyCode.equalsIgnoreCase(rate.getKey())).mapToDouble(Map.Entry::getValue).findFirst();
            if(optRate.isPresent()) {
                return optRate.getAsDouble();
            }

            return null;
        }

        @Override
        protected Map<Currency, Double> getAllExchangeRates() {
            return toCurrencyRates(getRates().data.rates);
        }

        private CoinbaseRates getRates() {
            String url = "https://api.coinbase.com/v2/exchange-rates?currency=BTC";

            if(log.isInfoEnabled()) {
                log.info("Requesting exchange rates from " + url);
            }

            HttpClientService httpClientService = AppServices.getHttpClientService();
            try {
                return httpClientService.requestJson(url, CoinbaseRates.class, null);
            } catch (Exception e) {
                if(log.isDebugEnabled()) {
                    log.warn("Error retrieving currency rates", e);
                } else {
                    log.warn("Error retrieving currency rates (" + e.getMessage() + ")");
                }
                return new CoinbaseRates();
            }
        }

        @Override
        public Map<Date, Double> getHistoricalExchangeRates(Currency currency, Date start, Date end) {
            Map<Date, Double> historicalRates = new TreeMap<>();
            DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

            Instant currentInstant = start.toInstant();
            Instant endInstant = end.toInstant();

            while(currentInstant.isBefore(endInstant) || currentInstant.equals(endInstant)) {
                Date fromDate = Date.from(currentInstant.atZone(ZoneId.systemDefault()).toInstant());
                currentInstant = currentInstant.plus(300, ChronoUnit.DAYS);
                Date toDate = Date.from(currentInstant.atZone(ZoneId.systemDefault()).toInstant());
                toDate = toDate.after(end) ? end : toDate;

                String startTime = dateFormat.format(fromDate);
                String endTime = dateFormat.format(toDate);

                String url = "https://api.pro.coinbase.com/products/BTC-" + currency.getCurrencyCode() + "/candles?start=" + startTime + "T12:00:00&end=" + endTime + "T12:00:00&granularity=86400";

                if(log.isInfoEnabled()) {
                    log.info("Requesting historical exchange rates from " + url);
                }

                HttpClientService httpClientService = AppServices.getHttpClientService();
                try {
                    Number[][] coinbaseData = httpClientService.requestJson(url, Number[][].class, HTTP_HEADERS);
                    for(Number[] price : coinbaseData) {
                        Date date = new Date(price[0].longValue() * 1000);
                        historicalRates.put(DateUtils.truncate(date, Calendar.DAY_OF_MONTH), price[4].doubleValue());
                    }
                } catch(Exception e) {
                    if(log.isDebugEnabled()) {
                        log.warn("Error retrieving historical currency rates", e);
                    } else {
                        if(e instanceof HttpResponseException httpException && httpException.getStatusCode() == 404) {
                            log.warn("Error retrieving historical currency rates (" + e.getMessage() + "). BTC-" + currency.getCurrencyCode() + " may not be supported by " + this);
                        } else {
                            log.warn("Error retrieving historical currency rates (" + e.getMessage() + ")");
                        }
                    }
                }
            }

            return historicalRates;
        }
    },
    COINGECKO("Coingecko", "Historical rates for the last 365 days") {
        @Override
        public List<Currency> getSupportedCurrencies() {
            return getRates().rates.entrySet().stream().filter(rate -> "fiat".equals(rate.getValue().type) && isValidISO4217Code(rate.getKey().toUpperCase(Locale.ROOT)))
                    .map(rate -> Currency.getInstance(rate.getKey().toUpperCase(Locale.ROOT))).collect(Collectors.toList());
        }

        @Override
        public Double getExchangeRate(Currency currency) {
            String currencyCode = currency.getCurrencyCode();
            OptionalDouble optRate = getRates().rates.entrySet().stream().filter(rate -> currencyCode.equalsIgnoreCase(rate.getKey())).mapToDouble(rate -> rate.getValue().value).findFirst();
            if(optRate.isPresent()) {
                return optRate.getAsDouble();
            }

            return null;
        }

        @Override
        protected Map<Currency, Double> getAllExchangeRates() {
            Map<String, Double> rates = new LinkedHashMap<>();
            getRates().rates.forEach((code, rate) -> {
                if("fiat".equals(rate.type)) {
                    rates.put(code, rate.value);
                }
            });
            return toCurrencyRates(rates);
        }

        private CoinGeckoRates getRates() {
            String url = "https://api.coingecko.com/api/v3/exchange_rates";

            if(log.isInfoEnabled()) {
                log.info("Requesting exchange rates from " + url);
            }

            HttpClientService httpClientService = AppServices.getHttpClientService();
            try {
                return httpClientService.requestJson(url, CoinGeckoRates.class, HTTP_HEADERS);
            } catch(Exception e) {
                if(log.isDebugEnabled()) {
                    log.warn("Error retrieving currency rates", e);
                } else {
                    log.warn("Error retrieving currency rates (" + e.getMessage() + ")");
                }
                return new CoinGeckoRates();
            }
        }

        @Override
        public Map<Date, Double> getHistoricalExchangeRates(Currency currency, Date start, Date end) {
            long startDate = start.getTime() / 1000;
            long endDate = end.getTime() / 1000;

            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.YEAR, -1);
            startDate = Math.max(cal.getTimeInMillis() / 1000, startDate);
            endDate = Math.max(cal.getTimeInMillis() / 1000, endDate);

            String url = "https://api.coingecko.com/api/v3/coins/bitcoin/market_chart/range?vs_currency=" + currency.getCurrencyCode() + "&from=" + startDate + "&to=" + endDate;

            if(log.isInfoEnabled()) {
                log.info("Requesting historical exchange rates from " + url);
            }

            Map<Date, Double> historicalRates = new TreeMap<>();
            HttpClientService httpClientService = AppServices.getHttpClientService();
            try {
                CoinGeckoHistoricalRates coinGeckoHistoricalRates = httpClientService.requestJson(url, CoinGeckoHistoricalRates.class, HTTP_HEADERS);
                for(List<Number> historicalRate : coinGeckoHistoricalRates.prices) {
                    Date date = new Date(historicalRate.get(0).longValue());
                    historicalRates.put(DateUtils.truncate(date, Calendar.DAY_OF_MONTH), historicalRate.get(1).doubleValue());
                }
            } catch(Exception e) {
                if(log.isDebugEnabled()) {
                    log.warn("Error retrieving historical currency rates", e);
                } else {
                    log.warn("Error retrieving historical currency rates (" + e.getMessage() + ")");
                }
            }

            return historicalRates;
        }
    },
    MEMPOOL_SPACE("mempool.space", "Historical rates from Apr 2023") {
        @Override
        public List<Currency> getSupportedCurrencies() {
            return getRates().rates.entrySet().stream().filter(price -> isValidISO4217Code(price.getKey().toUpperCase(Locale.ROOT)))
                    .map(rate -> Currency.getInstance(rate.getKey().toUpperCase(Locale.ROOT))).collect(Collectors.toList());
        }

        @Override
        public Double getExchangeRate(Currency currency) {
            String currencyCode = currency.getCurrencyCode();
            OptionalDouble optRate = getRates().rates.entrySet().stream().filter(price -> currencyCode.equalsIgnoreCase(price.getKey())).mapToDouble(Map.Entry::getValue).findFirst();
            if(optRate.isPresent()) {
                return optRate.getAsDouble();
            }

            return null;
        }

        @Override
        protected Map<Currency, Double> getAllExchangeRates() {
            return toCurrencyRates(getRates().rates);
        }

        private MempoolSpaceRates getRates() {
            String url = AppServices.isUsingProxy() ? "http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion/api/v1/prices" : "https://mempool.space/api/v1/prices";

            if(log.isInfoEnabled()) {
                log.info("Requesting exchange rates from " + url);
            }

            HttpClientService httpClientService = AppServices.getHttpClientService();
            try {
                return httpClientService.requestJson(url, MempoolSpaceRates.class, null);
            } catch(Exception e) {
                if(log.isDebugEnabled()) {
                    log.warn("Error retrieving currency rates", e);
                } else {
                    log.warn("Error retrieving currency rates (" + e.getMessage() + ")");
                }
                return new MempoolSpaceRates();
            }
        }

        @Override
        public Map<Date, Double> getHistoricalExchangeRates(Currency currency, Date start, Date end) {
            String url = AppServices.isUsingProxy() ? "http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion/api/v1/historical-price?currency=" + currency.getCurrencyCode() :
                    "https://mempool.space/api/v1/historical-price?currency=" + currency.getCurrencyCode();

            if(log.isInfoEnabled()) {
                log.info("Requesting historical exchange rates from " + url);
            }

            Map<Date, Double> historicalRates = new TreeMap<>();
            HttpClientService httpClientService = AppServices.getHttpClientService();
            try {
                MempoolSpaceHistoricalRates mempoolSpaceHistoricalRates = httpClientService.requestJson(url, MempoolSpaceHistoricalRates.class, null);
                Collections.reverse(mempoolSpaceHistoricalRates.prices); //Use "closing" rates
                for(MempoolSpaceRates historicalRate : mempoolSpaceHistoricalRates.prices) {
                    Date date = new Date(historicalRate.time * 1000);
                    if(date.after(start) && date.before(end) && historicalRate.rates.containsKey(currency.getCurrencyCode())) {
                        historicalRates.put(DateUtils.truncate(date, Calendar.DAY_OF_MONTH), historicalRate.rates.get(currency.getCurrencyCode()));
                    }
                }
            } catch(Exception e) {
                if(log.isDebugEnabled()) {
                    log.warn("Error retrieving historical currency rates", e);
                } else {
                    log.warn("Error retrieving historical currency rates (" + e.getMessage() + ")");
                }
            }

            return historicalRates;
        }
    },
    NEOXA("Neoxa", "Historical rates from Sep 2026") {
        //Neoxa quotes this chain's coin against USDC only. Any other currency is reached through the conversion
        //source, which quotes the SHA256d chain: dividing its price in one currency by its price in USD leaves the
        //rate between the two currencies, and the SHA256d chain's own price cancels out.
        @Override
        public List<Currency> getSupportedCurrencies() {
            //An empty list says the currencies could not be retrieved, and keeps the one the user chose
            List<Currency> currencies = new ArrayList<>(getConversionSource().getSupportedCurrencies());
            if(!currencies.isEmpty() && !currencies.contains(USD)) {
                currencies.addFirst(USD);
            }

            return currencies;
        }

        @Override
        public Double getExchangeRate(Currency currency) {
            Double usdRate = getTicker().ticker.lastPrice;
            if(usdRate == null || USD.equals(currency)) {
                return usdRate;
            }

            return convertFromUsd(usdRate, getConversionSource().getAllExchangeRates(), currency);
        }

        private NeoxaTicker getTicker() {
            String url = NEOXA_URL + "/api/exchange/ticker/" + NEOXA_PAIR;

            if(log.isInfoEnabled()) {
                log.info("Requesting exchange rates from " + url);
            }

            HttpClientService httpClientService = AppServices.getHttpClientService();
            try {
                return httpClientService.requestJson(url, NeoxaTicker.class, null);
            } catch(Exception e) {
                if(log.isDebugEnabled()) {
                    log.warn("Error retrieving currency rates", e);
                } else {
                    log.warn("Error retrieving currency rates (" + e.getMessage() + ")");
                }
                return new NeoxaTicker();
            }
        }

        @Override
        public Map<Date, Double> getHistoricalExchangeRates(Currency currency, Date start, Date end) {
            Map<Date, Double> usdRates = getUsdHistoricalExchangeRates(start, end);
            if(USD.equals(currency) || usdRates.isEmpty()) {
                return usdRates;
            }

            ExchangeSource conversionSource = getConversionSource();
            return convertFromUsd(usdRates, conversionSource.getHistoricalExchangeRates(currency, start, end), conversionSource.getHistoricalExchangeRates(USD, start, end));
        }

        @Override
        public HistoricalRates getHistoricalRates(Currency currency, Date start, Date end) {
            Integer splitHeight = HistoricalRates.getSplitHeight(Network.get());
            ExchangeSource conversionSource = getConversionSource();
            Map<Date, Double> btcRates = conversionSource.getHistoricalExchangeRates(currency, start, end);
            Map<Date, Double> usdRates = getUsdHistoricalExchangeRates(start, end);
            if(!USD.equals(currency) && !usdRates.isEmpty()) {
                usdRates = convertFromUsd(usdRates, btcRates, conversionSource.getHistoricalExchangeRates(USD, start, end));
            }

            return new HistoricalRates(splitHeight == null ? Collections.emptyMap() : btcRates, usdRates, splitHeight);
        }

        private Map<Date, Double> getUsdHistoricalExchangeRates(Date start, Date end) {
            //The candles endpoint takes a count rather than a range, and returns the most recent ones
            long days = ChronoUnit.DAYS.between(start.toInstant(), Instant.now()) + 2;
            String url = NEOXA_URL + "/api/exchange/candles/" + NEOXA_PAIR + "?interval=1d&limit=" + Math.max(1, Math.min(days, 10000));

            if(log.isInfoEnabled()) {
                log.info("Requesting historical exchange rates from " + url);
            }

            HttpClientService httpClientService = AppServices.getHttpClientService();
            try {
                NeoxaCandles neoxaCandles = httpClientService.requestJson(url, NeoxaCandles.class, null);
                return toDailyRates(neoxaCandles, start, end);
            } catch(Exception e) {
                if(log.isDebugEnabled()) {
                    log.warn("Error retrieving historical currency rates", e);
                } else {
                    log.warn("Error retrieving historical currency rates (" + e.getMessage() + ")");
                }
            }

            return Collections.emptyMap();
        }

        @Override
        public ExchangeSource getConversionSource() {
            return Config.get().getConversionSource();
        }
    };

    public static final ExchangeSource DEFAULT_CONVERSION_SOURCE = MEMPOOL_SPACE;
    public static final List<ExchangeSource> CONVERSION_SOURCES = List.of(COINBASE, COINGECKO, MEMPOOL_SPACE);

    private static final Currency USD = Currency.getInstance("USD");
    private static final String NEOXA_URL = "https://neoxa.exchange";
    private static final String NEOXA_PAIR = "BTCB2_USDC";

    private static final Logger log = LoggerFactory.getLogger(ExchangeSource.class);
    private static final Map<String, String> HTTP_HEADERS = Map.of("User-Agent", "Mozilla/4.0 (compatible; MSIE 9.0; Windows NT 6.1)", "Accept", "*/*");

    private final String name;
    private final String description;

    ExchangeSource(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public abstract List<Currency> getSupportedCurrencies();

    public abstract Double getExchangeRate(Currency currency);

    public abstract Map<Date, Double> getHistoricalExchangeRates(Currency currency, Date start, Date end);

    /**
     * Daily rates for valuing past transactions. Only a source that follows this chain tells a coin made before the
     * split from one made after it, so every other source values them all the same way.
     */
    public HistoricalRates getHistoricalRates(Currency currency, Date start, Date end) {
        return new HistoricalRates(Collections.emptyMap(), getHistoricalExchangeRates(currency, start, end), null);
    }

    /**
     * The price of a bitcoin in every currency this source quotes, from a single response so that all of them are
     * taken at the same moment.
     */
    protected Map<Currency, Double> getAllExchangeRates() {
        return Collections.emptyMap();
    }

    /**
     * The source used to turn a USD rate into another currency, or null where the rate is quoted directly.
     */
    public ExchangeSource getConversionSource() {
        return null;
    }

    static Map<Currency, Double> toCurrencyRates(Map<String, Double> rates) {
        Map<Currency, Double> currencyRates = new LinkedHashMap<>();
        rates.forEach((code, rate) -> {
            String upperCode = code.toUpperCase(Locale.ROOT);
            if(rate != null && isValidISO4217Code(upperCode)) {
                currencyRates.put(Currency.getInstance(upperCode), rate);
            }
        });

        return currencyRates;
    }

    static Double convertFromUsd(Double usdRate, Map<Currency, Double> btcRates, Currency currency) {
        Double btcUsd = btcRates.get(USD);
        Double btcCurrency = btcRates.get(currency);
        if(usdRate == null || btcUsd == null || btcCurrency == null || btcUsd <= 0.0 || btcCurrency <= 0.0) {
            return null;
        }

        return usdRate * btcCurrency / btcUsd;
    }

    static Map<Date, Double> convertFromUsd(Map<Date, Double> usdRates, Map<Date, Double> btcCurrencyRates, Map<Date, Double> btcUsdRates) {
        Map<Date, Double> rates = new TreeMap<>();
        usdRates.forEach((date, usdRate) -> {
            Double btcCurrency = btcCurrencyRates.get(date);
            Double btcUsd = btcUsdRates.get(date);
            //mempool.space marks a day it has no rate for with -1
            if(usdRate != null && btcCurrency != null && btcUsd != null && btcUsd > 0.0 && btcCurrency > 0.0) {
                rates.put(date, usdRate * btcCurrency / btcUsd);
            }
        });

        return rates;
    }

    static Map<Date, Double> toDailyRates(NeoxaCandles neoxaCandles, Date start, Date end) {
        Map<Date, Double> rates = new TreeMap<>();
        for(NeoxaCandle candle : neoxaCandles.candles) {
            Date date = new Date(candle.time * 1000);
            if(candle.close != null && !date.before(DateUtils.truncate(start, Calendar.DAY_OF_MONTH)) && !date.after(end)) {
                rates.put(DateUtils.truncate(date, Calendar.DAY_OF_MONTH), candle.close);
            }
        }

        return rates;
    }

    private static boolean isValidISO4217Code(String code) {
        try {
            Currency currency = Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return name;
    }

    public SVGImage getSVGImage() {
        try {
            URL url = AppServices.class.getResource("/image/exchangesource/" + name.toLowerCase(Locale.ROOT) + "-icon.svg");
            if(url != null) {
                return SVGLoader.load(url);
            }
        } catch(Exception e) {
            log.error("Could not load exchange source image for " + name);
        }

        return null;
    }

    public static class CurrenciesService extends Service<List<Currency>> {
        private final ExchangeSource exchangeSource;

        public CurrenciesService(ExchangeSource exchangeSource) {
            this.exchangeSource = exchangeSource;
        }

        @Override
        protected Task<List<Currency>> createTask() {
            return new Task<>() {
                protected List<Currency> call() {
                    return exchangeSource.getSupportedCurrencies();
                }
            };
        }
    }

    public static class RatesService extends ScheduledService<ExchangeRatesUpdatedEvent> {
        private final ExchangeSource exchangeSource;
        private final Currency selectedCurrency;

        public RatesService(ExchangeSource exchangeSource, Currency selectedCurrency) {
            this.exchangeSource = exchangeSource;
            this.selectedCurrency = selectedCurrency;
        }

        protected Task<ExchangeRatesUpdatedEvent> createTask() {
            return new Task<>() {
                protected ExchangeRatesUpdatedEvent call() {
                    Double rate = exchangeSource.getExchangeRate(selectedCurrency);
                    return new ExchangeRatesUpdatedEvent(selectedCurrency, rate);
                }
            };
        }

        public ExchangeSource getExchangeSource() {
            return exchangeSource;
        }
    }

    private static class CoinbaseRates {
        public CoinbaseData data = new CoinbaseData();
    }

    private static class CoinbaseData {
        public String currency;
        public Map<String, Double> rates = new LinkedHashMap<>();
    }

    private static class CoinGeckoRates {
        public Map<String, CoinGeckoRate> rates = new LinkedHashMap<>();
    }

    private static class CoinGeckoRate {
        public String name;
        public String unit;
        public Double value;
        public String type;
    }

    private static class CoinGeckoHistoricalRates {
        public List<List<Number>> prices = new ArrayList<>();
    }

    private static class MempoolSpaceRates {
        public long time;
        public final Map<String, Double> rates = new LinkedHashMap<>();

        // Capture all other fields that Jackson do not match other members
        @JsonAnyGetter
        public Map<String, Double> getPrices() {
            return rates;
        }

        @JsonAnySetter
        public void setPrice(String name, Double value) {
            rates.put(name, value);
        }
    }

    private static class MempoolSpaceHistoricalRates {
        public List<MempoolSpaceRates> prices = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class NeoxaTicker {
        public NeoxaTickerData ticker = new NeoxaTickerData();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class NeoxaTickerData {
        public Double lastPrice;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class NeoxaCandles {
        public List<NeoxaCandle> candles = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class NeoxaCandle {
        public long time;
        public Double close;
    }
}
