package com.eurobuddha.maxima.app.chat;
import android.app.Application;
import com.eurobuddha.maxima.app.wallet.TokenSend;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE,application=Application.class)
public class PaymentCurrencyTest {
    @Test public void selectedRadioAndPaymentTokenAlwaysAgree(){
        PaymentCurrency choice=new PaymentCurrency(RuntimeEnvironment.getApplication());
        assertTrue(choice.minima.isChecked());assertFalse(choice.usd.isChecked());assertEquals(TokenSend.MINIMA_TOKENID,choice.tokenId());
        for(int i=0;i<5;i++){
            choice.usd.performClick();assertFalse(choice.minima.isChecked());assertTrue(choice.usd.isChecked());
            assertEquals(choice.usd.getId(),choice.getCheckedRadioButtonId());assertEquals(TokenSend.MXUSD_TOKENID,choice.tokenId());assertEquals(TokenSend.MXUSD_NAME,choice.tokenName());
            choice.minima.performClick();assertTrue(choice.minima.isChecked());assertFalse(choice.usd.isChecked());assertEquals(TokenSend.MINIMA_TOKENID,choice.tokenId());assertEquals(TokenSend.MINIMA_NAME,choice.tokenName());
        }
    }
    @Test(expected=IllegalStateException.class) public void missingSelectionCannotDefaultToWrongCurrency(){PaymentCurrency c=new PaymentCurrency(RuntimeEnvironment.getApplication());c.clearCheck();c.tokenId();}
}
