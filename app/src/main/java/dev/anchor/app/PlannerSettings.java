package dev.anchor.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Explicit local planning preferences. Percent limits flexible work within the day window. */
public final class PlannerSettings {
    public int dayStartMinute=8*60,dayEndMinute=21*60,flexiblePercent=60;
    public boolean automaticPlanning=true;
    private static final String PREFS="planner-settings";
    public static PlannerSettings load(Context context) {
        SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        PlannerSettings s=new PlannerSettings();
        s.dayStartMinute=p.getInt("day-start",s.dayStartMinute);s.dayEndMinute=p.getInt("day-end",s.dayEndMinute);
        s.flexiblePercent=p.getInt("flexible-percent",s.flexiblePercent);s.automaticPlanning=p.getBoolean("automatic-planning",s.automaticPlanning);
        try { s.validate(); } catch(IllegalArgumentException invalid) { return new PlannerSettings(); }
        return s;
    }
    public void validate() { Scheduler.validateWindow(dayStartMinute,dayEndMinute,flexiblePercent); }
    public void save(Context context) {
        validate();
        if(!context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putInt("day-start",dayStartMinute).putInt("day-end",dayEndMinute)
            .putInt("flexible-percent",flexiblePercent).putBoolean("automatic-planning",automaticPlanning).commit())
            throw new IllegalStateException("Planning settings could not be saved. Please try again.");
    }
}
