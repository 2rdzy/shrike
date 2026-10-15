package com.sparrowwallet.sparrow.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.time.DateUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Calendar;
import java.util.Currency;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Neoxa quotes this chain's coin in USD only. Other currencies come from the conversion source, using only the ratio
 * between its price of a bitcoin in that currency and in USD, so its price of a bitcoin cancels out.
 */
public class NeoxaExchangeSourceTest {
    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency EUR = Currency.getInstance("EUR");

    //Trimmed from https://neoxa.exchange/api/exchange/ticker/BTCB2_USDC
    private static final String TICKER = "{\"success\":true,\"pair\":\"BTCB2_USDC\",\"ticker\":{\"lastPrice\":737.02,\"change\":-39.34,\"high24h\":820.37,\"low24h\":697,\"volume24h\":434.86,\"bestBid\":737.02,\"bestAsk\":741.46}}";
    //Trimmed from https://neoxa.exchange/api/exchange/candles/BTCB2_USDC?interval=1d&limit=3
    private static final String CANDLES = "{\"success\":true,\"pair\":\"BTCB2_USDC\",\"interval\":\"1d\",\"candles\":[" +
            "{\"time\":1788220800,\"open\":13.515415,\"high\":220,\"low\":13.515415,\"close\":90,\"volume\":442.69}," +
            "{\"time\":1788307200,\"open\":87,\"high\":299.6,\"low\":80,\"close\":250.5,\"volume\":100.1}," +
            "{\"time\":1788393600,\"open\":250.5,\"high\":300,\"low\":240,\"close\":280,\"volume\":50.2}]}";

    @Test
    public void testTickerParses() throws Exception {
        ExchangeSource.NeoxaTicker ticker = new ObjectMapper().readValue(TICKER, ExchangeSource.NeoxaTicker.class);
        Assertions.assertEquals(737.02, ticker.ticker.lastPrice);
    }

    @Test
    public void testCandlesParseToDailyCloses() throws Exception {
        ExchangeSource.NeoxaCandles candles = new ObjectMapper().readValue(CANDLES, ExchangeSource.NeoxaCandles.class);
        Date start = new Date(1788220800L * 1000);
        Date end = new Date(1788393600L * 1000);

        Map<Date, Double> rates = ExchangeSource.toDailyRates(candles, start, end);
        Assertions.assertEquals(3, rates.size());
        Assertions.assertEquals(90.0, rates.get(DateUtils.truncate(start, Calendar.DAY_OF_MONTH)));
        Assertions.assertEquals(280.0, rates.get(DateUtils.truncate(end, Calendar.DAY_OF_MONTH)));

        //Candles outside the requested range are left out
        Map<Date, Double> lastDay = ExchangeSource.toDailyRates(candles, end, end);
        Assertions.assertEquals(1, lastDay.size());
    }

    @Test
    public void testConversionUsesOnlyTheRatio() {
        Map<Currency, Double> btcRates = Map.of(USD, 80000.0, EUR, 72000.0);
        Assertions.assertEquals(663.318, ExchangeSource.convertFromUsd(737.02, btcRates, EUR), 0.0001);

        //Doubling the SHA256d chain's price in every currency changes nothing
        Map<Currency, Double> doubled = Map.of(USD, 160000.0, EUR, 144000.0);
        Assertions.assertEquals(ExchangeSource.convertFromUsd(737.02, btcRates, EUR), ExchangeSource.convertFromUsd(737.02, doubled, EUR), 0.0001);
    }

    @Test
    public void testConversionNeedsBothCurrencies() {
        Assertions.assertNull(ExchangeSource.convertFromUsd(737.02, Map.of(EUR, 72000.0), EUR));
        Assertions.assertNull(ExchangeSource.convertFromUsd(737.02, Map.of(USD, 80000.0), EUR));
        Assertions.assertNull(ExchangeSource.convertFromUsd(737.02, Map.of(USD, 0.0, EUR, 72000.0), EUR));
        Assertions.assertNull(ExchangeSource.convertFromUsd(null, Map.of(USD, 80000.0, EUR, 72000.0), EUR));
        Assertions.assertNull(ExchangeSource.convertFromUsd(737.02, Map.of(USD, 80000.0, EUR, -1.0), EUR));
    }

    @Test
    public void testHistoricalConversionSkipsDaysWithoutBothRates() {
        Date day1 = new Date(1788220800L * 1000);
        Date day2 = new Date(1788307200L * 1000);
        Map<Date, Double> usdRates = new LinkedHashMap<>();
        usdRates.put(day1, 100.0);
        usdRates.put(day2, 200.0);

        Map<Date, Double> converted = ExchangeSource.convertFromUsd(usdRates, Map.of(day1, 90000.0, day2, 45000.0), Map.of(day1, 100000.0));
        Assertions.assertEquals(1, converted.size());
        Assertions.assertEquals(90.0, converted.get(day1), 0.0001);

        //mempool.space marks a day it has no rate for with -1
        Map<Date, Double> unknown = ExchangeSource.convertFromUsd(usdRates, Map.of(day1, -1.0, day2, -1.0), Map.of(day1, 100000.0, day2, 100000.0));
        Assertions.assertTrue(unknown.isEmpty());
    }

    @Test
    public void testCurrencyCodesAreValidated() {
        Map<String, Double> rates = new LinkedHashMap<>();
        rates.put("usd", 80000.0);
        rates.put("EUR", 72000.0);
        rates.put("xyz", 1.0);
        rates.put("time", 1788220800.0);

        Map<Currency, Double> currencyRates = ExchangeSource.toCurrencyRates(rates);
        Assertions.assertEquals(Map.of(USD, 80000.0, EUR, 72000.0), currencyRates);
    }
}
