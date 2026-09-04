package com.eurobuddha.maxima.app.portal.ide.terminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The node's command registry (minimaCore fork, core/minima-core) with per-parameter
 * allowed values for full-depth autocomplete. Data lines are extracted from the node source
 * (CommandRunner.ALL_COMMANDS + each command's getValidParams()/help/equals checks).
 *
 * The node REJECTS any parameter not in its getValidParams() list, so every line here must
 * match that list exactly: tools/help/gen_help.py checks this table against a dump of the
 * node jar (tools/help/node-help.json) and refuses to build help.json on any mismatch.
 *
 * Line format:  name :: param1<v1,v2> param2 param3<true,false>
 * A <...> list means suggestable values (free-form input may still be allowed).
 */
public class CommandRegistry {

    public static class Param {
        public final String name;
        public final List<String> values;
        Param(String name, List<String> values) {
            this.name = name;
            this.values = values;
        }
    }

    public static class Cmd {
        public final String name;
        public final List<Param> params;
        Cmd(String name, List<Param> params) {
            this.name = name;
            this.params = params;
        }
        public String usage() {
            if (params.isEmpty()) return name;
            StringBuilder sb = new StringBuilder(name);
            for (Param p : params) {
                sb.append("  ").append(p.name).append(":");
                if (!p.values.isEmpty()) {
                    sb.append(String.join("|", p.values));
                }
            }
            return sb.toString();
        }
    }

    // Extracted from the node source (v1.1.2.3): CommandRunner.ALL_COMMANDS +
    // each command's getValidParams()/getBooleanParam/action-equals chains.
    private static final String[] DATA = {
        "quit :: compact<true,false>",
        "status :: clean<true> debug<true,false> complete<true,false>",
        "coins :: relevant<true> sendable<true,false> coinid amount address tokenid checkmempool<true,false> order<asc,desc> coinage simplestate<true,false> totalamount depth state megammr<true,false>",
        "txpow :: txpowid block address onchain relevant<true> max action<info> inblock",
        "connect :: host",
        "disconnect :: uid<all>",
        "network :: action<list,reset,recalculateip,restart,loggingon,loggingoff>",
        "message :: uid data",
        "trace :: enable<true,false> filter network<true,false>",
        "help :: command",
        "printtree :: depth cascade<true,false>",
        "automine ::",
        "printmmr ::",
        "rpc :: enable<true,false> ssl<true,false> password action<adduser,removeuser,listusers> username mode<read,write>",
        "send :: action uid address amount multi tokenid state burn coinage split debug<true,false> dryrun<true,false> mine<true,false> password storestate<true,false> fromaddress signkey",
        "balance :: address tokenid confirmations tokendetails<true,false> megammr<true,false> simple<true,false> coinlist<true,false>",
        "tokencreate :: name amount decimals script state signtoken webvalidate burn mine<true,false> uselimits<true,false>",
        "tokenvalidate :: tokenid",
        "tokens :: tokenid action<export,import> data",
        "getaddress ::",
        "newaddress ::",
        "debugflag :: activate<true,false> var",
        "incentivecash :: uid",
        "webhooks :: enable<true,false> action<list,add,remove,clear,errorlogs> hook filter",
        "peers :: action<list,forcecheck,addpeers,publish,fetch> peerslist max file url",
        "p2pstate ::",
        "sendpoll :: action<add,list,remove> uid address amount multi tokenid state burn coinage split debug<true,false> dryrun<true,false> mine<true,false> password storestate<true,false> fromaddress signkey",
        "healthcheck ::",
        "mempool ::",
        "block ::",
        "reset :: action<chainsync,seedsync,restore> archivefile file password phrase keys keyuses",
        "whitepaper ::",
        "sendnosign :: address amount multi tokenid state burn split debug<true,false> dryrun<true,false> file",
        "sendsign :: file password",
        "sendpost :: file",
        "sendview :: file",
        "sendfrom :: fromaddress address amount tokenid script privatekey keyuses mine<true,false> burn state split",
        "createfrom :: fromaddress address amount tokenid script burn",
        "rawfrom :: inputs outputs state",
        "rawtxnfrom :: inputs outputs scripts state",
        "signfrom :: id data privatekey keyuses post<true,false>",
        "postfrom :: data mine<true,false> mmr<true,false>",
        "constructfrom :: coinlist script toaddress toamount changeaddress changeamount tokenid",
        "consolidatefrom :: fromaddress tokenid script privatekey keyuses mine<true,false> burn maxcoins",
        "createtokenfrom :: fromaddress name amount privatekey keyuses script decimals mine<true,false>",
        "archive :: action<resync,integrity,export,exportraw,import,importold,importraw,inspect,inspectraw,addresscheck> host phrase anyphrase<true,false> keys keyuses file address statecheck logs<true,false> maxexport",
        "logs :: scripts<true,false> mining<true,false> maxima<true,false> blocks<true,false> networking<true,false> ibd<true,false> peerschecker<true,false> txpowdb<true,false>",
        "history :: depth max offset action<list,size,customsize,transactions> relevant<true,false> startmilli where",
        "convert :: from<hex,mx,string,base64> to<hex,mx,string,base64> data",
        "maths :: calculate logs<true,false>",
        "restoresync :: file password host",
        "timemilli :: minutesback hoursback",
        "decryptbackup :: file password output",
        "megammrsync :: action<mydetails,resync> host phrase anyphrase<true,false> keys keyuses file password",
        "systemcheck :: processor action<list,details>",
        "scanchain :: depth offset",
        "multisig :: id action<create,getkey,list,spend,sign,post,view> root required file publickeys amount tokenid coinid address password mine<true,false>",
        "multisigread :: id action<getkey,list,spend,post,view> root required file publickeys amount tokenid coinid address password",
        "checkaddress :: address",
        "sphincs :: action<generate,sign,verify,transaction,test> seed data privatekey file publickey signature amount address tokenid mine<true,false>",
        "ping :: host",
        "random :: size type<sha2,sha3>",
        "seedrandom :: modifier",
        "mysql :: action<info,setlogin,clearlogin,integrity,update,autobackup,resync,wipe,addresscheck,h2import,h2export,size,rawexport,rawimport,reset,fixmissing,findtxpow> host database user password keys keyuses phrase address txpowid enable<true,false> file statecheck logs<true,false> maxexport readonly<true,false> startfix endfix block",
        "mysqlcoins :: action<info,wipe,autobackup,update,search> host database user password logs<true,false> readonly<true,false> query where maxblocks maxcoins hidetoken<true,false> address spent<true,false> limit enable<true,false>",
        "slavenode :: host enable<true,false>",
        "jnitest :: testnonce maxattempts targetdifficulty",
        "benchmark :: hashes testnonce maxattempts targetdifficulty",
        "jniminingtest :: maxattempts testnonce targetdifficulty",
        "megammr :: action<info,export,import,integrity> file",
        "vault :: action<seed,wipekeys,restorekeys,passwordlock,passwordunlock,testphrase,resetkeys> seed keyuses phrase password confirm numkeys",
        "consolidate :: tokenid coinage maxcoins maxsigs burn debug<true,false> dryrun<true,false> password",
        "coinnotify :: action<add,remove,check> address",
        "backup :: debug<true,false> password file auto<true,false> confirm maxhistory",
        "restore :: file password shutdown<true,false>",
        "test :: show action",
        "runscript :: script state prevstate globals signatures extrascripts",
        "tutorial ::",
        "keys :: action<list,checkkeys,new,genkey,createallkeys> publickey phrase modifier keyuses",
        "scripts :: address",
        "newscript :: script trackall<true,false> clean<true,false>",
        "removescript :: address",
        "burn ::",
        "txnbasics :: id",
        "txncreate :: id",
        "txninput :: id coinid coindata floating<true,false> address amount tokenid scriptmmr<true,false>",
        "txnlist :: id transactiononly<true,false>",
        "txnclear :: id scripts<true,false> mmr<true,false> signatures<true,false>",
        "txnview :: file data",
        "txnoutput :: id amount address tokenid storestate<true,false>",
        "txnstate :: id port value",
        "txnsign :: id publickey<auto,custom> txndelete<true,false> txnpostauto<true,false> txnpostburn txnpostmine<true,false> password privatekey keyuses",
        "txnpost :: id auto<true,false> burn mine<true,false> txndelete<true,false>",
        "txndelete :: id",
        "txnexport :: id file showtxn<true,false>",
        "txnimport :: id file data",
        "txncheck :: id",
        "txnscript :: id scripts auto<true,false>",
        "txnauto :: id amount address tokenid sign<true,false> burn mmrscript<true,false>",
        "txnaddamount :: id amount address onlychange<true,false> tokenid fromaddress burn storestate<true,false> split",
        "txnlock :: action<lock,unlock,list> timeout unlockdelay",
        "txnmmr :: id",
        "txnmine :: id data",
        "txnminepost :: data",
        "txncoinlock :: action<lock,unlock>",
        "coinimport :: data track<true,false>",
        "coinexport :: coinid",
        "cointrack :: enable<true,false> coinid",
        "coincheck :: data",
        "hash :: data type<sha2,sha3> file",
        "hashtest :: amount",
        "sign :: publickey data",
        "verify :: publickey data signature",
        "mmrcreate :: nodes",
        "mmrproof :: data proof root",
    };

    private static Map<String, Cmd> CMDS;

    private static synchronized Map<String, Cmd> cmds() {
        if (CMDS == null) {
            CMDS = new LinkedHashMap<>();
            for (String line : DATA) {
                Cmd c = parse(line);
                if (c != null) CMDS.put(c.name, c);
            }
        }
        return CMDS;
    }

    private static Cmd parse(String line) {
        String[] halves = line.split("::", 2);
        String name = halves[0].trim();
        if (name.isEmpty()) return null;
        List<Param> params = new ArrayList<>();
        if (halves.length > 1) {
            for (String tok : halves[1].trim().split("\\s+")) {
                if (tok.isEmpty()) continue;
                int lt = tok.indexOf('<');
                if (lt >= 0 && tok.endsWith(">")) {
                    String pname = tok.substring(0, lt);
                    String[] vals = tok.substring(lt + 1, tok.length() - 1).split(",");
                    List<String> values = new ArrayList<>();
                    for (String v : vals) if (!v.isEmpty()) values.add(v);
                    params.add(new Param(pname, values));
                } else {
                    params.add(new Param(tok, Collections.emptyList()));
                }
            }
        }
        return new Cmd(name, params);
    }

    public static Iterable<Cmd> all() {
        return cmds().values();
    }

    public static Cmd get(String name) {
        return cmds().get(name);
    }

    /**
     * Known node-killers over the native Binder IPC (reply >1MB kills this app process
     * uncatchably and can crash the node). Returns a warning string, or null if safe.
     */
    public static String dangerWarning(String fullCommand) {
        // Check every command in a ; chain, not just the first.
        for (String part : fullCommand.split(";")) {
            String w = dangerWarningSingle(part.trim());
            if (w != null) return w;
        }
        return null;
    }

    private static String dangerWarningSingle(String cmd) {
        if (cmd.isEmpty()) return null;
        String name = cmd.split(" ")[0];
        if (name.equals("coins")) {
            boolean bounded = cmd.contains("depth:") || cmd.contains("coinid:") || cmd.contains("relevant:true")
                    || cmd.contains("sendable:true");
            if (!bounded) {
                return "Unbounded 'coins' on a busy address can build a giant reply that crashes "
                        + "the node AND this app (Binder ~1MB limit is uncatchable). Add depth: "
                        + "(start small, e.g. depth:4) or relevant:true.";
            }
        }
        if (name.equals("history")) {
            long max = -1;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("max:(\\d+)").matcher(cmd);
            if (m.find()) {
                // \d+ can exceed Integer/Long range — parse defensively; unparseable = unbounded.
                try {
                    max = Long.parseLong(m.group(1));
                } catch (NumberFormatException e) {
                    max = Long.MAX_VALUE;
                }
            }
            if (max < 0 || max > 25) {
                return "'history' returns FULL txpow bodies (~14KB each; default max ~100) and can "
                        + "exceed the IPC limits — big replies crash the node/app. Use max:8 or "
                        + "less and page with offset:.";
            }
        }
        if (name.equals("printtree") && !cmd.contains("depth:")) {
            return "'printtree' without depth: can return a huge tree. Add depth: (e.g. depth:10).";
        }
        return null;
    }
}
