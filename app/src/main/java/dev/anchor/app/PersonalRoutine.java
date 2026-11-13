package dev.anchor.app;
import java.util.*;
/** User-specified daily durations, 13 November 2026. Placement remains reviewed and editable. */
public final class PersonalRoutine {
    private PersonalRoutine() {}
    public static List<Routine> items() {
        List<Routine> out=new ArrayList<>();
        out.add(item("Morning chores","daily",30));
        out.add(item("Personal development · Babylon · daytime","challenge",30));
        out.add(item("Worthkind · hackathon and research","challenge",166));
        out.add(item("Personal projects","challenge",60));
        out.add(item("Cash flow · calls, outreach and contracts","challenge",150));
        out.add(item("Rest","daily",40));
        out.add(item("Podcasts and videos","challenge",60));
        out.add(item("Manga","challenge",60));
        out.add(item("Gaming","challenge",60));
        out.add(item("Anime","challenge",60));
        out.add(item("Exercise","daily",60));
        out.add(item("Personal development · Babylon · nighttime","challenge",30));
        out.add(item("Food and meals","daily",60));
        out.add(item("Social content creation and promotion","challenge",60));
        out.add(item("Mentor search and outreach","challenge",60));
        out.add(item("AI engineering · course and practice","challenge",150));
        out.add(item("Prayer and Bible reading","daily",60));
        out.add(item("Rotating study","challenge",30));
        return out;
    }
    public static String subjectFor(java.time.LocalDate date) {String[] subjects={"History","Law","Economics","Culture","Health"};long day=java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.of(2026,11,13),date);return subjects[(int)Math.floorMod(Math.floorDiv(day,2),5)];}
    private static Routine item(String title,String category,int minutes) {Routine r=new Routine();r.title=title;r.category=category;r.durationMinutes=minutes;r.weekdays=127;r.reminderMinutes=0;return r;}
}
