package com.sparrowwallet.sparrow.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.time.DateUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Calendar;
import java.util.Currency;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Neoxa quotes this chain's coin against USDC only, so USD is the one currency it offers.
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
    public void testOnlyUsdIsOffered() {
        Assertions.assertEquals(List.of(USD), ExchangeSource.NEOXA.getSupportedCurrencies());

        //Answered without a request, since there is nothing to ask for
        Assertions.assertNull(ExchangeSource.NEOXA.getExchangeRate(EUR));
        Assertions.assertTrue(ExchangeSource.NEOXA.getHistoricalExchangeRates(EUR, new Date(0), new Date()).isEmpty());
    }

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
        Assertions.assertEquals(1, ExchangeSource.toDailyRates(candles, end, end).size());
    }
}
