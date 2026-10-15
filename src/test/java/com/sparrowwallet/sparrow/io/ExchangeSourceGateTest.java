package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.sparrow.net.ExchangeSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

/**
 * Neoxa is the only source that quotes this chain's coin. Every other exchange source quotes the SHA256d chain, which
 * is not what this wallet holds, and using one of them overstated every fiat value by a couple of hundred times.
 * Every display site and the rates service decide which source to use by reading Config.getExchangeSource(), so this
 * pins the one place the decision is made.
 */
public class ExchangeSourceGateTest {
    private ExchangeSource storedExchangeSource;
    private ExchangeSource storedConversionSource;

    @BeforeEach
    public void saveConfig() throws ReflectiveOperationException {
        storedExchangeSource = (ExchangeSource)getField("exchangeSource").get(Config.get());
        storedConversionSource = (ExchangeSource)getField("conversionSource").get(Config.get());
    }

    @AfterEach
    public void restoreConfig() {
        Config.get().setExchangeSource(storedExchangeSource);
        Config.get().setConversionSource(storedConversionSource);
    }

    @Test
    public void testNeoxaIsUsedByDefault() {
        Config.get().setExchangeSource(null);

        Assertions.assertEquals(ExchangeSource.NEOXA, Config.get().getExchangeSource());
        Assertions.assertTrue(Config.get().isFetchRates());
    }

    @Test
    public void testAStoredSha256dSourceDoesNotReopenIt() {
        //A config carried over from before the split, or from Sparrow, must not bring the wrong rate back with it
        for(ExchangeSource stored : ExchangeSource.CONVERSION_SOURCES) {
            Config.get().setExchangeSource(stored);
            Assertions.assertEquals(ExchangeSource.NEOXA, Config.get().getExchangeSource(), stored + " was used for the rate");
        }
    }

    @Test
    public void testNoneIsKept() {
        Config.get().setExchangeSource(ExchangeSource.NONE);

        Assertions.assertEquals(ExchangeSource.NONE, Config.get().getExchangeSource());
        Assertions.assertFalse(Config.get().isFetchRates());
    }

    @Test
    public void testConversionSourceDefaultsToMempoolSpace() {
        Config.get().setConversionSource(null);
        Assertions.assertEquals(ExchangeSource.MEMPOOL_SPACE, Config.get().getConversionSource());

        //Neither of these quotes a bitcoin in more than one currency, so neither can convert
        Config.get().setConversionSource(ExchangeSource.NEOXA);
        Assertions.assertEquals(ExchangeSource.MEMPOOL_SPACE, Config.get().getConversionSource());
        Config.get().setConversionSource(ExchangeSource.NONE);
        Assertions.assertEquals(ExchangeSource.MEMPOOL_SPACE, Config.get().getConversionSource());

        Config.get().setConversionSource(ExchangeSource.COINBASE);
        Assertions.assertEquals(ExchangeSource.COINBASE, Config.get().getConversionSource());
        Assertions.assertEquals(ExchangeSource.COINBASE, ExchangeSource.NEOXA.getConversionSource());
    }

    private static Field getField(String name) throws NoSuchFieldException {
        Field field = Config.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
