package com.eurobuddha.maxima.app.portal.wallet;

import com.eurobuddha.maxima.app.i18n.Language;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TxnProgressLanguageTest {
    @Test public void translatedProgressDoesNotChangeCommandsOrPostEarly() {
        WalletActivity activity = mock(WalletActivity.class);
        NodeApi node = mock(NodeApi.class);
        when(activity.node()).thenReturn(node);
        List<String> commands = new ArrayList<>();
        List<NodeApi.Cb> callbacks = new ArrayList<>();
        doAnswer(call -> {
            commands.add(call.getArgument(0)); callbacks.add(call.getArgument(1)); return null;
        }).when(node).cmd(anyString(), any(NodeApi.Cb.class));
        Coin first = new Coin(); first.coinid = "0x1122";
        Coin second = new Coin(); second.coinid = "0x3344";
        TxnBuilder.Done done = mock(TxnBuilder.Done.class);
        List<CharSequence> labels = new ArrayList<>();
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ru"));
            new TxnBuilder(activity, Arrays.asList(first, second),
                    Arrays.asList(new TxnBuilder.Out("0xAABB", "1.234567890123456789")),
                    "0x00", "0.000000000000000001", done).onProgress(labels::add).run();
            for (int index = 0; index < 4; index++) callbacks.get(index).onResult(mock(JSONObject.class));
            assertEquals(5, labels.size());
            for (CharSequence label : labels) assertTrue(label instanceof Language.Message);
            List<String> normalized = new ArrayList<>();
            for (String command : commands) normalized.add(command.replaceAll("uw_[0-9]+_[0-9a-f]+", "TX"));
            assertEquals(Arrays.asList("txncreate id:TX", "txninput id:TX coinid:0x1122",
                    "txninput id:TX coinid:0x3344", "txnoutput id:TX amount:1.234567890123456789 address:0xAABB",
                    "txnsign id:TX publickey:auto txnpostauto:true txndelete:true txnpostburn:0.000000000000000001"), normalized);
            verifyNoInteractions(done);
        } finally { Locale.setDefault(old); }
    }

    @Test public void failureRetainsRawErrorAndCleansUpTransaction() {
        WalletActivity activity = mock(WalletActivity.class);
        NodeApi node = mock(NodeApi.class);
        when(activity.node()).thenReturn(node);
        List<String> commands = new ArrayList<>();
        List<NodeApi.Cb> callbacks = new ArrayList<>();
        doAnswer(call -> {
            commands.add(call.getArgument(0)); callbacks.add(call.getArgument(1)); return null;
        }).when(node).cmd(anyString(), nullable(NodeApi.Cb.class));
        TxnBuilder.Done done = mock(TxnBuilder.Done.class);
        new TxnBuilder(activity, new ArrayList<>(), new ArrayList<>(), "0x00", done).run();
        callbacks.get(0).onError("NOT_ENABLED 中文 Русский {count}");
        assertEquals(2, commands.size());
        assertEquals(commands.get(0).replace("txncreate", "txndelete"), commands.get(1));
        verify(done).onFailed("NOT_ENABLED 中文 Русский {count}");
        verifyNoMoreInteractions(done);
    }
}
