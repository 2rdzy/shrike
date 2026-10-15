package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.Network;
import org.apache.commons.lang3.time.DateUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Map;

/**
 * A transaction confirmed before the split exists on both chains and takes the SHA256d chain's rate; one confirmed
 * from the split on takes this chain's. The choice is made on height, since blocks on both sides of the split were
 * mined on the same day.
 */
public class HistoricalRatesTest {
    private static final Date SPLIT_DAY = DateUtils.truncate(new GregorianCalendar(2026, Calendar.AUGUST, 8, 12, 0).getTime(), Calendar.DAY_OF_MONTH);

    private final HistoricalRates rates = new HistoricalRates(Map.of(SPLIT_DAY, 100000.0), Map.of(SPLIT_DAY, 400.0), 961632);

    @Test
    public void testSplitHeight() {
        Assertions.assertEquals(961632, HistoricalRates.getSplitHeight(Network.MAINNET));
        Assertions.assertNull(HistoricalRates.getSplitHeight(Network.TESTNET));
        Assertions.assertNull(HistoricalRates.getSplitHeight(Network.REGTEST));
    }

    @Test
    public void testSameDayIsSplitByHeight() {
        Date morning = new GregorianCalendar(2026, Calendar.AUGUST, 8, 9, 30).getTime();
        Date evening = new GregorianCalendar(2026, Calendar.AUGUST, 8, 21, 15).getTime();

        Assertions.assertEquals(100000.0, rates.getRate(961631, morning));
        Assertions.assertEquals(400.0, rates.getRate(961632, morning));
        Assertions.assertEquals(400.0, rates.getRate(961700, evening));
    }

    @Test
    public void testMissingDayHasNoRate() {
        Date later = new GregorianCalendar(2026, Calendar.AUGUST, 20, 12, 0).getTime();

        Assertions.assertNull(rates.getRate(961631, later));
        Assertions.assertNull(rates.getRate(962000, later));
        Assertions.assertNull(rates.getRate(962000, null));
    }

    @Test
    public void testUnconfirmedTakesThisChainsRate() {
        Assertions.assertEquals(400.0, rates.getRate(0, SPLIT_DAY));
        Assertions.assertEquals(400.0, rates.getRate(-1, SPLIT_DAY));
    }

    @Test
    public void testNoSplitTakesThisChainsRate() {
        HistoricalRates noSplit = new HistoricalRates(Map.of(), Map.of(SPLIT_DAY, 400.0), null);
        Assertions.assertEquals(400.0, noSplit.getRate(1000, SPLIT_DAY));
    }
}
