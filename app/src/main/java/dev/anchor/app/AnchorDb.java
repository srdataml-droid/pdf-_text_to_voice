package dev.anchor.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** App-private SQLite storage. Deliberately has no sync or calendar-provider access. */
public class AnchorDb extends SQLiteOpenHelper {
    public AnchorDb(Context c) { this(c,"anchor.db"); }
    AnchorDb(Context c,String name) { super(c.getApplicationContext(),name,null,6); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tasks (id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,date TEXT NOT NULL,time TEXT NOT NULL DEFAULT '',notes TEXT NOT NULL DEFAULT '',duration INTEGER NOT NULL,reminder INTEGER NOT NULL,done INTEGER NOT NULL DEFAULT 0,event INTEGER NOT NULL DEFAULT 0,flexible INTEGER NOT NULL DEFAULT 0,completed_date TEXT NOT NULL DEFAULT '',updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX tasks_date ON tasks(date,time)");
        db.execSQL("CREATE INDEX tasks_completed ON tasks(completed_date)");
        db.execSQL("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT,text TEXT NOT NULL,created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT,role TEXT NOT NULL,content TEXT NOT NULL,created_at INTEGER NOT NULL,task_id INTEGER NOT NULL DEFAULT 0,task_version INTEGER NOT NULL DEFAULT 0,action_undone INTEGER NOT NULL DEFAULT 0)");
        createPlannerTables(db);
        RoutineStore.createTables(db);
        PeriodPlanStore.createTables(db);
        createLearningTables(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        if(oldVersion<2) {
            // Existing entries stay fixed/protected. Never rebuild or discard private data.
            db.execSQL("ALTER TABLE tasks ADD COLUMN flexible INTEGER NOT NULL DEFAULT 0");
            createPlannerTables(db);
        }
        if(oldVersion<3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN task_id INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE messages ADD COLUMN task_version INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE messages ADD COLUMN action_undone INTEGER NOT NULL DEFAULT 0");
        }
        if(oldVersion<4)RoutineStore.createTables(db);
        if(oldVersion>=4&&oldVersion<5)db.execSQL("ALTER TABLE routines ADD COLUMN category TEXT NOT NULL DEFAULT 'daily'");
        if(oldVersion<5)PeriodPlanStore.createTables(db);
        if(oldVersion<6)createLearningTables(db);
    }
    private void createPlannerTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE planner_meta (id INTEGER PRIMARY KEY CHECK(id=1),revision INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("INSERT INTO planner_meta(id,revision) VALUES(1,0)");
        db.execSQL("CREATE TABLE schedule_reports (id INTEGER PRIMARY KEY AUTOINCREMENT,created_at INTEGER NOT NULL,date TEXT NOT NULL,reason TEXT NOT NULL,summary TEXT NOT NULL,undone INTEGER NOT NULL DEFAULT 0,applied_revision INTEGER NOT NULL,signature TEXT NOT NULL)");
        db.execSQL("CREATE INDEX schedule_reports_signature ON schedule_reports(signature,undone)");
        db.execSQL("CREATE TABLE schedule_moves (report_id INTEGER NOT NULL REFERENCES schedule_reports(id) ON DELETE CASCADE,task_id INTEGER NOT NULL,title TEXT NOT NULL,from_date TEXT NOT NULL,from_time TEXT NOT NULL,to_date TEXT NOT NULL,to_time TEXT NOT NULL,post_updated_at INTEGER NOT NULL,PRIMARY KEY(report_id,task_id))");
        db.execSQL("CREATE TABLE schedule_unscheduled (report_id INTEGER NOT NULL REFERENCES schedule_reports(id) ON DELETE CASCADE,task_id INTEGER NOT NULL,PRIMARY KEY(report_id,task_id))");
    }
private void createLearningTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS learning_sources (id INTEGER PRIMARY KEY AUTOINCREMENT,type TEXT NOT NULL,title TEXT NOT NULL,author TEXT NOT NULL DEFAULT '',uri TEXT NOT NULL DEFAULT '',file_path TEXT NOT NULL DEFAULT '',total_chunks INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
        try { db.execSQL("CREATE INDEX learning_sources_type ON learning_sources(type)"); } catch (android.database.sqlite.SQLiteException ignored) {}
        db.execSQL("CREATE TABLE IF NOT EXISTS learning_chunks (id INTEGER PRIMARY KEY AUTOINCREMENT,source_id INTEGER NOT NULL REFERENCES learning_sources(id) ON DELETE CASCADE,chunk_index INTEGER NOT NULL,content TEXT NOT NULL,token_count INTEGER NOT NULL,page_start INTEGER,page_end INTEGER,created_at INTEGER NOT NULL)");
        try { db.execSQL("CREATE INDEX learning_chunks_source ON learning_chunks(source_id,chunk_index)"); } catch (android.database.sqlite.SQLiteException ignored) {}
        db.execSQL("CREATE TABLE IF NOT EXISTS learning_concepts (id INTEGER PRIMARY KEY AUTOINCREMENT,source_id INTEGER NOT NULL REFERENCES learning_sources(id) ON DELETE CASCADE,name TEXT NOT NULL,description TEXT NOT NULL DEFAULT '',mastery REAL NOT NULL DEFAULT 0.0,last_reviewed INTEGER,review_interval_days INTEGER NOT NULL DEFAULT 1,ease_factor REAL NOT NULL DEFAULT 2.5,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
        try { db.execSQL("CREATE INDEX learning_concepts_source ON learning_concepts(source_id)"); } catch (android.database.sqlite.SQLiteException ignored) {}
        db.execSQL("CREATE TABLE IF NOT EXISTS learning_questions (id INTEGER PRIMARY KEY AUTOINCREMENT,concept_id INTEGER NOT NULL REFERENCES learning_concepts(id) ON DELETE CASCADE,type TEXT NOT NULL,question TEXT NOT NULL,answer TEXT NOT NULL,difficulty INTEGER NOT NULL DEFAULT 1,times_asked INTEGER NOT NULL DEFAULT 0,times_correct INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL)");
        try { db.execSQL("CREATE INDEX learning_questions_concept ON learning_questions(concept_id)"); } catch (android.database.sqlite.SQLiteException ignored) {}
        db.execSQL("CREATE TABLE IF NOT EXISTS learning_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT,date TEXT NOT NULL,started_at INTEGER NOT NULL,completed_at INTEGER,total_questions INTEGER NOT NULL DEFAULT 0,correct_answers INTEGER NOT NULL DEFAULT 0,mode TEXT NOT NULL DEFAULT 'review')");
        try { db.execSQL("CREATE INDEX learning_sessions_date ON learning_sessions(date)"); } catch (android.database.sqlite.SQLiteException ignored) {}
        db.execSQL("CREATE TABLE IF NOT EXISTS learning_answers (id INTEGER PRIMARY KEY AUTOINCREMENT,session_id INTEGER NOT NULL REFERENCES learning_sessions(id) ON DELETE CASCADE,question_id INTEGER NOT NULL REFERENCES learning_questions(id) ON DELETE CASCADE,user_answer TEXT NOT NULL,is_correct INTEGER NOT NULL DEFAULT 0,confidence INTEGER NOT NULL DEFAULT 0,time_taken_ms INTEGER NOT NULL,answered_at INTEGER NOT NULL)");
        try { db.execSQL("CREATE INDEX learning_answers_session ON learning_answers(session_id)"); } catch (android.database.sqlite.SQLiteException ignored) {}
        db.execSQL("CREATE TABLE IF NOT EXISTS learning_implementations (id INTEGER PRIMARY KEY AUTOINCREMENT,concept_id INTEGER NOT NULL REFERENCES learning_concepts(id) ON DELETE CASCADE,title TEXT NOT NULL,description TEXT NOT NULL,project_link TEXT NOT NULL DEFAULT '',evidence TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL,linked_task_id INTEGER NOT NULL DEFAULT 0)");
        try { db.execSQL("CREATE INDEX learning_implementations_concept ON learning_implementations(concept_id)"); } catch (android.database.sqlite.SQLiteException ignored) {}
    }
    private Task read(Cursor c) {
        Task t=new Task();
        t.id=c.getLong(c.getColumnIndexOrThrow("id")); t.title=c.getString(c.getColumnIndexOrThrow("title"));
        t.date=c.getString(c.getColumnIndexOrThrow("date")); t.time=c.getString(c.getColumnIndexOrThrow("time"));
        t.notes=c.getString(c.getColumnIndexOrThrow("notes")); t.durationMinutes=c.getInt(c.getColumnIndexOrThrow("duration"));
        t.reminderMinutes=c.getInt(c.getColumnIndexOrThrow("reminder")); t.done=c.getInt(c.getColumnIndexOrThrow("done"))==1;
        t.event=c.getInt(c.getColumnIndexOrThrow("event"))==1; t.flexible=c.getInt(c.getColumnIndexOrThrow("flexible"))==1; t.completedDate=c.getString(c.getColumnIndexOrThrow("completed_date"));
        t.updatedAt=c.getLong(c.getColumnIndexOrThrow("updated_at")); return t;
    }
    private List<Task> queryTasks(String where,String[] args) {
        List<Task> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("tasks",null,where,args,null,null,"date ASC, CASE WHEN time='' THEN 0 ELSE 1 END, time ASC,id ASC")) {
            while(c.moveToNext()) out.add(read(c));
        } return out;
    }
    public List<Task> tasks(String date) { return queryTasks("date=?",new String[]{date}); }
    public List<Task> range(String start,String end) { return queryTasks("date>=? AND date<=?",new String[]{start,end}); }
    public Task task(long id) { List<Task> ts=queryTasks("id=?",new String[]{Long.toString(id)}); return ts.isEmpty()?null:ts.get(0); }
    public synchronized long save(Task t) {
        DateRules.validate(t);t.title=t.title.trim();
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            Task old=t.id==0?null:task(t.id);
            if(t.id!=0 && old==null)throw new IllegalArgumentException("This entry no longer exists.");
            // Editors cannot overwrite a completion that changed while their dialog was open.
            if(old!=null) { t.done=old.done;t.completedDate=old.completedDate; }
            if(t.event) { t.done=false;t.completedDate="";t.flexible=false; }
            t.updatedAt=Math.max(System.currentTimeMillis(),old==null?0:old.updatedAt+1);
            ContentValues v=new ContentValues();v.put("title",t.title);v.put("date",t.date);v.put("time",t.time);v.put("notes",t.notes);
            v.put("duration",t.durationMinutes);v.put("reminder",t.reminderMinutes);v.put("done",t.done?1:0);v.put("event",t.event?1:0);v.put("flexible",t.flexible?1:0);
            v.put("completed_date",t.completedDate);v.put("updated_at",t.updatedAt);
            if(t.id==0)t.id=db.insertOrThrow("tasks",null,v);else db.update("tasks",v,"id=?",new String[]{Long.toString(t.id)});
            bumpRevision(db);db.setTransactionSuccessful();return t.id;
        } finally { db.endTransaction(); }
    }
    public synchronized void delete(long id) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try { if(db.delete("tasks","id=?",new String[]{Long.toString(id)})>0)bumpRevision(db);db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
    public synchronized void complete(long id,boolean done) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            Task t=task(id);
            if(t!=null && !t.event && t.done!=done) {
                ContentValues v=new ContentValues();v.put("done",done?1:0);v.put("completed_date",done?DateRules.today():"");
                v.put("updated_at",Math.max(System.currentTimeMillis(),t.updatedAt+1));db.update("tasks",v,"id=?",new String[]{Long.toString(id)});bumpRevision(db);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    private long revision(SQLiteDatabase db) {
        try(Cursor c=db.rawQuery("SELECT revision FROM planner_meta WHERE id=1",null)) { if(!c.moveToFirst())throw new IllegalStateException("Planner revision is missing.");return c.getLong(0); }
    }
    private long bumpRevision(SQLiteDatabase db) { db.execSQL("UPDATE planner_meta SET revision=revision+1 WHERE id=1");return revision(db); }
    long calendarRevision() {return revision(getReadableDatabase());}
    void routineChanged() {bumpRevision(getWritableDatabase());}
    public RoutinePlanner.Preview previewRoutine(Routine r,LocalDateTime now,int start,int end,int percent) {return new RoutineStore(this).preview(r,now,start,end,percent);}
    public long saveRoutine(RoutinePlanner.Preview p,LocalDateTime now,int start,int end,int percent) {return new RoutineStore(this).save(p,now,start,end,percent);}
    public List<Routine> routines() {return new RoutineStore(this).list();}
    public synchronized List<Task> routineTasks(long routineId) {
        List<Task> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT t.* FROM tasks t JOIN routine_entries e ON e.task_id=t.id WHERE e.routine_id=? ORDER BY t.date,t.time,t.id",new String[]{Long.toString(routineId)})) {
            while(c.moveToNext())result.add(read(c));
        }
        return result;
    }
    public int stopRoutine(long id,LocalDateTime now) {return new RoutineStore(this).stop(id,now);}
    public PeriodPlan.Preview previewPeriod(List<Routine> items,LocalDate first,LocalDate last,LocalDateTime now,int start,int end,int percent){return new PeriodPlanStore(this).preview(items,first,last,now,start,end,percent);}
    public long savePeriod(PeriodPlan.Preview p,LocalDateTime now,int start,int end,int percent){return new PeriodPlanStore(this).save(p,now,start,end,percent);}
    public List<PeriodPlan.Record> periodPlans(){return new PeriodPlanStore(this).records();}
    public boolean undoPeriod(long id){return new PeriodPlanStore(this).undo(id);}
    public long requestMove(long id,long version,String date,String time,LocalDateTime now){return AgentReschedule.apply(this,id,version,date,time,now);}
    public String taskCategory(long id){try(Cursor c=getReadableDatabase().rawQuery("SELECT r.category FROM routines r JOIN routine_entries e ON e.routine_id=r.id WHERE e.task_id=?",new String[]{Long.toString(id)})){return c.moveToFirst()?c.getString(0):"";}}

    /** Apply one pure deterministic plan and its audit report in the same SQLite transaction. */
    public synchronized long replan(LocalDate date,LocalTime now,int start,int end,int percent,String reason) {
        Scheduler.validateWindow(start,end,percent);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            List<Task> entries=range(date.minusDays(1).toString(),date.toString());
            Scheduler.Plan plan=Scheduler.plan(entries,date,now,start,end,percent);
            if(plan.moves.isEmpty() && plan.unscheduled.isEmpty()) { db.setTransactionSuccessful();return 0; }
            Map<Long,Long> versions=new HashMap<>();
            for(Scheduler.Move move:plan.moves) {
                Task t=task(move.taskId);
                if(t==null || !t.flexible || t.event || t.done)throw new IllegalStateException("An entry changed while planning. Please try again.");
                long updated=Math.max(System.currentTimeMillis(),t.updatedAt+1);
                ContentValues values=new ContentValues();values.put("date",move.toDate);values.put("time",move.toTime);values.put("updated_at",updated);
                db.update("tasks",values,"id=?",new String[]{Long.toString(t.id)});versions.put(t.id,updated);
                RoutineStore.followAutomaticMove(db,t.id,t.updatedAt,updated);
            }
            long applied=plan.moves.isEmpty()?revision(db):bumpRevision(db);
            String signature=signature(range(date.minusDays(1).toString(),date.toString()),date,start,end,percent,plan.unscheduled);
            if(plan.moves.isEmpty())try(Cursor cursor=db.rawQuery("SELECT id FROM schedule_reports WHERE signature=? AND undone=0 LIMIT 1",new String[]{signature})) {
                if(cursor.moveToFirst()) { db.setTransactionSuccessful();return 0; }
            }
            ContentValues report=new ContentValues();report.put("created_at",System.currentTimeMillis());report.put("date",date.toString());report.put("reason",reason==null?"Calendar check":reason);
            report.put("summary",plan.summary);report.put("applied_revision",applied);report.put("signature",signature);
            long id=db.insertOrThrow("schedule_reports",null,report);
            for(Scheduler.Move move:plan.moves) {
                ContentValues values=new ContentValues();values.put("report_id",id);values.put("task_id",move.taskId);values.put("title",move.title);
                values.put("from_date",move.fromDate);values.put("from_time",move.fromTime);values.put("to_date",move.toDate);values.put("to_time",move.toTime);values.put("post_updated_at",versions.get(move.taskId));
                db.insertOrThrow("schedule_moves",null,values);
            }
            for(long taskId:plan.unscheduled) { ContentValues values=new ContentValues();values.put("report_id",id);values.put("task_id",taskId);db.insertOrThrow("schedule_unscheduled",null,values); }
            db.setTransactionSuccessful();return id;
        } finally { db.endTransaction(); }
    }
    private String signature(List<Task> entries,LocalDate date,int start,int end,int percent,List<Long> unscheduled) {
        StringBuilder value=new StringBuilder(date.toString()).append('|').append(start).append('|').append(end).append('|').append(percent);
        entries.sort(Comparator.comparingLong(t->t.id));
        for(Task t:entries)value.append('|').append(t.id).append(':').append(t.updatedAt);
        List<Long> unscheduledIds=new ArrayList<>(unscheduled);Collections.sort(unscheduledIds);
        for(long id:unscheduledIds)value.append("|u:").append(id);
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();
            for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
        } catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public synchronized List<ScheduleChange> changeReports() {
        SQLiteDatabase db=getReadableDatabase();List<ScheduleChange> changes=new ArrayList<>();
        db.beginTransaction();
        try {
            long current=revision(db);
            try(Cursor c=db.query("schedule_reports",null,null,null,null,null,"id DESC")) {
                while(c.moveToNext()) {
                    ScheduleChange change=new ScheduleChange();change.id=c.getLong(c.getColumnIndexOrThrow("id"));change.createdAt=c.getLong(c.getColumnIndexOrThrow("created_at"));
                    change.date=c.getString(c.getColumnIndexOrThrow("date"));change.reason=c.getString(c.getColumnIndexOrThrow("reason"));change.summary=c.getString(c.getColumnIndexOrThrow("summary"));change.undone=c.getInt(c.getColumnIndexOrThrow("undone"))!=0;
                    loadMoves(db,change);change.canUndo=!change.undone && !change.moves.isEmpty() && current==c.getLong(c.getColumnIndexOrThrow("applied_revision")) && movesAreCurrent(db,change.id);
                    changes.add(change);
                }
            }
            db.setTransactionSuccessful();return changes;
        } finally { db.endTransaction(); }
    }
    private void loadMoves(SQLiteDatabase db,ScheduleChange change) {
        String[] args={Long.toString(change.id)};
        try(Cursor c=db.query("schedule_moves",null,"report_id=?",args,null,null,"task_id ASC")) {
            while(c.moveToNext())change.moves.add(new Scheduler.Move(c.getLong(c.getColumnIndexOrThrow("task_id")),c.getString(c.getColumnIndexOrThrow("title")),c.getString(c.getColumnIndexOrThrow("from_date")),c.getString(c.getColumnIndexOrThrow("from_time")),c.getString(c.getColumnIndexOrThrow("to_date")),c.getString(c.getColumnIndexOrThrow("to_time"))));
        }
        try(Cursor c=db.query("schedule_unscheduled",new String[]{"task_id"},"report_id=?",args,null,null,"task_id ASC")) { while(c.moveToNext())change.unscheduled.add(c.getLong(0)); }
    }
    private boolean movesAreCurrent(SQLiteDatabase db,long reportId) {
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM schedule_moves m LEFT JOIN tasks t ON t.id=m.task_id WHERE m.report_id=? AND (t.id IS NULL OR t.updated_at<>m.post_updated_at OR t.date<>m.to_date OR t.time<>m.to_time OR t.flexible<>1 OR t.event<>0 OR t.done<>0)",new String[]{Long.toString(reportId)})) { return c.moveToFirst() && c.getInt(0)==0; }
    }
    /** Undo is all-or-nothing and refuses any calendar edits made after this report. */
    public synchronized boolean undoReport(long reportId) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            ScheduleChange change=new ScheduleChange();change.id=reportId;
            try(Cursor c=db.query("schedule_reports",null,"id=?",new String[]{Long.toString(reportId)},null,null,null)) {
                if(!c.moveToFirst() || c.getInt(c.getColumnIndexOrThrow("undone"))!=0 || c.getLong(c.getColumnIndexOrThrow("applied_revision"))!=revision(db))return false;
            }
            loadMoves(db,change);if(change.moves.isEmpty() || !movesAreCurrent(db,reportId))return false;
            for(Scheduler.Move move:change.moves) {
                Task t=task(move.taskId);ContentValues values=new ContentValues();values.put("date",move.fromDate);values.put("time",move.fromTime);values.put("updated_at",Math.max(System.currentTimeMillis(),t.updatedAt+1));
                db.update("tasks",values,"id=?",new String[]{Long.toString(move.taskId)});
                RoutineStore.followAutomaticMove(db,t.id,t.updatedAt,values.getAsLong("updated_at"));
            }
            ContentValues values=new ContentValues();values.put("undone",1);db.update("schedule_reports",values,"id=?",new String[]{Long.toString(reportId)});bumpRevision(db);db.setTransactionSuccessful();return true;
        } finally { db.endTransaction(); }
    }
    public Map<String,Integer> completionCounts(String start,String end) {
        Map<String,Integer> out=new HashMap<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT completed_date,COUNT(*) FROM tasks WHERE done=1 AND event=0 AND completed_date>=? AND completed_date<=? GROUP BY completed_date",new String[]{start,end})) {
            while(c.moveToNext()) out.put(c.getString(0),c.getInt(1));
        } return out;
    }
    public List<Memory> memories() {
        List<Memory> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("memories",null,null,null,null,null,"id DESC")) { while(c.moveToNext()) { Memory m=new Memory(); m.id=c.getLong(0);m.text=c.getString(1);m.createdAt=c.getLong(2);out.add(m); } }
        return out;
    }
    public long remember(String text) {
        if(text==null || text.trim().isEmpty() || text.length()>4000) throw new IllegalArgumentException("Memory must contain 1 to 4000 characters.");
        ContentValues v=new ContentValues(); v.put("text",text.trim());v.put("created_at",System.currentTimeMillis());return getWritableDatabase().insertOrThrow("memories",null,v);
    }
    public void forget(long id) { getWritableDatabase().delete("memories","id=?",new String[]{Long.toString(id)}); }
    /** Explicit chat action: entry and human-readable confirmation are committed together. */
    public synchronized ChatMessage scheduleFromChat(Task t) {
        DateRules.validate(t);
        if(t.id!=0||t.time.isEmpty())throw new IllegalArgumentException("Give the new task a start time.");
        LocalDateTime start=LocalDate.parse(t.date).atTime(LocalTime.parse(t.time)),end=start.plusMinutes(t.durationMinutes);
        if(!start.isAfter(LocalDateTime.now()))throw new IllegalArgumentException("That start time has passed. Choose a future time.");
        SQLiteDatabase sql=getWritableDatabase();sql.beginTransaction();
        try {
            for(Task existing:range(LocalDate.parse(t.date).minusDays(1).toString(),end.toLocalDate().toString())) {
                if(existing.date.equals(t.date)&&existing.time.equals(t.time)&&existing.title.equalsIgnoreCase(t.title.trim())&&existing.durationMinutes==t.durationMinutes&&existing.event==t.event) {
                    ChatMessage same=calendarMessage(null,"Already scheduled: "+existing.title+" on "+existing.date+" at "+existing.time+". No duplicate was added.");sql.setTransactionSuccessful();return same;
                }
                if(existing.time.isEmpty()) {
                    if(existing.event&& !LocalDate.parse(existing.date).isBefore(start.toLocalDate())&&!LocalDate.parse(existing.date).isAfter(end.minusNanos(1).toLocalDate()))
                        throw new IllegalArgumentException("That time is occupied by an all-day commitment. Choose another time.");
                    continue;
                }
                LocalDateTime other=LocalDate.parse(existing.date).atTime(LocalTime.parse(existing.time));
                if(start.isBefore(other.plusMinutes(existing.durationMinutes))&&end.isAfter(other))throw new IllegalArgumentException("That time is occupied by "+existing.title+". Choose another time; your existing entries stay in place.");
            }
            save(t);
            String text="Scheduled "+t.title+" on "+t.date+" at "+t.time+" for "+t.durationMinutes+" minutes.";
            if(t.notes.startsWith("Used a 30-minute"))text+=" I used the default 30-minute duration.";
            ChatMessage saved=calendarMessage(t,text);sql.setTransactionSuccessful();return saved;
        }finally{sql.endTransaction();}
    }
    private ChatMessage calendarMessage(Task task,String text) {
        ChatMessage m=new ChatMessage();m.role="assistant";m.content=text;m.createdAt=System.currentTimeMillis();
        if(task!=null){m.taskId=task.id;m.taskVersion=task.updatedAt;m.canUndoCreation=true;}
        ContentValues v=new ContentValues();v.put("role",m.role);v.put("content",text);v.put("created_at",m.createdAt);v.put("task_id",m.taskId);v.put("task_version",m.taskVersion);
        m.id=getWritableDatabase().insertOrThrow("messages",null,v);return m;
    }
    public synchronized boolean undoChatCreation(long messageId) {
        SQLiteDatabase sql=getWritableDatabase();sql.beginTransaction();
        try(Cursor c=sql.query("messages",new String[]{"task_id","task_version","action_undone"},"id=?",new String[]{Long.toString(messageId)},null,null,null)) {
            if(!c.moveToFirst()||c.getLong(0)==0||c.getInt(2)!=0)return false;
            Task t=task(c.getLong(0));if(t==null||t.updatedAt!=c.getLong(1))return false;
            delete(t.id);ContentValues v=new ContentValues();v.put("action_undone",1);sql.update("messages",v,"id=?",new String[]{Long.toString(messageId)});
            sql.setTransactionSuccessful();return true;
        }finally{sql.endTransaction();}
    }
    public List<ChatMessage> messages() {
        List<ChatMessage> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM (SELECT * FROM messages ORDER BY id DESC LIMIT 200) ORDER BY id ASC",null)) {
            while(c.moveToNext()) {
                ChatMessage m=new ChatMessage();m.id=c.getLong(0);m.role=c.getString(1);m.content=c.getString(2);m.createdAt=c.getLong(3);
                m.taskId=c.getLong(c.getColumnIndexOrThrow("task_id"));m.taskVersion=c.getLong(c.getColumnIndexOrThrow("task_version"));m.creationUndone=c.getInt(c.getColumnIndexOrThrow("action_undone"))!=0;
                Task saved=m.taskId==0?null:task(m.taskId);m.canUndoCreation=!m.creationUndone&&saved!=null&&saved.updatedAt==m.taskVersion;out.add(m);
            }
        } return out;
    }
    public void message(String role,String content) {
        if(!"user".equals(role) && !"assistant".equals(role)) throw new IllegalArgumentException("Unsupported message role.");
        if(content==null || content.trim().isEmpty()) return;
        ContentValues v=new ContentValues();v.put("role",role);v.put("content",content);v.put("created_at",System.currentTimeMillis());getWritableDatabase().insertOrThrow("messages",null,v);
    }
    public void clearMessages() { getWritableDatabase().delete("messages",null,null); }

    private LearningSource readSource(Cursor c) {
        LearningSource s=new LearningSource();
        s.id=c.getLong(c.getColumnIndexOrThrow("id"));
        s.type=c.getString(c.getColumnIndexOrThrow("type"));
        s.title=c.getString(c.getColumnIndexOrThrow("title"));
        s.author=c.getString(c.getColumnIndexOrThrow("author"));
        s.uri=c.getString(c.getColumnIndexOrThrow("uri"));
        s.filePath=c.getString(c.getColumnIndexOrThrow("file_path"));
        s.totalChunks=c.getInt(c.getColumnIndexOrThrow("total_chunks"));
        s.createdAt=c.getLong(c.getColumnIndexOrThrow("created_at"));
        s.updatedAt=c.getLong(c.getColumnIndexOrThrow("updated_at"));
        return s;
    }
    public synchronized long saveSource(LearningSource s) {
        long now=System.currentTimeMillis();
        if(s.id==0) s.createdAt=now;
        s.updatedAt=now;
        ContentValues v=new ContentValues();
        v.put("type",s.type);v.put("title",s.title);v.put("author",s.author);v.put("uri",s.uri);
        v.put("file_path",s.filePath);v.put("total_chunks",s.totalChunks);
        v.put("created_at",s.createdAt);v.put("updated_at",s.updatedAt);
        SQLiteDatabase db=getWritableDatabase();
        if(s.id==0) s.id=db.insertOrThrow("learning_sources",null,v);
        else db.update("learning_sources",v,"id=?",new String[]{Long.toString(s.id)});
        return s.id;
    }
    public LearningSource getSource(long id) {
        try(Cursor c=getReadableDatabase().query("learning_sources",null,"id=?",new String[]{Long.toString(id)},null,null,null)) {
            return c.moveToFirst()?readSource(c):null;
        }
    }
    public List<LearningSource> getSources(String type) {
        List<LearningSource> out=new ArrayList<>();
        String where=type==null?null:"type=?";
        String[] args=type==null?null:new String[]{type};
        try(Cursor c=getReadableDatabase().query("learning_sources",null,where,args,null,null,"updated_at DESC")) {
            while(c.moveToNext()) out.add(readSource(c));
        }
        return out;
    }
    public synchronized void deleteSource(long id) {
        getWritableDatabase().delete("learning_sources","id=?",new String[]{Long.toString(id)});
    }

    private LearningChunk readChunk(Cursor c) {
        LearningChunk ch=new LearningChunk();
        ch.id=c.getLong(c.getColumnIndexOrThrow("id"));
        ch.sourceId=c.getLong(c.getColumnIndexOrThrow("source_id"));
        ch.chunkIndex=c.getInt(c.getColumnIndexOrThrow("chunk_index"));
        ch.content=c.getString(c.getColumnIndexOrThrow("content"));
        ch.tokenCount=c.getInt(c.getColumnIndexOrThrow("token_count"));
        int psIdx=c.getColumnIndex("page_start");
        int peIdx=c.getColumnIndex("page_end");
        if(psIdx>=0 && !c.isNull(psIdx)) ch.pageStart=c.getInt(psIdx);
        if(peIdx>=0 && !c.isNull(peIdx)) ch.pageEnd=c.getInt(peIdx);
        ch.createdAt=c.getLong(c.getColumnIndexOrThrow("created_at"));
        return ch;
    }
    public synchronized long saveChunk(LearningChunk ch) {
        ContentValues v=new ContentValues();
        v.put("source_id",ch.sourceId);v.put("chunk_index",ch.chunkIndex);v.put("content",ch.content);
        v.put("token_count",ch.tokenCount);
        if(ch.pageStart!=null) v.put("page_start",ch.pageStart);
        if(ch.pageEnd!=null) v.put("page_end",ch.pageEnd);
        v.put("created_at",ch.createdAt);
        SQLiteDatabase db=getWritableDatabase();
        if(ch.id==0) ch.id=db.insertOrThrow("learning_chunks",null,v);
        else db.update("learning_chunks",v,"id=?",new String[]{Long.toString(ch.id)});
        return ch.id;
    }
    public List<LearningChunk> getChunks(long sourceId) {
        List<LearningChunk> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("learning_chunks",null,"source_id=?",new String[]{Long.toString(sourceId)},null,null,"chunk_index ASC")) {
            while(c.moveToNext()) out.add(readChunk(c));
        }
        return out;
    }
    public synchronized void deleteChunks(long sourceId) {
        getWritableDatabase().delete("learning_chunks","source_id=?",new String[]{Long.toString(sourceId)});
    }

    private LearningConcept readConcept(Cursor c) {
        LearningConcept con=new LearningConcept();
        con.id=c.getLong(c.getColumnIndexOrThrow("id"));
        con.sourceId=c.getLong(c.getColumnIndexOrThrow("source_id"));
        con.name=c.getString(c.getColumnIndexOrThrow("name"));
        con.description=c.getString(c.getColumnIndexOrThrow("description"));
        con.mastery=c.getDouble(c.getColumnIndexOrThrow("mastery"));
        int lrIdx=c.getColumnIndex("last_reviewed");
        if(lrIdx>=0 && !c.isNull(lrIdx)) con.lastReviewed=c.getLong(lrIdx);
        con.reviewIntervalDays=c.getInt(c.getColumnIndexOrThrow("review_interval_days"));
        con.easeFactor=c.getDouble(c.getColumnIndexOrThrow("ease_factor"));
        con.createdAt=c.getLong(c.getColumnIndexOrThrow("created_at"));
        con.updatedAt=c.getLong(c.getColumnIndexOrThrow("updated_at"));
        return con;
    }
    public synchronized long saveConcept(LearningConcept con) {
        long now=System.currentTimeMillis();
        if(con.id==0) con.createdAt=now;
        con.updatedAt=now;
        ContentValues v=new ContentValues();
        v.put("source_id",con.sourceId);v.put("name",con.name);v.put("description",con.description);
        v.put("mastery",con.mastery);
        if(con.lastReviewed!=null) v.put("last_reviewed",con.lastReviewed);
        v.put("review_interval_days",con.reviewIntervalDays);v.put("ease_factor",con.easeFactor);
        v.put("created_at",con.createdAt);v.put("updated_at",con.updatedAt);
        SQLiteDatabase db=getWritableDatabase();
        if(con.id==0) con.id=db.insertOrThrow("learning_concepts",null,v);
        else db.update("learning_concepts",v,"id=?",new String[]{Long.toString(con.id)});
        return con.id;
    }
    public LearningConcept getConcept(long id) {
        try(Cursor c=getReadableDatabase().query("learning_concepts",null,"id=?",new String[]{Long.toString(id)},null,null,null)) {
            return c.moveToFirst()?readConcept(c):null;
        }
    }
    public List<LearningConcept> getConcepts(long sourceId) {
        List<LearningConcept> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("learning_concepts",null,"source_id=?",new String[]{Long.toString(sourceId)},null,null,"mastery ASC,name ASC")) {
            while(c.moveToNext()) out.add(readConcept(c));
        }
        return out;
    }
    public List<LearningConcept> getConceptsDueForReview() {
        List<LearningConcept> out=new ArrayList<>();
        long now=System.currentTimeMillis();
        try(Cursor c=getReadableDatabase().rawQuery(
            "SELECT * FROM learning_concepts WHERE last_reviewed IS NULL OR last_reviewed + review_interval_days * 86400000 <= ? ORDER BY mastery ASC",
            new String[]{Long.toString(now)})) {
            while(c.moveToNext()) out.add(readConcept(c));
        }
        return out;
    }

    private LearningQuestion readQuestion(Cursor c) {
        LearningQuestion q=new LearningQuestion();
        q.id=c.getLong(c.getColumnIndexOrThrow("id"));
        q.conceptId=c.getLong(c.getColumnIndexOrThrow("concept_id"));
        q.type=c.getString(c.getColumnIndexOrThrow("type"));
        q.question=c.getString(c.getColumnIndexOrThrow("question"));
        q.answer=c.getString(c.getColumnIndexOrThrow("answer"));
        q.difficulty=c.getInt(c.getColumnIndexOrThrow("difficulty"));
        q.timesAsked=c.getInt(c.getColumnIndexOrThrow("times_asked"));
        q.timesCorrect=c.getInt(c.getColumnIndexOrThrow("times_correct"));
        q.createdAt=c.getLong(c.getColumnIndexOrThrow("created_at"));
        return q;
    }
    public synchronized long saveQuestion(LearningQuestion q) {
        ContentValues v=new ContentValues();
        v.put("concept_id",q.conceptId);v.put("type",q.type);v.put("question",q.question);
        v.put("answer",q.answer);v.put("difficulty",q.difficulty);
        v.put("times_asked",q.timesAsked);v.put("times_correct",q.timesCorrect);
        v.put("created_at",q.createdAt);
        SQLiteDatabase db=getWritableDatabase();
        if(q.id==0) q.id=db.insertOrThrow("learning_questions",null,v);
        else db.update("learning_questions",v,"id=?",new String[]{Long.toString(q.id)});
        return q.id;
    }
    public LearningQuestion getQuestion(long id) {
        try(Cursor c=getReadableDatabase().query("learning_questions",null,"id=?",new String[]{Long.toString(id)},null,null,null)) {
            return c.moveToFirst()?readQuestion(c):null;
        }
    }
    public List<LearningQuestion> getQuestions(long conceptId) {
        List<LearningQuestion> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("learning_questions",null,"concept_id=?",new String[]{Long.toString(conceptId)},null,null,"times_asked ASC,difficulty ASC")) {
            while(c.moveToNext()) out.add(readQuestion(c));
        }
        return out;
    }
    public List<LearningQuestion> getRandomQuestions(List<Long> conceptIds,int limit) {
        if(conceptIds.isEmpty()) return new ArrayList<>();
        String placeholders=String.join(",", Collections.nCopies(conceptIds.size(),"?"));
        List<LearningQuestion> out=new ArrayList<>();
        String[] args=new String[conceptIds.size()+1];
        for(int i=0;i<conceptIds.size();i++) args[i]=Long.toString(conceptIds.get(i));
        args[args.length-1]=String.valueOf(limit);
        try(Cursor c=getReadableDatabase().rawQuery(
            "SELECT * FROM learning_questions WHERE concept_id IN ("+placeholders+") ORDER BY RANDOM() LIMIT ?",args)) {
            while(c.moveToNext()) out.add(readQuestion(c));
        }
        return out;
    }

    private LearningSession readSession(Cursor c) {
        LearningSession s=new LearningSession();
        s.id=c.getLong(c.getColumnIndexOrThrow("id"));
        s.date=c.getString(c.getColumnIndexOrThrow("date"));
        s.startedAt=c.getLong(c.getColumnIndexOrThrow("started_at"));
        int caIdx=c.getColumnIndex("completed_at");
        if(caIdx>=0 && !c.isNull(caIdx)) s.completedAt=c.getLong(caIdx);
        s.totalQuestions=c.getInt(c.getColumnIndexOrThrow("total_questions"));
        s.correctAnswers=c.getInt(c.getColumnIndexOrThrow("correct_answers"));
        s.mode=c.getString(c.getColumnIndexOrThrow("mode"));
        return s;
    }
    public synchronized long saveSession(LearningSession s) {
        ContentValues v=new ContentValues();
        v.put("date",s.date);v.put("started_at",s.startedAt);
        if(s.completedAt!=null) v.put("completed_at",s.completedAt);
        v.put("total_questions",s.totalQuestions);v.put("correct_answers",s.correctAnswers);
        v.put("mode",s.mode);
        SQLiteDatabase db=getWritableDatabase();
        if(s.id==0) s.id=db.insertOrThrow("learning_sessions",null,v);
        else db.update("learning_sessions",v,"id=?",new String[]{Long.toString(s.id)});
        return s.id;
    }
    public LearningSession getSession(long id) {
        try(Cursor c=getReadableDatabase().query("learning_sessions",null,"id=?",new String[]{Long.toString(id)},null,null,null)) {
            return c.moveToFirst()?readSession(c):null;
        }
    }
    public List<LearningSession> getSessions(String startDate,String endDate) {
        List<LearningSession> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("learning_sessions",null,"date>=? AND date<=?",new String[]{startDate,endDate},null,null,"started_at DESC")) {
            while(c.moveToNext()) out.add(readSession(c));
        }
        return out;
    }

    private LearningAnswer readAnswer(Cursor c) {
        LearningAnswer a=new LearningAnswer();
        a.id=c.getLong(c.getColumnIndexOrThrow("id"));
        a.sessionId=c.getLong(c.getColumnIndexOrThrow("session_id"));
        a.questionId=c.getLong(c.getColumnIndexOrThrow("question_id"));
        a.userAnswer=c.getString(c.getColumnIndexOrThrow("user_answer"));
        a.isCorrect=c.getInt(c.getColumnIndexOrThrow("is_correct"))==1;
        a.confidence=c.getInt(c.getColumnIndexOrThrow("confidence"));
        a.timeTakenMs=c.getLong(c.getColumnIndexOrThrow("time_taken_ms"));
        a.answeredAt=c.getLong(c.getColumnIndexOrThrow("answered_at"));
        return a;
    }
    public synchronized long saveAnswer(LearningAnswer a) {
        ContentValues v=new ContentValues();
        v.put("session_id",a.sessionId);v.put("question_id",a.questionId);v.put("user_answer",a.userAnswer);
        v.put("is_correct",a.isCorrect?1:0);v.put("confidence",a.confidence);
        v.put("time_taken_ms",a.timeTakenMs);v.put("answered_at",a.answeredAt);
        SQLiteDatabase db=getWritableDatabase();
        if(a.id==0) a.id=db.insertOrThrow("learning_answers",null,v);
        else db.update("learning_answers",v,"id=?",new String[]{Long.toString(a.id)});
        return a.id;
    }
    public List<LearningAnswer> getAnswers(long sessionId) {
        List<LearningAnswer> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("learning_answers",null,"session_id=?",new String[]{Long.toString(sessionId)},null,null,"answered_at ASC")) {
            while(c.moveToNext()) out.add(readAnswer(c));
        }
        return out;
    }

    private LearningImplementation readImplementation(Cursor c) {
        LearningImplementation impl=new LearningImplementation();
        impl.id=c.getLong(c.getColumnIndexOrThrow("id"));
        impl.conceptId=c.getLong(c.getColumnIndexOrThrow("concept_id"));
        impl.title=c.getString(c.getColumnIndexOrThrow("title"));
        impl.description=c.getString(c.getColumnIndexOrThrow("description"));
        impl.projectLink=c.getString(c.getColumnIndexOrThrow("project_link"));
        impl.evidence=c.getString(c.getColumnIndexOrThrow("evidence"));
        impl.createdAt=c.getLong(c.getColumnIndexOrThrow("created_at"));
        impl.linkedTaskId=c.getLong(c.getColumnIndexOrThrow("linked_task_id"));
        return impl;
    }
    public synchronized long saveImplementation(LearningImplementation impl) {
        ContentValues v=new ContentValues();
        v.put("concept_id",impl.conceptId);v.put("title",impl.title);v.put("description",impl.description);
        v.put("project_link",impl.projectLink);v.put("evidence",impl.evidence);
        v.put("created_at",impl.createdAt);v.put("linked_task_id",impl.linkedTaskId);
        SQLiteDatabase db=getWritableDatabase();
        if(impl.id==0) impl.id=db.insertOrThrow("learning_implementations",null,v);
        else db.update("learning_implementations",v,"id=?",new String[]{Long.toString(impl.id)});
        return impl.id;
    }
    public List<LearningImplementation> getImplementations(long conceptId) {
        List<LearningImplementation> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("learning_implementations",null,"concept_id=?",new String[]{Long.toString(conceptId)},null,null,"created_at DESC")) {
            while(c.moveToNext()) out.add(readImplementation(c));
        }
        return out;
    }
}
