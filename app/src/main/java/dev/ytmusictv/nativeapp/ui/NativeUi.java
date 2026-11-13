package dev.ytmusictv.nativeapp.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

final class NativeUi {
    static final int BG=Color.rgb(17,18,23), CARD=Color.rgb(31,33,40),
            INK=Color.rgb(242,238,230), MUTED=Color.rgb(163,160,156), GOLD=Color.rgb(203,183,146);
    private NativeUi() {}
    static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable fill(Context c,int color,boolean border){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,9));
        if(border)d.setStroke(dp(c,2),GOLD);return d;
    }
    static TextView text(Context c,String value,int sp,int color){
        TextView v=new TextView(c);v.setText(value);v.setTextSize(sp);v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);return v;
    }
    static Button button(Context c,String label,Runnable action){
        Button b=new Button(c);b.setId(View.generateViewId());b.setText(label);b.setAllCaps(false);b.setTextSize(14);
        b.setTextColor(INK);b.setMinWidth(dp(c,66));b.setMinimumWidth(dp(c,66));
        b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(c,14),0,dp(c,14),0);
        b.setStateListAnimator(null);b.setDefaultFocusHighlightEnabled(false);
        b.setBackground(fill(c,CARD,false));b.setFocusable(true);
        b.setOnFocusChangeListener((v,f)->{b.setBackground(fill(c,f?GOLD:CARD,f));b.setTextColor(f?BG:INK);});
        b.setOnClickListener(v->action.run());return b;
    }
    static void heading(TextView v){v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);}
    static String time(long ms){
        long seconds=Math.max(0,ms)/1000;return String.format(java.util.Locale.ROOT,"%d:%02d",seconds/60,seconds%60);
    }
}
