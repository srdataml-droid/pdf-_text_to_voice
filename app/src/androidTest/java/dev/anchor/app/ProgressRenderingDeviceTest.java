package dev.anchor.app;
import android.test.AndroidTestCase;
import android.graphics.*;
public class ProgressRenderingDeviceTest extends AndroidTestCase {
    public void testOneDoneOneMissedTwoPendingPaintExactShares() {
        DailyProgress.Day day=new DailyProgress.Day();day.total=4;day.completed=1;day.missed=1;day.pending=2;
        Bitmap bitmap=Bitmap.createBitmap(400,40,Bitmap.Config.ARGB_8888);
        DayProgressView.draw(new Canvas(bitmap),new RectF(0,0,400,40),day,new Paint(Paint.ANTI_ALIAS_FLAG),8);
        assertEquals(DayProgressView.GREEN,bitmap.getPixel(50,20));assertEquals(DayProgressView.RED,bitmap.getPixel(150,20));assertEquals(DayProgressView.NEUTRAL,bitmap.getPixel(300,20));bitmap.recycle();
    }
}
