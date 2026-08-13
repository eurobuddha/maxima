import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;

import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.crypto.DeterministicRsa;
import com.eurobuddha.maxima.core.crypto.MaximaCrypto;

/**
 * PRE-FLIGHT CHECK - does the REAL reference implementation accept the carrier
 * and envelope our code produces?
 *
 * We build a complete MaxTxPoW with our own :core classes, then hand the bytes
 * to the reference org.minima classes and ask them to parse and validate it.
 * If this passes, a live send should too - and if it fails, we find out here
 * instead of staring at an unexplained ack on the network.
 *
 * Runs with BOTH our build/classes and minima.jar on the classpath.
 */
public class CarrierCheck {

    static int pass = 0, fail = 0;

    static void ok(String m) { pass++; System.out.println("  ok  " + m); }
    static void bad(String m) { fail++; System.out.println("  XX  " + m); }

    public static void main(String[] args) throws Exception {

        System.out.println("Building a full Maxima unit with OUR code...\n");

        // Deterministic identity from a fixed test seed.
        byte[] seed = "maxima-carrier-check-seed".getBytes("UTF-8");
        KeyPair kp = DeterministicRsa.derive(seed, "test");
        byte[] pubDer = kp.getPublic().getEncoded();

        System.out.println("  our public key DER : " + pubDer.length + " bytes");
        System.out.println("  our private key DER: " + kp.getPrivate().getEncoded().length + " bytes");
        System.out.println("  modulus bits       : "
                + ((RSAPrivateKey) kp.getPrivate()).getModulus().bitLength());

        // Send to ourselves - recipient key is our own public key.
        long now = 1755000000000L;
        com.eurobuddha.maxima.core.msg.MaxTxPoW unit = MaximaSender.build(
                pubDer, kp.getPrivate(), pubDer,
                "**maxima_check_connect**", "hello-from-core".getBytes("UTF-8"), now).unit;

        byte[] ourBytes = Codec.serialise(unit);
        System.out.println("  serialised MaxTxPoW: " + ourBytes.length + " bytes\n");

        System.out.println("Handing those bytes to the REFERENCE implementation...\n");

        // --- parse with the reference ---
        org.minima.system.network.maxima.message.MaxTxPoW refUnit;
        try {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(ourBytes));
            refUnit = org.minima.system.network.maxima.message.MaxTxPoW.ReadFromStream(dis);
            ok("reference MaxTxPoW.ReadFromStream parsed our bytes");
        } catch (Exception e) {
            bad("reference could not parse our MaxTxPoW: " + e);
            e.printStackTrace();
            System.exit(1);
            return;
        }

        // --- the ONLY validity check a receiver performs ---
        if (refUnit.checkValidTxPoW()) {
            ok("reference checkValidTxPoW() PASSED (customHash binds the MaximaPackage)");
        } else {
            bad("reference checkValidTxPoW() FAILED - receiver would reply WRONGHASH");
        }

        // --- would the peer push it into the blockchain pipeline? ---
        org.minima.objects.TxPoW rtx = refUnit.getTxPoW();
        boolean isTxn = rtx.isTransaction();
        boolean isBlk = rtx.isBlock();
        System.out.println("     isTransaction=" + isTxn + "  isBlock=" + isBlk);
        if (!isTxn && !isBlk) {
            ok("carrier is neither transaction nor block - NOT re-injected into the chain");
        } else {
            bad("carrier would be re-injected into the peer's blockchain pipeline!");
        }

        // --- size gate ---
        int mpSize = refSerialise(refUnit.getMaximaPackage()).length;
        System.out.println("     MaximaPackage serialised = " + mpSize + " bytes (limit 262144)");
        if (mpSize <= 262144) ok("under the TOOBIG limit"); else bad("over the TOOBIG limit");

        // --- byte-identical round trip through the reference ---
        byte[] reBytes = refSerialise(refUnit);
        if (java.util.Arrays.equals(ourBytes, reBytes)) {
            ok("reference re-serialised our unit byte-identically (" + reBytes.length + " bytes)");
        } else {
            bad("re-serialisation differs: ours=" + ourBytes.length
                    + " reference=" + (reBytes == null ? "null" : reBytes.length));
        }

        // --- decrypt + verify signature using the reference crypto ---
        try {
            org.minima.objects.base.MiniData cipher =
                    refUnit.getMaximaPackage().mData;
            org.minima.utils.encrypt.CryptoPackage cp = new org.minima.utils.encrypt.CryptoPackage();
            cp.ConvertMiniDataVersion(cipher);
            byte[] plain = cp.decrypt(kp.getPrivate().getEncoded());
            ok("reference CryptoPackage.decrypt() recovered our plaintext");

            org.minima.system.network.maxima.message.MaximaInternal mi =
                    org.minima.system.network.maxima.message.MaximaInternal
                            .ConvertMiniDataVersion(new org.minima.objects.base.MiniData(plain));

            boolean sigok = org.minima.utils.encrypt.SignVerify.verify(
                    mi.mFrom.getBytes(), mi.mData.getBytes(), mi.mSignature.getBytes());
            if (sigok) {
                ok("reference SignVerify.verify() accepted our SHA256withRSA signature");
            } else {
                bad("reference rejected our signature");
            }

            org.minima.system.network.maxima.message.MaximaMessage rmm =
                    org.minima.system.network.maxima.message.MaximaMessage
                            .ConvertMiniDataVersion(mi.mData);
            System.out.println("     decoded application: " + rmm.mApplication);
            System.out.println("     decoded data       : "
                    + new String(rmm.mData.getBytes(), "UTF-8"));
            System.out.println("     decoded timemilli  : " + rmm.mTimeMilli);

            // The receiver's from/signer bind check.
            if (rmm.mFrom.isEqual(mi.mFrom)) {
                ok("inner from == signer (passes the receiver's bind check)");
            } else {
                bad("from/signer mismatch - receiver would reply FAIL");
            }

        } catch (Exception e) {
            bad("reference decrypt/verify path threw: " + e);
            e.printStackTrace();
        }

        // --- determinism: same seed must give the same key, always ---
        KeyPair again = DeterministicRsa.derive(seed, "test");
        if (java.util.Arrays.equals(pubDer, again.getPublic().getEncoded())) {
            ok("deterministic RSA: same seed reproduced the identical public key");
        } else {
            bad("deterministic RSA is NOT stable across calls");
        }
        KeyPair other = DeterministicRsa.derive(seed, "different-context");
        if (!java.util.Arrays.equals(pubDer, other.getPublic().getEncoded())) {
            ok("a different context yields a different identity");
        } else {
            bad("context is not affecting derivation");
        }

        System.out.println("\n=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) {
            System.out.println("PRE-FLIGHT FAILED - do not send to the network yet.");
            System.exit(1);
        }
        System.out.println("The reference accepts our unit. Safe to attempt a live send.");
    }

    static byte[] refSerialise(org.minima.utils.Streamable s) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(bos);
        s.writeDataStream(dos);
        dos.flush();
        dos.close();
        return bos.toByteArray();
    }
}
