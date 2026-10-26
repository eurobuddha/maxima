package com.eurobuddha.maxima.app.chat;

import android.content.Context;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import com.eurobuddha.maxima.app.wallet.TokenSend;

/** The existing MINIMA/MxUSD picker, shared by both Android apps and both send screens. */
public final class PaymentCurrency extends RadioGroup {
    public final RadioButton minima,usd;
    public PaymentCurrency(Context context){
        super(context);setOrientation(HORIZONTAL);
        minima=new RadioButton(context);minima.setId(View.generateViewId());minima.setText("Minima");
        usd=new RadioButton(context);usd.setId(View.generateViewId());usd.setText(TokenSend.MXUSD_NAME);
        addView(minima);addView(usd);check(minima.getId());
    }
    public boolean isUsd(){
        int id=getCheckedRadioButtonId();
        if(id!=minima.getId()&&id!=usd.getId())throw new IllegalStateException("Select a currency");
        return id==usd.getId();
    }
    public String tokenId(){return isUsd()?TokenSend.MXUSD_TOKENID:TokenSend.MINIMA_TOKENID;}
    public String tokenName(){return isUsd()?TokenSend.MXUSD_NAME:TokenSend.MINIMA_NAME;}
}
