package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.Network;
import org.apache.commons.lang3.time.DateUtils;

import java.util.Calendar;
import java.util.Date;
import java.util.Map;

/**
 * Daily rates for valuing past transactions, split at the block where this chain left the SHA256d one.
 *
 * A transaction confirmed before that block exists on both chains, and at the time it was worth what a bitcoin was,
 * so it takes the SHA256d chain's rate. A transaction confirmed from that block on only exists on this chain, and
 * takes this chain's own rate. Splitting on height rather than date keeps the day of the split right, since blocks
 * on both sides of it were mined that day.
 */
public class HistoricalRates {
    //The first block that Knots follows and the SHA256d chain does not
    private static final int MAINNET_SPLIT_HEIGHT = 961632;

    private final Map<Date, Double> preSplitRates;
    private final Map<Date, Double> postSplitRates;
    private final Integer splitHeight;

    public HistoricalRates(Map<Date, Double> preSplitRates, Map<Date, Double> postSplitRates, Integer splitHeight) {
        this.preSplitRates = preSplitRates;
        this.postSplitRates = postSplitRates;
        this.splitHeight = splitHeight;
    }

    /**
     * The height of the first block not shared with the SHA256d chain, or null where this network has no such split.
     */
    public static Integer getSplitHeight(Network network) {
        return network == Network.MAINNET ? MAINNET_SPLIT_HEIGHT : null;
    }

    /**
     * The rate on the day a transaction confirmed, or null where it has no date or no rate is known for that day.
     */
    public Double getRate(Integer height, Date date) {
        if(date == null) {
            return null;
        }

        Date day = DateUtils.truncate(date, Calendar.DAY_OF_MONTH);
        boolean preSplit = splitHeight != null && height != null && height > 0 && height < splitHeight;
        return preSplit ? preSplitRates.get(day) : postSplitRates.get(day);
    }
}
