package dev.anchor.app;
import android.content.Context;
import android.graphics.*;
import android.view.View;

/** One equal share per planned task; no colour is inferred from raw completion counts. */
final class DayProgressView extends View {
    static final int GREEN=0xff25766c,RED=0xffc65b61,NEUTRAL=0xffe9edf2;
    private final DailyProgress.Day day;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    DayProgressView(Context c,DailyProgress.Day day){super(c);this.day=day;setContentDescription(day.description());setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);}
    static void draw(Canvas canvas,RectF rect,DailyProgress.Day day,Paint paint,float radius) {
        paint.setStyle(Paint.Style.FILL);paint.setColor(NEUTRAL);canvas.drawRoundRect(rect,radius,radius,paint);
        if(day==null||day.total==0)return;
        Path clip=new Path();clip.addRoundRect(rect,radius,radius,Path.Direction.CW);canvas.save();canvas.clipPath(clip);
        float green=rect.left+rect.width()*day.greenFraction(),red=green+rect.width()*day.redFraction();
        paint.setColor(GREEN);canvas.drawRect(rect.left,rect.top,green,rect.bottom,paint);
        paint.setColor(RED);canvas.drawRect(green,rect.top,red,rect.bottom,paint);canvas.restore();
    }
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);draw(canvas,new RectF(0,0,getWidth(),getHeight()),day,paint,getHeight()/2f);}
}
