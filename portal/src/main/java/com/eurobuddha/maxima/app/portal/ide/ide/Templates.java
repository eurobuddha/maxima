package com.eurobuddha.maxima.app.portal.ide.ide;

/** Starter script templates — the proven on-chain patterns. */
public class Templates {

    public static final String[][] ALL = {

        {"Blank", ""},

        {"Signature lock",
         "/* Standard wallet lock: spendable only by the key holder */\n"
       + "RETURN SIGNEDBY(0xYOURPUBLICKEY)"},

        {"Timelock refund (@COINAGE)",
         "/* Receiver can spend with their key at any time.\n"
       + "   Sender can reclaim after 'timeout' blocks (stored in state 1).\n"
       + "   NB use @COINAGE, NOT @BLKNUM (broken on synced nodes) */\n"
       + "LET rpk = PREVSTATE(0)\n"
       + "LET spk = PREVSTATE(2)\n"
       + "IF SIGNEDBY(rpk) THEN RETURN TRUE ENDIF\n"
       + "LET timeout = PREVSTATE(1)\n"
       + "RETURN @COINAGE GT timeout AND SIGNEDBY(spk)"},

        {"Hashlock (HTLC leg)",
         "/* Spendable by receiver with the secret preimage (state 13 of the spend txn),\n"
       + "   or refunded to the owner after the timeout */\n"
       + "LET rpk = PREVSTATE(0)\n"
       + "LET hash = PREVSTATE(1)\n"
       + "LET opk = PREVSTATE(2)\n"
       + "LET timeout = PREVSTATE(3)\n"
       + "IF SIGNEDBY(rpk) AND SHA3(STATE(13)) EQ hash THEN RETURN TRUE ENDIF\n"
       + "RETURN @COINAGE GT timeout AND SIGNEDBY(opk)"},

        {"Multisig 2-of-3",
         "/* Any 2 of the 3 listed keys must sign */\n"
       + "RETURN MULTISIG(2 0xKEY1 0xKEY2 0xKEY3)"},

        {"Commit-reveal random",
         "/* Provably-fair randomness from two secrets committed in advance:\n"
       + "   result = NUMBER(first 4 bytes of SHA3(secret1+secret2)) % range.\n"
       + "   NB NUMBER() overflows on a full hash — SUBSET first. */\n"
       + "LET c1 = PREVSTATE(0)\n"
       + "LET c2 = PREVSTATE(1)\n"
       + "LET rng = PREVSTATE(2)\n"
       + "LET s1 = STATE(10)\n"
       + "LET s2 = STATE(11)\n"
       + "ASSERT SHA3(s1) EQ c1\n"
       + "ASSERT SHA3(s2) EQ c2\n"
       + "LET h = SHA3(CONCAT(s1 s2))\n"
       + "LET r = NUMBER(SUBSET(0 4 h)) % rng\n"
       + "RETURN TRUE"},

        {"Phase-transition covenant",
         "/* Multi-phase pattern: the coin recreates itself at the SAME address with\n"
       + "   updated state (VERIFYOUT ... TRUE + storestate:true on the output),\n"
       + "   until a final payout path pays out with storestate:false. */\n"
       + "LET hpk = PREVSTATE(0)\n"
       + "LET ph = PREVSTATE(6)\n"
       + "IF ph EQ 0 AND SIGNEDBY(hpk) THEN RETURN TRUE ENDIF\n"
       + "IF ph EQ 0 THEN\n"
       + "  ASSERT SAMESTATE(0 5)\n"
       + "  ASSERT STATE(6) EQ 1\n"
       + "  ASSERT VERIFYOUT(@INPUT @ADDRESS @AMOUNT @TOKENID TRUE)\n"
       + "  RETURN TRUE\n"
       + "ENDIF\n"
       + "RETURN FALSE"},
    };

    public static String[] names() {
        String[] names = new String[ALL.length];
        for (int i = 0; i < ALL.length; i++) names[i] = ALL[i][0];
        return names;
    }
}
