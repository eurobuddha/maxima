package com.eurobuddha.maxima.core.codec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Fixed-point number: {@code int8 scale | int8 unscaledLen | unscaled BigInteger two's-complement BE}.
 *
 * Used for the {@code timeMilli} field of every Maxima message and for list
 * lengths in the MLS packets.
 */
public final class MiniNumber implements Streamable {

    /** Reference: MiniNumber.MAX_DIGITS */
    public static final int MAX_DIGITS = 64;

    /** Reference: MiniNumber.MAX_DECIMAL_PLACES */
    public static final int MAX_DECIMAL_PLACES = MAX_DIGITS - 20;

    public static final MathContext MATH_CONTEXT = new MathContext(MAX_DIGITS, RoundingMode.DOWN);

    /** Reference caps values at +/- (2^64 - 1). */
    public static final BigDecimal MAX_MININUMBER = new BigDecimal(2).pow(64).subtract(BigDecimal.ONE);
    public static final BigDecimal MIN_MININUMBER = MAX_MININUMBER.negate();

    private BigDecimal mNumber;

    public MiniNumber() {
        this(0L);
    }

    public MiniNumber(long zNumber) {
        mNumber = new BigDecimal(zNumber, MATH_CONTEXT);
        checkLimits();
    }

    public MiniNumber(BigInteger zNumber) {
        mNumber = new BigDecimal(zNumber, MATH_CONTEXT);
        checkLimits();
    }

    public MiniNumber(BigDecimal zNumber) {
        mNumber = new BigDecimal(zNumber.toPlainString(), MATH_CONTEXT);
        checkLimits();
    }

    public MiniNumber(String zNumber) {
        mNumber = new BigDecimal(zNumber, MATH_CONTEXT);
        checkLimits();
    }

    private void checkLimits() {
        if (mNumber.scale() > MAX_DECIMAL_PLACES) {
            mNumber = mNumber.setScale(MAX_DECIMAL_PLACES, RoundingMode.DOWN);
        }
        if (mNumber.compareTo(MAX_MININUMBER) > 0) {
            throw new NumberFormatException("MiniNumber too large - outside allowed range 2^64: " + mNumber);
        }
        if (mNumber.compareTo(MIN_MININUMBER) < 0) {
            throw new NumberFormatException("MiniNumber too small - outside allowed range -(2^64)");
        }
    }

    public BigDecimal getAsBigDecimal() {
        return mNumber;
    }

    public long getAsLong() {
        return mNumber.longValue();
    }

    public int getAsInt() {
        return mNumber.intValue();
    }

    @Override
    public void writeDataStream(DataOutputStream zOut) throws IOException {
        zOut.writeByte(mNumber.scale());
        byte[] data = mNumber.unscaledValue().toByteArray();
        zOut.writeByte(data.length);
        zOut.write(data);
    }

    @Override
    public void readDataStream(DataInputStream zIn) throws IOException {
        int scale = zIn.readByte();
        int len = zIn.readByte();
        if (len > 32 || len < 0) {
            throw new IOException("Error reading MiniNumber - input too large or negative: " + len);
        }
        byte[] data = new byte[len];
        zIn.readFully(data);
        mNumber = new BigDecimal(new BigInteger(data), scale, MATH_CONTEXT);
    }

    public static MiniNumber readFromStream(DataInputStream zIn) throws IOException {
        MiniNumber n = new MiniNumber();
        n.readDataStream(zIn);
        return n;
    }

    /** Reference writes bare list lengths as a MiniNumber (MiniNumber.WriteToStream). */
    public static void writeToStream(DataOutputStream zOut, int zValue) throws IOException {
        new MiniNumber(zValue).writeDataStream(zOut);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MiniNumber)) return false;
        return mNumber.compareTo(((MiniNumber) o).mNumber) == 0;
    }

    @Override
    public int hashCode() {
        return mNumber.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return mNumber.toPlainString();
    }
}
