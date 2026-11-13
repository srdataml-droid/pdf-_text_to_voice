package dev.anchor.app;

/** Pure occurrence policy shared by alarm delivery and native tests. */
final class AlarmRules {
    private AlarmRules() {}
    static boolean acceptOccurrence(long currentVersion,long currentDue,long currentToken,String state,
            long version,long due,long token,long now) {
        return "pending".equals(state) && currentVersion==version && currentDue==due && currentToken==token
            && due>0 && token>0 && now>=due;
    }
    static long snoozeAt(long now,int minutes) {
        if(minutes!=5 && minutes!=10) throw new IllegalArgumentException("Choose a 5 or 10 minute snooze.");
        return Math.addExact(now,minutes*60_000L);
    }
    static boolean mayRing(long due,long now) { return due>0 && now>=due && now-due<=120_000L; }
}
