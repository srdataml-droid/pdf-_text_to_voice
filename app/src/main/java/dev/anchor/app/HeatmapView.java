package dev.anchor.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.View;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;

/** Task shares on each planned day: green completed, red overdue, neutral not yet due. */
public final class HeatmapView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private Map<String,Integer> counts = Collections.emptyMap();
    private Map<String,DailyProgress.Day> outcomes=Collections.emptyMap();
    private LocalDate today = LocalDate.now();
    private LocalDate focusedDay = today;
    private LocalDate start = today.minusWeeks(12).with(java.time.DayOfWeek.MONDAY);
    private LocalDate windowStart = start, windowEnd = today;
    private Consumer<LocalDate> selected;
    private float cell, gap, left, top;
    private int weeks;

    public HeatmapView(Context context) {
        super(context);
        paint.setTypeface(context.getResources().getFont(R.font.anchor_sans));
        setMinimumHeight(dp(172));
        setFocusable(true);
        setClickable(true);
    }

    public void setData(Map<String,Integer> values, LocalDate date, Consumer<LocalDate> listener) {
        setChallengeData(values,date.minusDays(59),date,listener);
    }

    public void setChallengeData(Map<String,Integer> values, LocalDate challengeStart, LocalDate date, Consumer<LocalDate> listener) {
        outcomes=Collections.emptyMap();
        counts = values;
        today = date;
        focusedDay = date;
        windowStart = challengeStart; windowEnd = challengeStart.plusDays(59);
        start = challengeStart.with(java.time.DayOfWeek.MONDAY);
        weeks = (int)(java.time.temporal.ChronoUnit.DAYS.between(start, windowEnd) / 7) + 1;
        selected = listener;
        int total = 0, active = 0;
        for (int count : values.values()) { total += count; if (count > 0) active++; }
        setContentDescription("Completion heatmap, " + total + " completed tasks on " + active
                + " active days in the 60-day challenge. Activate to open a day, or use arrow keys to choose a day. Tap a square to open that day.");
        focusedDay = date.isBefore(windowStart) ? windowStart : date.isAfter(windowEnd) ? windowEnd : date;
        invalidate();
    }
    public void setPlanData(Map<String,DailyProgress.Day> values,LocalDate challengeStart,LocalDate date,Consumer<LocalDate> listener) {
        setChallengeData(Collections.emptyMap(),challengeStart,date,listener);outcomes=values;
        setContentDescription("60-day task progress. Green completed, red missed, grey not yet due. Each task fills one share of its planned day. Tap a day or use arrow keys to inspect it.");invalidate();
    }
    private String dayDescription(){DailyProgress.Day d=outcomes.get(focusedDay.toString());return focusedDay+", "+(d==null?counts.getOrDefault(focusedDay.toString(),0)+" completed tasks":d.description());}

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        float size = Math.min(dp(21), (width - dp(32)) / 13f - dp(3));
        int desired = Math.round(dp(34) + (size + dp(3)) * 7);
        setMeasuredDimension(width, resolveSize(desired, heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (weeks == 0) return;
        left = dp(26); top = dp(25); gap = dp(3);
        cell = Math.min(dp(21), (getWidth() - left - gap * (weeks - 1)) / weeks);
        paint.setTextSize(dp(10));
        paint.setColor(0xff68728c);
        canvas.drawText("M", 0, top + cell * .8f, paint);
        canvas.drawText("W", 0, top + (cell + gap) * 2 + cell * .8f, paint);
        canvas.drawText("F", 0, top + (cell + gap) * 4 + cell * .8f, paint);
        int lastMonth = -1;
        for (int w = 0; w < weeks; w++) {
            LocalDate week = start.plusWeeks(w);
            float x = left + w * (cell + gap);
            if (week.getMonthValue() != lastMonth) {
                canvas.drawText(week.format(DateTimeFormatter.ofPattern("MMM")), x, dp(13), paint);
                lastMonth = week.getMonthValue();
            }
            for (int d = 0; d < 7; d++) {
                LocalDate day = week.plusDays(d);
                int n = counts.getOrDefault(day.toString(), 0);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(day.isBefore(windowStart) || day.isAfter(windowEnd) || day.isAfter(today) ? 0xfff7f8fb : n == 0 ? 0xffe9edf2
                        : n == 1 ? 0xffbbdcd5 : n == 2 ? 0xff79b8aa : n < 5 ? 0xff408f7f : 0xff25766c);
                rect.set(x, top + d * (cell + gap), x + cell, top + d * (cell + gap) + cell);
                canvas.drawRoundRect(rect, dp(3), dp(3), paint);
                if(!day.isBefore(windowStart)&&!day.isAfter(windowEnd)&&outcomes.containsKey(day.toString()))
                    DayProgressView.draw(canvas,rect,outcomes.get(day.toString()),paint,dp(3));
                if (day.equals(today)) {
                    paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(0xff4d54b8);
                    canvas.drawRoundRect(rect, dp(3), dp(3), paint);
                    paint.setStyle(Paint.Style.FILL);
                }
            }
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            int week = (int)((event.getX() - left) / (cell + gap));
            int day = (int)((event.getY() - top) / (cell + gap));
            if (event.getX() >= left && event.getY() >= top && week >= 0 && week < weeks && day >= 0 && day < 7) {
                LocalDate date = start.plusWeeks(week).plusDays(day);
                if (!date.isBefore(windowStart) && !date.isAfter(windowEnd) && selected != null) {
                    focusedDay = date;
                    performClick();
                }
            }
            return true;
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        announceForAccessibility(dayDescription());
        if (selected != null) selected.accept(focusedDay);
        return true;
    }
    @Override public boolean onKeyDown(int key, KeyEvent event) {
        LocalDate next = focusedDay;
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) next = next.minusWeeks(1);
        else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) next = next.plusWeeks(1);
        else if (key == KeyEvent.KEYCODE_DPAD_UP) next = next.minusDays(1);
        else if (key == KeyEvent.KEYCODE_DPAD_DOWN) next = next.plusDays(1);
        else if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) return performClick();
        else return super.onKeyDown(key, event);
        if (!next.isBefore(windowStart) && !next.isAfter(windowEnd)) focusedDay = next;
        announceForAccessibility(dayDescription());
        return true;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
