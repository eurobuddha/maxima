package org.minima.system.brains;

import java.util.ArrayList;

import org.minima.objects.Coin;
import org.minima.objects.Transaction;
import org.minima.objects.base.MiniData;

/**
 * M0 TRIM: the wallet does NOT do Proof-of-Work or block assembly — that stays node-side.
 * The full node's TxPoWGenerator is deliberately NOT bundled. This slim stand-in carries ONLY
 * {@link #precomputeTransactionCoinID(Transaction)}, copied verbatim from minima-core's
 * TxPoWGenerator, because {@code TxnRow.toJSON()} (bundled verbatim) calls it to fill the
 * deterministic output CoinIDs. The method is pure (Coin/Transaction/MiniData only) and pulls in
 * no node DB / network / SQL. It will also be reused by the M1 transaction factory.
 */
public class TxPoWGenerator {

	public static void precomputeTransactionCoinID(Transaction zTransaction) {

		//Get the inputs..
		ArrayList<Coin> inputs = zTransaction.getAllInputs();

		//Are there any..
		if(inputs.size() == 0) {
			return;
		}

		//Get the first coin..
		Coin firstcoin = inputs.get(0);

		//Is it an ELTOO input
		boolean eltoo = false;
		if(firstcoin.getCoinID().isEqual(Coin.COINID_ELTOO)) {
			eltoo = true;
		}

		//The base modifier
		MiniData basecoinid = firstcoin.getCoinID();

		//Now cycle..
		ArrayList<Coin> outputs = zTransaction.getAllOutputs();
		int num=0;
		for(Coin output : outputs) {

			//Calculate the CoinID..
			if(eltoo) {

				//Normal
				output.resetCoinID(Coin.COINID_OUTPUT);

			}else {

				//The CoinID
				MiniData coinid = zTransaction.calculateCoinID(basecoinid, num);
				output.resetCoinID(coinid);
			}

			num++;
		}
	}
}
