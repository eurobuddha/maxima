package com.eurobuddha.maxima.app.portal.ide.ide;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** KISS VM language tables (keywords, functions, globals) for highlighting + lint. */
public class KissVm {

    public static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "LET", "IF", "THEN", "ELSEIF", "ELSE", "ENDIF",
            "WHILE", "DO", "ENDWHILE",
            "EXEC", "MAST", "ASSERT", "RETURN",
            "TRUE", "FALSE",
            "AND", "OR", "XOR", "NAND", "NOR", "NXOR", "NOT", "NEG",
            "EQ", "NEQ", "GT", "GTE", "LT", "LTE"
    ));

    public static final Set<String> FUNCTIONS = new HashSet<>(Arrays.asList(
            // general
            "CONCAT", "LEN", "REV", "SUBSET", "GET", "EXISTS", "ADDRESS", "REPLACE", "SUBSTR",
            "OVERWRITE", "SETLEN",
            // cast
            "BOOL", "HEX", "NUMBER", "STRING", "UTF8", "ASCII",
            // number
            "ABS", "CEIL", "FLOOR", "MIN", "MAX", "INC", "DEC", "SIGDIG", "POW", "SQRT",
            // hex/bits
            "BITSET", "BITGET", "BITCOUNT",
            // sha / crypto
            "SHA2", "SHA3", "PROOF", "CHAINSHA",
            // sigs
            "SIGNEDBY", "MULTISIG", "CHECKSIG",
            // state
            "STATE", "PREVSTATE", "SAMESTATE",
            // txn
            "GETOUTADDR", "GETOUTAMT", "GETOUTTOK", "GETOUTKEEPSTATE",
            "GETINADDR", "GETINAMT", "GETINTOK", "GETINID",
            "VERIFYIN", "VERIFYOUT", "SUMINPUTS", "SUMOUTPUTS",
            "FUNCTION"
    ));

    public static final Set<String> GLOBALS = new HashSet<>(Arrays.asList(
            "@BLOCK", "@BLKNUM", "@BLOCKMILLI", "@CREATED", "@COINAGE",
            "@INPUT", "@AMOUNT", "@ADDRESS", "@TOKENID", "@COINID",
            "@SCRIPT", "@TOTIN", "@TOTOUT", "@FLOATING"
    ));

    /** The proven practical on-chain script size limit (chars) — beyond it scripts silently fail. */
    public static final int MAX_SAFE_CHARS = 1200;
}
