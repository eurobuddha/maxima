package com.eurobuddha.maxima.app.portal.wallet;

/** A local record of a transaction this wallet posted. Persisted in {@link HistoryDb}. */
public class HistoryRow {
    public long   id;
    public String internalid;
    public String txnid;       // real txpowid once known, else internal uw_ id
    public String status;      // posting / posted / error
    public String recipient;
    public String amount;
    public String tokenid;
    public String tokenName;
    public long   ts;
    public String note;
    public String inputs;      // JSON array [{coinid,address,amount}]
    public String outputs;     // JSON array [{address,amount}]
    public String changeaddr;
    public String burn;
}
